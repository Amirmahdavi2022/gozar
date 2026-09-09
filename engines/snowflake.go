package engines

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"

	sf "gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2/client/lib"
)

// Snowflake carries traffic over WebRTC to a volunteer browser somewhere
// outside the censored network. On the wire it is a video call, which is the
// whole point: blocking it means blocking video calls.
//
// The transport itself only gives us a net.Conn to a Tor bridge, so this wraps
// it in a small SOCKS5 listener. Tor is then pointed at that port and its own
// SOCKS port is what the tunnel actually uses.
type Snowflake struct {
	BrokerURL    string
	FrontDomains []string
	ICEAddresses []string
	Fingerprint  string

	mu       sync.Mutex
	listener net.Listener
	tr       *sf.Transport
	conns    map[net.Conn]struct{}
}

func NewSnowflake() *Snowflake {
	return &Snowflake{
		// Tor's own broker. driftkite will pass its own address here later,
		// which is the only difference between the two engines.
		BrokerURL:    "https://1098762253.rsc.cdn77.org/",
		FrontDomains: []string{"www.phpmyadmin.net"},
		ICEAddresses: []string{
			"stun:stun.l.google.com:19302",
			"stun:stun.antisip.com:3478",
			"stun:stun.dus.net:3478",
			"stun:stun.epygi.com:3478",
			"stun:stun.sonetel.net:3478",
			"stun:stun.voipgate.com:3478",
			"stun:stun.voys.nl:3478",
		},
		Fingerprint: "2B280B23E1107BB62ABFC40DDCC8824814F80A72",
		conns:       map[net.Conn]struct{}{},
	}
}

func (s *Snowflake) Name() string { return "snowflake" }

// Listen brings up the local SOCKS5 port and returns it.
func (s *Snowflake) Listen(ctx context.Context) (int, error) {
	tr, err := sf.NewSnowflakeClient(sf.ClientConfig{
		BrokerURL:         s.BrokerURL,
		FrontDomains:      s.FrontDomains,
		ICEAddresses:      s.ICEAddresses,
		BridgeFingerprint: s.Fingerprint,
		Max:               3, // hold three volunteers, so one closing a tab is survivable
	})
	if err != nil {
		return 0, err
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}

	s.mu.Lock()
	s.tr = tr
	s.listener = ln
	s.mu.Unlock()

	go s.serve(ctx, ln)

	return ln.Addr().(*net.TCPAddr).Port, nil
}

func (s *Snowflake) serve(ctx context.Context, ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer conn.Close()
			if err := s.handle(ctx, conn); err != nil && !errors.Is(err, io.EOF) {
				return
			}
		}()
	}
}

// handle speaks just enough SOCKS5 to be useful: no auth, CONNECT only. Every
// accepted connection gets its own snowflake, and the destination in the
// request is ignored because the far end of a snowflake is always the bridge.
func (s *Snowflake) handle(ctx context.Context, client net.Conn) error {
	if err := socksHandshake(client); err != nil {
		return err
	}

	s.mu.Lock()
	tr := s.tr
	s.mu.Unlock()
	if tr == nil {
		return errors.New("snowflake not started")
	}

	remote, err := tr.Dial()
	if err != nil {
		_ = socksReply(client, 0x01) // general failure
		return err
	}
	defer remote.Close()

	s.track(remote)
	defer s.untrack(remote)

	if err := socksReply(client, 0x00); err != nil {
		return err
	}

	done := make(chan struct{}, 2)
	go func() { io.Copy(remote, client); done <- struct{}{} }()
	go func() { io.Copy(client, remote); done <- struct{}{} }()

	select {
	case <-done:
	case <-ctx.Done():
	}
	return nil
}

func (s *Snowflake) track(c net.Conn) {
	s.mu.Lock()
	s.conns[c] = struct{}{}
	s.mu.Unlock()
}

func (s *Snowflake) untrack(c net.Conn) {
	s.mu.Lock()
	delete(s.conns, c)
	s.mu.Unlock()
}

func (s *Snowflake) Stop() {
	s.mu.Lock()
	ln := s.listener
	conns := make([]net.Conn, 0, len(s.conns))
	for c := range s.conns {
		conns = append(conns, c)
	}
	s.listener = nil
	s.conns = map[net.Conn]struct{}{}
	s.mu.Unlock()

	if ln != nil {
		ln.Close()
	}
	for _, c := range conns {
		c.Close()
	}
}

// --- minimal SOCKS5, server side ---

func socksHandshake(c net.Conn) error {
	head := make([]byte, 2)
	if _, err := io.ReadFull(c, head); err != nil {
		return err
	}
	if head[0] != 0x05 {
		return fmt.Errorf("not socks5")
	}
	methods := make([]byte, int(head[1]))
	if _, err := io.ReadFull(c, methods); err != nil {
		return err
	}
	// No authentication.
	if _, err := c.Write([]byte{0x05, 0x00}); err != nil {
		return err
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(c, req); err != nil {
		return err
	}
	if req[1] != 0x01 {
		_ = socksReply(c, 0x07) // command not supported
		return fmt.Errorf("only connect is supported")
	}

	switch req[3] {
	case 0x01: // ipv4
		if _, err := io.ReadFull(c, make([]byte, 4)); err != nil {
			return err
		}
	case 0x03: // domain
		n := make([]byte, 1)
		if _, err := io.ReadFull(c, n); err != nil {
			return err
		}
		if _, err := io.ReadFull(c, make([]byte, int(n[0]))); err != nil {
			return err
		}
	case 0x04: // ipv6
		if _, err := io.ReadFull(c, make([]byte, 16)); err != nil {
			return err
		}
	default:
		_ = socksReply(c, 0x08)
		return fmt.Errorf("bad address type")
	}

	port := make([]byte, 2)
	if _, err := io.ReadFull(c, port); err != nil {
		return err
	}
	_ = binary.BigEndian.Uint16(port)
	return nil
}

func socksReply(c net.Conn, status byte) error {
	_, err := c.Write([]byte{0x05, status, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	return err
}
