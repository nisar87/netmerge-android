// Package mobile is what the Android app calls (built with gomobile bind).
package mobile

import (
	"encoding/json"
	"errors"
	"sync"

	"github.com/nisar87/netmerge-android/engine/split"
	"github.com/nisar87/netmerge-android/engine/tun"
)

var (
	mu  sync.Mutex
	eng *tun.Engine
)

// Start runs the engine on the VPN's file descriptor (detached from Java, the engine closes it). netsJSON is the
// list of networks: [{"name":"wifi","handle":123,"v4":true,"v6":false}, ...].
func Start(fd int, mtu int, netsJSON string) error {
	var nets []split.Net
	if err := json.Unmarshal([]byte(netsJSON), &nets); err != nil {
		return err
	}
	mu.Lock()
	defer mu.Unlock()
	if eng != nil {
		return errors.New("already running")
	}
	e, err := tun.Start(fd, mtu, nets)
	if err != nil {
		return err
	}
	eng = e
	return nil
}

// SetNetworks updates the networks (one came, one left, an address changed).
func SetNetworks(netsJSON string) error {
	var nets []split.Net
	if err := json.Unmarshal([]byte(netsJSON), &nets); err != nil {
		return err
	}
	mu.Lock()
	defer mu.Unlock()
	if eng != nil {
		eng.SetNets(nets)
	}
	return nil
}

// Stop ends the engine.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if eng != nil {
		eng.Stop()
		eng = nil
	}
}

// Stats returns the networks' counters as JSON ("" when not running).
func Stats() string {
	mu.Lock()
	e := eng
	mu.Unlock()
	if e == nil {
		return ""
	}
	b, _ := json.Marshal(map[string]any{"nets": e.Split.Stats(), "fails": e.Fails(), "drops": e.Drops()})
	return string(b)
}
