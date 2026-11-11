// Package dnsblock answers DNS questions for blocked names (ads, trackers, malware) on the phone itself, the way
// NetMerge for Windows blocks them: the app asking gets "no such address" (0.0.0.0 / ::) and never connects.
package dnsblock

import (
	"bufio"
	"encoding/binary"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// never: names that must keep working whatever a list says (connectivity checks, list downloads).
var never = []string{
	"connectivitycheck.gstatic.com", "www.gstatic.com", "clients3.google.com", "android.clients.google.com",
	"raw.githubusercontent.com", "github.com", "cdn.jsdelivr.net",
}

// Blocker holds the blocked names and what was blocked.
type Blocker struct {
	mu      sync.RWMutex
	on      bool
	names   map[string]struct{}
	allow   map[string]struct{}
	blocked atomic.Int64
	asked   atomic.Int64

	recentMu sync.Mutex
	recent   []Recent // newest last, at most maxRecent
}

// Recent is one blocked question.
type Recent struct {
	Name  string `json:"name"`
	Time  int64  `json:"time"` // unix ms
	Count int    `json:"count"`
}

const maxRecent = 100

// New returns a blocker that blocks nothing until lists are loaded and it is switched on.
func New() *Blocker { return &Blocker{names: map[string]struct{}{}, allow: map[string]struct{}{}} }

// Load replaces the blocked names with those read from the lists (one name per line; "#" comments, "0.0.0.0 name"
// hosts lines and "*.name" / "||name^" forms are understood). Returns how many names were loaded.
func (b *Blocker) Load(lists ...io.Reader) int {
	names := make(map[string]struct{}, 100_000)
	for _, r := range lists {
		sc := bufio.NewScanner(r)
		sc.Buffer(make([]byte, 64<<10), 1<<20)
		for sc.Scan() {
			if n := parseLine(sc.Text()); n != "" {
				names[n] = struct{}{}
			}
		}
	}
	for _, n := range never {
		delete(names, n)
	}
	b.mu.Lock()
	b.names = names
	b.mu.Unlock()
	return len(names)
}

func parseLine(s string) string {
	if i := strings.IndexAny(s, "#!"); i >= 0 {
		s = s[:i]
	}
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if f := strings.Fields(s); len(f) >= 2 && (f[0] == "0.0.0.0" || f[0] == "127.0.0.1" || f[0] == "::") {
		s = f[1]
	} else if len(f) != 1 {
		return ""
	}
	s = strings.TrimPrefix(s, "||")
	s = strings.TrimSuffix(s, "^")
	s = strings.TrimPrefix(s, "*.")
	s = strings.TrimSuffix(strings.ToLower(s), ".")
	if s == "" || s == "localhost" || strings.ContainsAny(s, "/:*@ ") || !strings.Contains(s, ".") {
		return ""
	}
	return s
}

// SetOn switches blocking on or off (the lists stay loaded).
func (b *Blocker) SetOn(on bool) { b.mu.Lock(); b.on = on; b.mu.Unlock() }

// SetAllow replaces the names that are never blocked (and their subdomains).
func (b *Blocker) SetAllow(names []string) {
	m := map[string]struct{}{}
	for _, n := range names {
		if n = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(n)), "."); n != "" {
			m[n] = struct{}{}
		}
	}
	b.mu.Lock()
	b.allow = m
	b.mu.Unlock()
}

// Count: names loaded.
func (b *Blocker) Count() int { b.mu.RLock(); defer b.mu.RUnlock(); return len(b.names) }

// Stats: questions seen and blocked since start.
func (b *Blocker) Stats() (asked, blocked int64) { return b.asked.Load(), b.blocked.Load() }

// Recent returns the most recently blocked names, newest first.
func (b *Blocker) Recent() []Recent {
	b.recentMu.Lock()
	defer b.recentMu.Unlock()
	out := make([]Recent, len(b.recent))
	for i, r := range b.recent {
		out[len(b.recent)-1-i] = r
	}
	return out
}

// Blocks reports whether a name (or a domain it belongs to) is blocked.
func (b *Blocker) Blocks(name string) bool {
	name = strings.TrimSuffix(strings.ToLower(name), ".")
	b.mu.RLock()
	defer b.mu.RUnlock()
	if !b.on || len(b.names) == 0 {
		return false
	}
	for n := name; ; {
		if _, ok := b.allow[n]; ok {
			return false
		}
		i := strings.IndexByte(n, '.')
		if i < 0 {
			break
		}
		n = n[i+1:]
	}
	for n := name; ; {
		if _, ok := b.names[n]; ok {
			return true
		}
		i := strings.IndexByte(n, '.')
		if i < 0 {
			return false
		}
		n = n[i+1:]
	}
}

// Answer looks at one DNS packet from an app. When it asks for a blocked name it returns the answer to send back
// (0.0.0.0 for an IPv4 address, :: for IPv6, an empty answer for anything else) and true; otherwise nil, false and
// the packet goes on to the real DNS server.
func (b *Blocker) Answer(q []byte) ([]byte, bool) {
	name, qtype, end, ok := question(q)
	if !ok {
		return nil, false
	}
	b.asked.Add(1)
	if !b.Blocks(name) {
		return nil, false
	}
	b.blocked.Add(1)
	b.remember(name)
	return reply(q[:end], qtype), true
}

func (b *Blocker) remember(name string) {
	b.recentMu.Lock()
	defer b.recentMu.Unlock()
	now := time.Now().UnixMilli()
	for i := range b.recent {
		if b.recent[i].Name == name {
			r := b.recent[i]
			r.Count++
			r.Time = now
			b.recent = append(append(b.recent[:i:i], b.recent[i+1:]...), r)
			return
		}
	}
	b.recent = append(b.recent, Recent{Name: name, Time: now, Count: 1})
	if len(b.recent) > maxRecent {
		b.recent = b.recent[len(b.recent)-maxRecent:]
	}
}

// question reads the first question of a DNS query: its name, type and where the question ends.
func question(p []byte) (name string, qtype uint16, end int, ok bool) {
	if len(p) < 12 || p[2]&0x80 != 0 || binary.BigEndian.Uint16(p[4:6]) != 1 {
		return "", 0, 0, false // not a query with one question
	}
	var sb strings.Builder
	i := 12
	for {
		if i >= len(p) {
			return "", 0, 0, false
		}
		l := int(p[i])
		i++
		if l == 0 {
			break
		}
		if l&0xC0 != 0 || i+l > len(p) || sb.Len()+l > 253 {
			return "", 0, 0, false
		}
		if sb.Len() > 0 {
			sb.WriteByte('.')
		}
		sb.Write(p[i : i+l])
		i += l
	}
	if i+4 > len(p) {
		return "", 0, 0, false
	}
	return sb.String(), binary.BigEndian.Uint16(p[i : i+2]), i + 4, true
}

// reply builds the answer to a blocked question (head: the query up to the end of its question).
func reply(head []byte, qtype uint16) []byte {
	r := make([]byte, len(head), len(head)+28)
	copy(r, head)
	r[2] = 0x80 | (head[2] & 0x01)         // response; keep "recursion desired"
	r[3] = 0x80                            // recursion available, no error
	binary.BigEndian.PutUint16(r[6:8], 0)  // answers
	binary.BigEndian.PutUint16(r[8:10], 0) // authority
	binary.BigEndian.PutUint16(r[10:12], 0)
	var rdata []byte
	switch qtype {
	case 1: // A
		rdata = make([]byte, 4)
	case 28: // AAAA
		rdata = make([]byte, 16)
	default:
		return r
	}
	binary.BigEndian.PutUint16(r[6:8], 1)
	r = append(r, 0xC0, 12) // the name: pointer to the question
	r = binary.BigEndian.AppendUint16(r, qtype)
	r = binary.BigEndian.AppendUint16(r, 1)  // class IN
	r = binary.BigEndian.AppendUint32(r, 10) // TTL: 10 s, so allowing a name or turning blocking off takes effect soon
	r = binary.BigEndian.AppendUint16(r, uint16(len(rdata)))
	return append(r, rdata...)
}
