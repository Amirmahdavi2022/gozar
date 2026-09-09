package core

import (
	"context"
	"time"
)

// Shape is what the traffic looks like from outside. This is the axis that
// matters for surviving DPI: two engines with the same shape fail together,
// so the racer spreads its bets across shapes rather than across brand names.
type Shape string

const (
	ShapeWebRTC Shape = "webrtc" // indistinguishable from a video call
	ShapeHTTPS  Shape = "https"  // WebSocket upgrade over TLS, looks like browsing
	ShapeRandom Shape = "random" // no structure at all, obfs4 style
	ShapeUser   Shape = "user"   // whatever config the user brought
)

// Session is a live way out. It exposes a local SOCKS5 port that the VPN
// service points its tun at.
type Session struct {
	SocksPort int
	StartedAt time.Time
}

// Engine is one way of getting out. Implementations must be safe to Start
// concurrently with other engines: the racer runs several at once and keeps
// whichever wins.
type Engine interface {
	// Name is for logs and nothing else. It is never shown to the user.
	Name() string

	// Shape is what this looks like on the wire.
	Shape() Shape

	// NeedsBootstrap reports whether this engine cannot start cold, because
	// it needs something fetched first (bridge lines, a broker address).
	// Engines that need bootstrap are skipped on a first ever launch and
	// provisioned later through whichever engine did come up.
	NeedsBootstrap() bool

	// Start brings the engine up and returns once it has a local proxy
	// listening. It must respect ctx: the racer cancels the losers the
	// moment a winner is confirmed.
	Start(ctx context.Context) (*Session, error)

	// Stop tears it down. Safe to call more than once.
	Stop()
}
