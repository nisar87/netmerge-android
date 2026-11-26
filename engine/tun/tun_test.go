package tun

import (
	"net"
	"strings"
	"testing"

	"github.com/nisar87/netmerge-android/engine/events"
	"github.com/nisar87/netmerge-android/engine/split"
)

// Networks often change several times within a second (Wi-Fi lost, back, checked). A connection on a network that
// left is closed and counted once, however many changes follow before its relay ends.
func TestSetNetsClosesEachConnectionOnce(t *testing.T) {
	e := &Engine{conns: map[net.Conn]int64{}}
	wifi, wifiPeer := net.Pipe()
	defer wifiPeer.Close()
	mobile, mobilePeer := net.Pipe()
	defer mobilePeer.Close()
	defer mobile.Close()
	e.track(wifi, 1)
	e.track(mobile, 2)

	onlyMobile := []split.Net{{Name: "mobile", Handle: 2, V4: true}}
	for i := 0; i < 4; i++ {
		e.SetNets(onlyMobile)
	}
	e.SetNets([]split.Net{{Name: "wifi", Handle: 3, V4: true}, {Name: "mobile", Handle: 2, V4: true}})
	if got := e.Drops(); got != 1 {
		t.Fatalf("drops = %d, want 1 (the Wi-Fi connection, counted once)", got)
	}
	if got := e.Open(); got != 1 {
		t.Fatalf("open = %d, want 1 (the mobile connection)", got)
	}
	if _, err := wifi.Write([]byte("x")); err == nil {
		t.Fatal("the Wi-Fi connection was not closed")
	}
	e.untrack(wifi) // its relay ends later: nothing changes
	if got := e.Open(); got != 1 {
		t.Fatalf("open after the relay ended = %d, want 1", got)
	}
}

// The same networks told again (Android does that several times within a second) are logged once.
func TestSameNetworksAreLoggedOnce(t *testing.T) {
	e := &Engine{conns: map[net.Conn]int64{}}
	events.Drain()
	both := []split.Net{{Name: "wifi", Handle: 1, V4: true, V6: true}, {Name: "mobile", Handle: 2, V4: true}}
	for i := 0; i < 5; i++ {
		e.SetNets(both)
	}
	e.SetNets(both[1:])
	e.SetNets(both[1:])
	e.SetNets(both)
	log := events.Drain()
	if n := strings.Count(log, "networks now"); n != 3 {
		t.Fatalf("%d lines, want 3 (both, mobile, both):\n%s", n, log)
	}
}
