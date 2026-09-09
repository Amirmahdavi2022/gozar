package core

// Where a user ends up when nothing works. Every engine can fail at once —
// during a full shutdown they will — and an app that just says "failed" in
// that moment is useless. The channel is reachable over the same networks
// that stay up when everything else does not, and it is where fresh configs
// and news get posted.
const (
	SupportChannel = "https://t.me/parsv2r"
	SupportHandle  = "@parsv2r"
)
