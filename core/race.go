package core

import (
	"context"
	"sync"
	"time"
)

// Racer starts several engines at once and keeps the first one that proves it
// actually carries traffic. Starting is not the same as working: an engine can
// bring up a local proxy and still be dead on the wire, so nothing counts as a
// winner until a probe has come back through it.
//
// One engine is kept warm behind the winner. That standby is the whole reason
// a drop feels like nothing happened: when the active engine dies there is
// already a live tunnel to move to, so there is no reconnect to sit through.

type Racer struct {
	engines   []Engine
	board     *Scoreboard
	probe     Prober
	log       func(string)

	mu       sync.Mutex
	active   *runner
	standby  *runner
	stopping bool
}

type runner struct {
	engine  Engine
	session *Session
	cancel  context.CancelFunc
	latency time.Duration
}

const (
	// How long to give a single engine before writing it off for this attempt.
	engineDeadline = 25 * time.Second

	// Gap between launches. Everything at once floods a weak mobile link and
	// makes every engine look slower than it is, so they go off in a ladder.
	launchStagger = 1200 * time.Millisecond

	// How often the active tunnel is checked once it is up.
	healthEvery = 15 * time.Second

	// Consecutive failed probes before we give up on the active engine.
	healthTolerance = 2
)

func NewRacer(engines []Engine, board *Scoreboard, probe Prober, log func(string)) *Racer {
	return &Racer{engines: engines, board: board, probe: probe, log: log}
}

// Connect brings up a tunnel and returns once traffic is flowing. Order comes
// from the scoreboard, so on any launch after the first, whatever worked last
// time on this network goes first and the whole thing usually finishes in one
// round trip instead of a race.
func (r *Racer) Connect(ctx context.Context, net NetworkID) (*Session, error) {
	order := r.board.Order(net, r.engines)

	type result struct {
		run *runner
		err error
	}
	results := make(chan result, len(order))

	raceCtx, cancelRace := context.WithCancel(ctx)
	var wg sync.WaitGroup

	for i, eng := range order {
		if eng.NeedsBootstrap() && !r.board.Bootstrapped(eng.Name()) {
			r.logf("skipping " + eng.Name() + ", nothing to bootstrap from yet")
			continue
		}

		wg.Add(1)
		go func(idx int, e Engine) {
			defer wg.Done()

			select {
			case <-time.After(time.Duration(idx) * launchStagger):
			case <-raceCtx.Done():
				return
			}

			runCtx, cancel := context.WithTimeout(raceCtx, engineDeadline)
			started := time.Now()

			sess, err := e.Start(runCtx)
			if err != nil {
				cancel()
				r.board.Record(net, e.Name(), false, 0)
				results <- result{err: err}
				return
			}

			// Starting proves nothing. Push a probe through it.
			if err := r.probe.Through(runCtx, sess.SocksPort); err != nil {
				cancel()
				e.Stop()
				r.board.Record(net, e.Name(), false, 0)
				results <- result{err: err}
				return
			}

			took := time.Since(started)
			r.board.Record(net, e.Name(), true, took)
			results <- result{run: &runner{engine: e, session: sess, cancel: cancel, latency: took}}
		}(i, eng)
	}

	go func() { wg.Wait(); close(results) }()

	var winner *runner
	var lastErr error

	for res := range results {
		if res.err != nil {
			lastErr = res.err
			continue
		}
		if winner == nil {
			winner = res.run
			r.logf("up on " + winner.engine.Name() + " in " + winner.latency.Truncate(time.Millisecond).String())
			continue
		}
		// A second engine came up after we already had one. Keep it warm as
		// the standby, but only if it fails differently from the winner —
		// two engines of the same shape tend to die in the same instant.
		if r.standbyWanted(winner, res.run) {
			r.setStandby(res.run)
			continue
		}
		res.run.cancel()
		res.run.engine.Stop()
	}

	cancelRace()

	if winner == nil {
		return nil, lastErr
	}

	r.mu.Lock()
	r.active = winner
	r.mu.Unlock()

	go r.watch(ctx, net)
	return winner.session, nil
}

func (r *Racer) standbyWanted(active, candidate *runner) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.standby != nil {
		return false
	}
	return candidate.engine.Shape() != active.engine.Shape()
}

func (r *Racer) setStandby(run *runner) {
	r.mu.Lock()
	r.standby = run
	r.mu.Unlock()
	r.logf("holding " + run.engine.Name() + " warm as a standby")
}

// watch keeps probing the live tunnel and swaps to the standby the moment it
// stops answering.
func (r *Racer) watch(ctx context.Context, net NetworkID) {
	misses := 0
	ticker := time.NewTicker(healthEvery)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}

		r.mu.Lock()
		active := r.active
		stopping := r.stopping
		r.mu.Unlock()
		if stopping || active == nil {
			return
		}

		checkCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
		err := r.probe.Through(checkCtx, active.session.SocksPort)
		cancel()

		if err == nil {
			misses = 0
			continue
		}

		misses++
		if misses < healthTolerance {
			continue
		}

		r.logf(active.engine.Name() + " stopped answering, moving over")
		r.board.Record(net, active.engine.Name(), false, 0)
		misses = 0

		if !r.promoteStandby() {
			// Nothing warm to move to. Start the whole race again rather
			// than leaving the user staring at a dead tunnel.
			go func() {
				if _, err := r.Connect(ctx, net); err != nil {
					r.logf("could not find another way out")
				}
			}()
			return
		}
	}
}

func (r *Racer) promoteStandby() bool {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.standby == nil {
		return false
	}
	old := r.active
	r.active = r.standby
	r.standby = nil

	go func() {
		if old != nil {
			old.cancel()
			old.engine.Stop()
		}
	}()
	return true
}

// Active reports the live session, or nil.
func (r *Racer) Active() *Session {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.active == nil {
		return nil
	}
	return r.active.session
}

func (r *Racer) Stop() {
	r.mu.Lock()
	r.stopping = true
	active, standby := r.active, r.standby
	r.active, r.standby = nil, nil
	r.mu.Unlock()

	for _, run := range []*runner{active, standby} {
		if run != nil {
			run.cancel()
			run.engine.Stop()
		}
	}
}

func (r *Racer) logf(msg string) {
	if r.log != nil {
		r.log(msg)
	}
}
