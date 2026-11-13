// Package mobile is what the Android app calls (built with gomobile bind).
package mobile

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"

	"github.com/nisar87/netmerge-android/engine/dnsblock"
	"github.com/nisar87/netmerge-android/engine/events"

	"github.com/nisar87/netmerge-android/engine/split"
	"github.com/nisar87/netmerge-android/engine/tun"
)

var (
	mu  sync.Mutex
	eng *tun.Engine
	// block: the ad and tracker block list; loaded once, kept across starts
	block = dnsblock.New()
)

// Start runs the engine on the VPN's file descriptor (detached from Java, the engine closes it). netsJSON is the
// list of networks: [{"name":"wifi","handle":123,"v4":true,"v6":false}, ...].
func Start(fd int, mtu int, netsJSON string) error {
	var nets []split.Net
	if err := json.Unmarshal([]byte(netsJSON), &nets); err != nil {
		events.Addf("engine: bad network list: %v", err)
		return err
	}
	mu.Lock()
	defer mu.Unlock()
	if eng != nil {
		events.Addf("engine: asked to run while it already runs")
		return errors.New("already running")
	}
	e, err := tun.StartWith(fd, mtu, nets, block)
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
		events.Addf("engine: bad network list: %v", err)
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
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	// open/goroutines/heap: watched by the long-run test (they must not keep growing)
	b, _ := json.Marshal(map[string]any{"nets": e.Split.Stats(), "fails": e.Fails(), "drops": e.Drops(),
		"open": e.Open(), "goroutines": runtime.NumGoroutine(), "heapKB": m.HeapAlloc >> 10})
	return string(b)
}

// LoadBlocklists reads the block lists (files, one path per line) and returns how many names they hold.
func LoadBlocklists(paths string) int {
	var rs []io.Reader
	for _, p := range strings.Split(paths, "\n") {
		if p = strings.TrimSpace(p); p == "" {
			continue
		}
		f, err := os.Open(p)
		if err != nil {
			events.Addf("blocking: a list could not be read: %v", err)
			continue
		}
		defer f.Close()
		rs = append(rs, f)
	}
	return block.Load(rs...)
}

// SetBlocking switches ad blocking on or off.
func SetBlocking(on bool) { block.SetOn(on) }

// SetAllowed replaces the names never blocked (one per line).
func SetAllowed(names string) { block.SetAllow(strings.Split(names, "\n")) }

// BlockStats returns the blocking counters and the recently blocked names as JSON.
func BlockStats() string {
	asked, blocked := block.Stats()
	b, _ := json.Marshal(map[string]any{"names": block.Count(), "asked": asked, "blocked": blocked, "recent": block.Recent()})
	return string(b)
}

// VirtualDNS returns the VPN's own DNS addresses (IPv4 and IPv6, one per line) for the app's VPN settings.
func VirtualDNS() string { return tun.VirtualDNS4.String() + "\n" + tun.VirtualDNS6.String() }

// Events returns the engine's log lines since the last call (one per line; "" when none). The app writes them to its
// own log every 2 s.
func Events() string { return events.Drain() }

// SetCrashFile names a file for the details of a crash in the engine (a Go crash ends the app without a Java stack):
// the app sends what it finds there at the next start. Called once at start.
func SetCrashFile(path string) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		events.Addf("engine: crash file: %v", err)
		return
	}
	if err := debug.SetCrashOutput(f, debug.CrashOptions{}); err != nil {
		events.Addf("engine: crash output: %v", err)
	}
	f.Close() // SetCrashOutput keeps its own copy
}
