package core

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"time"

	"golang.org/x/net/proxy"
)

// Prober answers one question: does traffic actually come back through this
// tunnel. Everything in the racer hangs off it, so it is deliberately dull.
type Prober interface {
	Through(ctx context.Context, socksPort int) error
}

// HTTPProbe fetches a tiny endpoint through the tunnel's SOCKS port.
//
// The target matters more than it looks. It has to be small, reachable
// worldwide, and boring enough that hitting it repeatedly is unremarkable.
// Cloudflare and Google both publish endpoints that answer in a few bytes.
type HTTPProbe struct {
	Targets []string
	Timeout time.Duration
}

func DefaultProbe() *HTTPProbe {
	return &HTTPProbe{
		Targets: []string{
			"http://cp.cloudflare.com/generate_204",
			"http://connectivitycheck.gstatic.com/generate_204",
		},
		Timeout: 8 * time.Second,
	}
}

func (p *HTTPProbe) Through(ctx context.Context, socksPort int) error {
	dialer, err := proxy.SOCKS5("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort), nil, proxy.Direct)
	if err != nil {
		return err
	}

	client := &http.Client{
		Timeout: p.Timeout,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return dialer.Dial(network, addr)
			},
			DisableKeepAlives: true,
		},
	}

	var last error
	for _, target := range p.Targets {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
		if err != nil {
			last = err
			continue
		}
		resp, err := client.Do(req)
		if err != nil {
			last = err
			continue
		}
		resp.Body.Close()
		if resp.StatusCode < 500 {
			return nil
		}
		last = fmt.Errorf("probe got %d", resp.StatusCode)
	}
	if last == nil {
		last = fmt.Errorf("no probe target reachable")
	}
	return last
}
