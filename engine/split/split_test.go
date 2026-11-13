package split

import (
	"math"
	"testing"
	"time"
)

func picks(s *Splitter, n int, v6 bool) map[string]int {
	got := map[string]int{}
	for i := 0; i < n; i++ {
		if p, ok := s.Pick(v6); ok {
			got[p.Name]++
		}
	}
	return got
}

// Until speeds are known, both networks get the same share.
func TestEqualShareAtStart(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true, V6: true}})
	got := picks(&s, 100, false)
	if got["wifi"] != 50 || got["mobile"] != 50 {
		t.Fatalf("%v", got)
	}
}

// New connections follow the speed each network has shown: the phone's numbers, Wi-Fi ~64 Mbps, mobile ~17.
func TestShareFollowsLearnedSpeed(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	wrx, _, _ := s.Counter("wifi")
	mrx, _, _ := s.Counter("mobile")
	for i := 0; i < 10; i++ {
		wrx.Add(8_000_000)
		mrx.Add(2_150_000)
		s.Tick(1)
	}
	got := picks(&s, 1000, false)
	share := float64(got["mobile"]) / 1000
	if math.Abs(share-2.15/10.15) > 0.02 {
		t.Fatalf("mobile share %.3f, want about %.3f (%v)", share, 2.15/10.15, got)
	}
	for _, st := range s.Stats() {
		if st.Name == "wifi" && (st.Cap < 7.9e6 || st.Cap > 8.1e6) {
			t.Fatalf("learned %v", st)
		}
	}
}

// A network that goes quiet is not forgotten at once, but its share shrinks when another one proves faster.
func TestSlowedNetworkLosesShare(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	wrx, _, _ := s.Counter("wifi")
	mrx, _, _ := s.Counter("mobile")
	for i := 0; i < 5; i++ { // both equally fast
		wrx.Add(5_000_000)
		mrx.Add(5_000_000)
		s.Tick(1)
	}
	for i := 0; i < 60; i++ { // mobile drops to a tenth
		wrx.Add(5_000_000)
		mrx.Add(500_000)
		s.Tick(1)
	}
	got := picks(&s, 1000, false)
	if got["mobile"] > 250 {
		t.Fatalf("a much slower mobile line still gets %v", got)
	}
}

// IPv6 addresses go only to networks that have IPv6 (on the test phone: mobile only). None: no network.
func TestIPv6OnlyWhereAvailable(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true, V6: true}})
	got := picks(&s, 50, true)
	if got["mobile"] != 50 || got["wifi"] != 0 {
		t.Fatalf("%v", got)
	}
	s.SetNets([]Net{{Name: "wifi", V4: true}})
	if _, ok := s.Pick(true); ok {
		t.Fatal("an IPv6 address was given to a network without IPv6")
	}
}

// When a network leaves and comes back, the others keep what was learned about them.
func TestSetNetsKeepsLearning(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	wrx, _, _ := s.Counter("wifi")
	wrx.Add(9_000_000)
	s.Tick(1)
	s.SetNets([]Net{{Name: "wifi", V4: true}})
	if p, _ := s.Pick(false); p.Name != "wifi" {
		t.Fatal(p)
	}
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	for _, st := range s.Stats() {
		if st.Name == "wifi" && st.Cap < 8e6 {
			t.Fatalf("Wi-Fi's learned speed was lost: %v", st)
		}
	}
}

// A network that joins next to a fast, busy one gets a fair share of new connections at first.
func TestJoiningNetworkGetsAFairStart(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}})
	wrx, _, _ := s.Counter("wifi")
	for i := 0; i < 10; i++ {
		wrx.Add(12_000_000)
		s.Tick(1)
	}
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	got := picks(&s, 100, false)
	if got["mobile"] < 40 {
		t.Fatalf("mobile got only %d of 100 new connections: %v", got["mobile"], got)
	}
}

// A pause does not erase what was learned: after a minute of nothing, the faster network still gets more.
func TestIdleKeepsLearnedSpeeds(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	wrx, _, _ := s.Counter("wifi")
	mrx, _, _ := s.Counter("mobile")
	for i := 0; i < 10; i++ {
		wrx.Add(8_000_000)
		mrx.Add(2_000_000)
		s.Tick(1)
	}
	for i := 0; i < 60; i++ {
		s.Tick(1) // idle
	}
	got := picks(&s, 1000, false)
	share := float64(got["mobile"]) / 1000
	if share < 0.15 || share > 0.25 {
		t.Fatalf("mobile share after a pause %.2f, want about 0.2: %v", share, got)
	}
}

// A network far slower than the other gets only a trickle of new connections.
func TestVerySlowNetworkGetsATrickle(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	wrx, _, _ := s.Counter("wifi")
	mrx, _, _ := s.Counter("mobile")
	for i := 0; i < 120; i++ {
		wrx.Add(8_000_000)
		mrx.Add(130_000) // about 1 Mbps
		s.Tick(1)
	}
	got := picks(&s, 1000, false)
	if got["mobile"] > 50 || got["mobile"] < 10 {
		t.Fatalf("very slow mobile got %d of 1000 new connections, want a trickle (about 30): %v", got["mobile"], got)
	}
}

// A network that has not carried anything yet gets a fair chance (a fifth), however fast the other one is.
func TestUnmeasuredNetworkIsNotTrickled(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	mrx, _, _ := s.Counter("mobile")
	for i := 0; i < 5; i++ {
		mrx.Add(30_000_000)
		s.Tick(1)
	}
	got := picks(&s, 1000, false)
	if got["wifi"] < 150 {
		t.Fatalf("unmeasured Wi-Fi got only %d of 1000: %v", got["wifi"], got)
	}
}

// A network that takes far longer to answer than the other gets only a trickle at once, before it has carried
// anything: web pages are many small connections and each one would wait for it (seen on a phone: very slow mobile
// data, a website did not open at all while merging).
func TestSlowToAnswerGetsATrickle(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	for i := 0; i < 5; i++ {
		s.Answered("wifi", 30*time.Millisecond)
		s.Answered("mobile", 900*time.Millisecond)
	}
	got := picks(&s, 1000, false)
	if got["mobile"] > 50 {
		t.Fatalf("slow-to-answer mobile got %d of 1000 new connections, want a trickle: %v", got["mobile"], got)
	}
	// both answer about as quickly: no trickle
	var q Splitter
	q.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	for i := 0; i < 5; i++ {
		q.Answered("wifi", 40*time.Millisecond)
		q.Answered("mobile", 70*time.Millisecond)
	}
	if got := picks(&q, 100, false); got["mobile"] < 40 {
		t.Fatalf("mobile answering in 70 ms next to 40 ms was cut: %v", got)
	}
}

// A network that joins next to a busy fast one, then carries almost nothing while its connections stay open, loses
// its share within seconds (it used to keep the fast network's speed forever: below 32 KB/s nothing was learned).
func TestVerySlowBusyNetworkLosesShareQuickly(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}})
	wrx, _, _ := s.Counter("wifi")
	for i := 0; i < 5; i++ {
		wrx.Add(6_000_000)
		s.Tick(1)
	}
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	wrx, _, _ = s.Counter("wifi")
	mrx, _, mact := s.Counter("mobile")
	mact.Add(3) // connections open on mobile
	for i := 0; i < 30; i++ {
		wrx.Add(6_000_000)
		mrx.Add(4_000) // about 32 kbps
		s.Tick(1)
	}
	got := picks(&s, 1000, false)
	if got["mobile"] > 50 {
		t.Fatalf("very slow busy mobile got %d of 1000 after 30 s: %v", got["mobile"], got)
	}
}

// While every network is quiet (open connections, no data), nothing is judged slow.
func TestQuietOpenConnectionsAreNotSlow(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	wrx, _, wact := s.Counter("wifi")
	mrx, _, mact := s.Counter("mobile")
	for i := 0; i < 10; i++ {
		wrx.Add(8_000_000)
		mrx.Add(4_000_000)
		s.Tick(1)
	}
	wact.Add(5)
	mact.Add(5)
	for i := 0; i < 60; i++ {
		s.Tick(1)
	}
	got := picks(&s, 1000, false)
	share := float64(got["mobile"]) / 1000
	if share < 0.28 || share > 0.38 {
		t.Fatalf("mobile share after a quiet minute %.2f, want about 0.33: %v", share, got)
	}
}

// DNS and the like go on the best network first: quick to answer, then fast.
func TestRankedPutsTheQuickNetworkFirst(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "mobile", V4: true}, {Name: "wifi", V4: true}})
	for i := 0; i < 3; i++ {
		s.Answered("wifi", 30*time.Millisecond)
		s.Answered("mobile", 1200*time.Millisecond)
	}
	r := s.Ranked(false)
	if len(r) != 2 || r[0].Name != "wifi" {
		t.Fatalf("ranked %v, want wifi first", r)
	}
}

// The phone's case: Wi-Fi answering its first connections in 0.27 s next to mobile at 0.09 s is not cut (both are
// fine for pages); nor are two answers enough to judge.
func TestModeratelySlowerAnswerIsNotCut(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	for i := 0; i < 5; i++ {
		s.Answered("wifi", 270*time.Millisecond)
		s.Answered("mobile", 88*time.Millisecond)
	}
	if got := picks(&s, 100, false); got["wifi"] < 40 {
		t.Fatalf("Wi-Fi answering in 0.27 s next to 0.09 s was cut: %v", got)
	}
	var f Splitter
	f.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	f.Answered("wifi", 30*time.Millisecond)
	f.Answered("mobile", 2*time.Second)
	if got := picks(&f, 100, false); got["mobile"] < 40 {
		t.Fatalf("one slow answer was enough to cut mobile: %v", got)
	}
}

// A network proven the fastest by what it carried keeps its share even when it is slow to answer.
func TestProvenFastestKeepsShareWhenSlowToAnswer(t *testing.T) {
	var s Splitter
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	wrx, _, _ := s.Counter("wifi")
	mrx, _, _ := s.Counter("mobile")
	for i := 0; i < 10; i++ {
		wrx.Add(8_000_000)
		mrx.Add(2_000_000)
		s.Tick(1)
	}
	for i := 0; i < 5; i++ {
		s.Answered("wifi", 900*time.Millisecond)
		s.Answered("mobile", 50*time.Millisecond)
	}
	if got := picks(&s, 1000, false); got["wifi"] < 600 {
		t.Fatalf("fastest Wi-Fi lost its share for answering slowly: %v", got)
	}
}

// The log is told when a network's treatment changes (learning, full share, trickle), once per change.
func TestNoteTellsTreatmentChanges(t *testing.T) {
	var s Splitter
	var notes []string
	s.Note = func(m string) { notes = append(notes, m) }
	s.SetNets([]Net{{Name: "wifi", V4: true}, {Name: "mobile", V4: true}})
	wrx, _, _ := s.Counter("wifi")
	mrx, _, _ := s.Counter("mobile")
	s.Tick(1)
	if len(notes) != 2 {
		t.Fatalf("first tick: %q", notes)
	}
	for i := 0; i < 30; i++ {
		wrx.Add(8_000_000)
		mrx.Add(100_000)
		s.Tick(1)
	}
	joined := ""
	for _, n := range notes {
		joined += n + "\n"
	}
	if !contains(joined, "wifi: full share") || !contains(joined, "mobile: trickle: much slower") {
		t.Fatalf("notes:\n%s", joined)
	}
	before := len(notes)
	s.Tick(1)
	if len(notes) != before {
		t.Fatalf("a tick without change added notes: %q", notes[before:])
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
