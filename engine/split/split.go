// Package split decides which network each new connection uses. It is the same idea as NetMerge's dispatcher on
// Windows: every network gets new connections in proportion to the speed it has shown, so a faster network carries
// more, and a network that becomes slow gets fewer. Only the standard library is used, so it is tested anywhere.
package split

import (
	"sync"
	"sync/atomic"
)

// Net is one network the phone can use (Wi-Fi, mobile data).
type Net struct {
	Name   string `json:"name"`
	Handle int64  `json:"handle"` // Android's network handle, used to bind a socket to this network
	V4     bool   `json:"v4"`
	V6     bool   `json:"v6"`
}

// Stat is what the app shows and the tests check.
type Stat struct {
	Name   string  `json:"name"`
	Rx     int64   `json:"rx"`
	Tx     int64   `json:"tx"`
	Active int64   `json:"active"`
	Conns  int64   `json:"conns"` // connections given to this network so far
	Cap    float64 `json:"cap"`   // learned speed, bytes per second
	Share  float64 `json:"share"` // share of new connections it gets now
}

type line struct {
	Net
	rx, tx, active, conns atomic.Int64
	cap                   float64 // learned capacity (bytes/s): highest recent rate, slowly forgotten
	lastRx                int64
	cur                   float64 // smooth weighted round robin state
}

// Splitter hands out networks to new connections.
type Splitter struct {
	mu    sync.Mutex
	lines []*line
}

const (
	startCap = 1 << 20 // assume 1 MB/s until a network shows what it can do
	minCap   = 64 << 10
	forget   = 0.97 // per second: a speed seen once is forgotten in about a minute unless seen again
)

// SetNets replaces the list of networks, keeping what was learned about networks that stay.
func (s *Splitter) SetNets(nets []Net) {
	s.mu.Lock()
	defer s.mu.Unlock()
	old := map[string]*line{}
	for _, l := range s.lines {
		old[l.Name] = l
	}
	var next []*line
	for _, n := range nets {
		if l, ok := old[n.Name]; ok {
			l.Net = n
			next = append(next, l)
			continue
		}
		next = append(next, &line{Net: n, cap: startCap})
	}
	s.lines = next
}

// Tick is called once a second with the time since the last call: it learns each network's speed.
func (s *Splitter) Tick(seconds float64) {
	if seconds <= 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, l := range s.lines {
		rx := l.rx.Load()
		rate := float64(rx-l.lastRx) / seconds
		l.lastRx = rx
		l.cap *= forget
		if rate > l.cap {
			l.cap = rate
		}
		if l.cap < minCap {
			l.cap = minCap
		}
	}
}

// Pick chooses the network for a new connection to an IPv6 (v6) or IPv4 address. ok is false when no network can
// reach that kind of address (an app then falls back to the other kind).
func (s *Splitter) Pick(v6 bool) (n Net, ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var best *line
	total := 0.0
	for _, l := range s.lines {
		if (v6 && !l.V6) || (!v6 && !l.V4) {
			continue
		}
		l.cur += l.cap
		total += l.cap
		if best == nil || l.cur > best.cur {
			best = l
		}
	}
	if best == nil {
		return Net{}, false
	}
	best.cur -= total
	best.conns.Add(1)
	return best.Net, true
}

// Counter returns the byte and connection counters of a network, for the connection that was given to it.
func (s *Splitter) Counter(name string) (rx, tx, active *atomic.Int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, l := range s.lines {
		if l.Name == name {
			return &l.rx, &l.tx, &l.active
		}
	}
	var a, b, c atomic.Int64 // a network that left meanwhile: count into nothing
	return &a, &b, &c
}

// Stats reports every network.
func (s *Splitter) Stats() []Stat {
	s.mu.Lock()
	defer s.mu.Unlock()
	total := 0.0
	for _, l := range s.lines {
		total += l.cap
	}
	out := make([]Stat, 0, len(s.lines))
	for _, l := range s.lines {
		st := Stat{Name: l.Name, Rx: l.rx.Load(), Tx: l.tx.Load(), Active: l.active.Load(), Conns: l.conns.Load(), Cap: l.cap}
		if total > 0 {
			st.Share = l.cap / total
		}
		out = append(out, st)
	}
	return out
}
