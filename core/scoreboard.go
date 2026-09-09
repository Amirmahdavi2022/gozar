package core

import (
	"encoding/json"
	"os"
	"sort"
	"sync"
	"time"
)

// NetworkID identifies the network the phone is on right now. What works on
// home wifi is often not what works on mobile data, and an engine that died on
// one carrier says nothing about the other, so every score is kept per network.
//
// It is a coarse fingerprint on purpose: no SSID, no IP, nothing that would be
// worth handing to anyone who got hold of the phone.
type NetworkID string

type record struct {
	Wins     int           `json:"wins"`
	Losses   int           `json:"losses"`
	Latency  time.Duration `json:"latency"` // moving average of time to first working probe
	LastGood time.Time     `json:"last_good"`
}

type Scoreboard struct {
	path string

	mu     sync.Mutex
	Nets   map[NetworkID]map[string]*record `json:"nets"`
	Boot   map[string]bool                  `json:"bootstrapped"`
	dirty  bool
}

func LoadScoreboard(path string) *Scoreboard {
	b := &Scoreboard{
		path: path,
		Nets: map[NetworkID]map[string]*record{},
		Boot: map[string]bool{},
	}
	if data, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(data, b)
		if b.Nets == nil {
			b.Nets = map[NetworkID]map[string]*record{}
		}
		if b.Boot == nil {
			b.Boot = map[string]bool{}
		}
	}
	return b
}

func (b *Scoreboard) Save() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.dirty {
		return nil
	}
	data, err := json.Marshal(b)
	if err != nil {
		return err
	}
	b.dirty = false
	return os.WriteFile(b.path, data, 0o600)
}

func (b *Scoreboard) Record(net NetworkID, engine string, ok bool, took time.Duration) {
	b.mu.Lock()
	defer b.mu.Unlock()

	byEngine, seen := b.Nets[net]
	if !seen {
		byEngine = map[string]*record{}
		b.Nets[net] = byEngine
	}
	rec, seen := byEngine[engine]
	if !seen {
		rec = &record{}
		byEngine[engine] = rec
	}

	if ok {
		rec.Wins++
		rec.LastGood = time.Now()
		if rec.Latency == 0 {
			rec.Latency = took
		} else {
			// Weighted toward history so one slow night does not bury an
			// engine that is usually the fastest one here.
			rec.Latency = (rec.Latency*3 + took) / 4
		}
	} else {
		rec.Losses++
	}
	b.dirty = true
}

// Order decides who goes first. Anything that worked on this network recently
// leads, sorted by how quickly it came up. Engines with no history here go
// next, because an unknown is worth more than a known failure. Whatever has
// been failing here goes last, but still goes — networks change.
func (b *Scoreboard) Order(net NetworkID, engines []Engine) []Engine {
	b.mu.Lock()
	byEngine := b.Nets[net]
	scores := map[string]*record{}
	for name, rec := range byEngine {
		copied := *rec
		scores[name] = &copied
	}
	b.mu.Unlock()

	type ranked struct {
		engine Engine
		tier   int
		speed  time.Duration
	}

	fresh := time.Now().Add(-72 * time.Hour)
	list := make([]ranked, 0, len(engines))

	for _, e := range engines {
		rec, seen := scores[e.Name()]
		switch {
		case seen && rec.LastGood.After(fresh):
			list = append(list, ranked{e, 0, rec.Latency})
		case !seen:
			list = append(list, ranked{e, 1, 0})
		default:
			list = append(list, ranked{e, 2, 0})
		}
	}

	sort.SliceStable(list, func(i, j int) bool {
		if list[i].tier != list[j].tier {
			return list[i].tier < list[j].tier
		}
		return list[i].speed < list[j].speed
	})

	out := make([]Engine, len(list))
	for i, r := range list {
		out[i] = r.engine
	}
	return out
}

// Bootstrapped reports whether an engine has what it needs to start cold.
func (b *Scoreboard) Bootstrapped(engine string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.Boot[engine]
}

func (b *Scoreboard) MarkBootstrapped(engine string) {
	b.mu.Lock()
	b.Boot[engine] = true
	b.dirty = true
	b.mu.Unlock()
}
