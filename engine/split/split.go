// Package split decides which network each new connection uses. It is the same idea as NetMerge's dispatcher on
// Windows: every network gets new connections in proportion to the speed it has shown, so a faster network carries
// more, and a network that becomes slow gets fewer. Only the standard library is used, so it is tested anywhere.
package split

import (
	"math"
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
	// forget: per second of use, a speed seen once fades (half in about 35 s of traffic) unless seen again. While a
	// network carries (almost) nothing nothing is forgotten: idle says nothing about its speed, and forgetting then
	// made every network look the same after a pause.
	forget  = 0.98
	idleBps = 32 << 10
	// slowPart: a network slower than this part of the fastest gets only a trickle of new connections (1 in
	// trickle), enough to notice when it gets faster. With a full share it only delays downloads: their last pieces
	// wait for it (seen on the phone with mobile data at about 1 Mbps next to 60 Mbps Wi-Fi).
	slowPart = 0.15
	trickle  = 32
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
	known, sum := 0, 0.0
	for _, n := range nets {
		if l, ok := old[n.Name]; ok {
			known++
			sum += l.cap
		}
	}
	for _, n := range nets {
		if l, ok := old[n.Name]; ok {
			l.Net = n
			next = append(next, l)
			continue
		}
		// A network that joins while others are already busy starts as fast as their average: otherwise it would
		// get so few connections next to a fast network that it never shows what it can do. If it is slower, its
		// learned speed falls to what it really carries within about a minute.
		c := float64(startCap)
		if known > 0 && sum/float64(known) > c {
			c = sum / float64(known)
		}
		next = append(next, &line{Net: n, cap: c})
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
		if rate < idleBps {
			continue
		}
		l.cap *= math.Pow(forget, seconds)
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
	total, fastest := 0.0, 0.0
	for _, l := range s.lines {
		if ((v6 && l.V6) || (!v6 && l.V4)) && l.cap > fastest {
			fastest = l.cap
		}
	}
	for _, l := range s.lines {
		if (v6 && !l.V6) || (!v6 && !l.V4) {
			continue
		}
		w := l.weight(fastest)
		l.cur += w
		total += w
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

// measured: a network has carried enough to have shown its speed (before that it is never cut to a trickle).
const measured = 2 << 20

// weight is a network's share of new connections: its learned speed, or a trickle when it has shown that it is far
// slower than the fastest network.
func (l *line) weight(fastest float64) float64 {
	if l.rx.Load() <= measured {
		// not shown its speed yet: at least a fifth of new connections, so it gets the chance
		return math.Max(l.cap, fastest/4)
	}
	if l.cap < slowPart*fastest {
		return fastest / trickle
	}
	return l.cap
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
	total, fastest := 0.0, 0.0
	for _, l := range s.lines {
		if l.cap > fastest {
			fastest = l.cap
		}
	}
	for _, l := range s.lines {
		total += l.weight(fastest)
	}
	out := make([]Stat, 0, len(s.lines))
	for _, l := range s.lines {
		st := Stat{Name: l.Name, Rx: l.rx.Load(), Tx: l.tx.Load(), Active: l.active.Load(), Conns: l.conns.Load(), Cap: l.cap}
		if total > 0 {
			st.Share = l.weight(fastest) / total
		}
		out = append(out, st)
	}
	return out
}
