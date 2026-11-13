// Package events collects what the engine has to say for the app's log (files/events.log): the app takes the lines
// every 2 s (mobile.Events). Problems that can happen many times a second (connections that fail) are counted and
// written once a minute, so the log stays small. Never written: addresses or names of sites.
package events

import (
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"
)

const keep = 400 // lines kept while the app does not take them

var (
	mu     sync.Mutex
	lines  []string
	lost   int
	counts = map[string]int{}
)

// Addf adds a line.
func Addf(format string, args ...any) {
	s := fmt.Sprintf(format, args...)
	mu.Lock()
	defer mu.Unlock()
	if len(lines) >= keep {
		lines = lines[1:]
		lost++
	}
	lines = append(lines, s)
}

// Count counts one more of something frequent (written by Flush, with how many).
func Count(key string) {
	mu.Lock()
	counts[key]++
	mu.Unlock()
}

// Flush writes the counts gathered since the last flush as one line (called once a minute).
func Flush(title string) {
	mu.Lock()
	if len(counts) == 0 {
		mu.Unlock()
		return
	}
	parts := make([]string, 0, len(counts))
	for k, n := range counts {
		parts = append(parts, fmt.Sprintf("%s=%d", k, n))
	}
	counts = map[string]int{}
	mu.Unlock()
	sort.Strings(parts)
	Addf("%s: %s", title, strings.Join(parts, " "))
}

// Drain returns the lines not taken yet (one per line) and forgets them.
func Drain() string {
	mu.Lock()
	defer mu.Unlock()
	if len(lines) == 0 && lost == 0 {
		return ""
	}
	out := strings.Join(lines, "\n")
	if lost > 0 {
		out = fmt.Sprintf("(%d older engine lines lost)\n", lost) + out
	}
	lines, lost = nil, 0
	return out
}

// Since says how long ago t was, for lines like "took 3.2 s".
func Since(t time.Time) string { return fmt.Sprintf("%.1f s", time.Since(t).Seconds()) }
