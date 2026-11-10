package split

import (
	"math"
	"testing"
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
