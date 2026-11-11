package dnsblock

import (
	"encoding/binary"
	"net"
	"strings"
	"testing"
)

func query(name string, qtype uint16) []byte {
	q := []byte{0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0}
	for _, l := range strings.Split(name, ".") {
		q = append(q, byte(len(l)))
		q = append(q, l...)
	}
	q = append(q, 0)
	q = binary.BigEndian.AppendUint16(q, qtype)
	return binary.BigEndian.AppendUint16(q, 1)
}

func blocker(list string) *Blocker {
	b := New()
	b.Load(strings.NewReader(list))
	b.SetOn(true)
	return b
}

func TestListFormats(t *testing.T) {
	b := blocker("# comment\nads.example.com\n0.0.0.0 tracker.net\n||banner.org^\n*.wild.io\nBad Line here\nlocalhost\n")
	if b.Count() != 4 {
		t.Fatalf("loaded %d names, want 4", b.Count())
	}
	for _, n := range []string{"ads.example.com", "x.ads.example.com", "tracker.net", "banner.org", "a.b.wild.io", "WILD.IO."} {
		if !b.Blocks(n) {
			t.Errorf("%s not blocked", n)
		}
	}
	for _, n := range []string{"example.com", "www.example.com", "net", "goodtracker.net"} {
		if b.Blocks(n) {
			t.Errorf("%s blocked", n)
		}
	}
}

func TestNeverBlockedAndAllow(t *testing.T) {
	b := blocker("connectivitycheck.gstatic.com\nads.example.com\nexample.org\n")
	if b.Blocks("connectivitycheck.gstatic.com") {
		t.Error("a connectivity check must never be blocked")
	}
	b.SetAllow([]string{"good.example.org"})
	if b.Blocks("good.example.org") || b.Blocks("cdn.good.example.org") {
		t.Error("allowed name blocked")
	}
	if !b.Blocks("other.example.org") {
		t.Error("allow went too far")
	}
	b.SetOn(false)
	if b.Blocks("ads.example.com") {
		t.Error("blocked while off")
	}
}

func TestAnswers(t *testing.T) {
	b := blocker("ads.example.com\n")
	if _, ok := b.Answer(query("www.example.com", 1)); ok {
		t.Fatal("answered a name that is not blocked")
	}
	for _, c := range []struct {
		qtype uint16
		ip    net.IP
	}{{1, net.IPv4zero.To4()}, {28, net.IPv6zero}, {65, nil}} {
		q := query("x.ads.example.com", c.qtype)
		r, ok := b.Answer(q)
		if !ok {
			t.Fatalf("type %d not answered", c.qtype)
		}
		if r[0] != 0x12 || r[1] != 0x34 || r[2]&0x80 == 0 || r[3]&0x0F != 0 {
			t.Fatalf("type %d: bad header % x", c.qtype, r[:4])
		}
		an := binary.BigEndian.Uint16(r[6:8])
		if c.ip == nil {
			if an != 0 {
				t.Fatalf("type %d: %d answers, want 0", c.qtype, an)
			}
			continue
		}
		if an != 1 || !net.IP(r[len(r)-len(c.ip):]).Equal(c.ip) {
			t.Fatalf("type %d: answer % x", c.qtype, r[len(q):])
		}
	}
	asked, blocked := b.Stats()
	if asked != 4 || blocked != 3 {
		t.Fatalf("stats %d asked %d blocked", asked, blocked)
	}
	if rec := b.Recent(); len(rec) != 1 || rec[0].Name != "x.ads.example.com" || rec[0].Count != 3 {
		t.Fatalf("recent %+v", rec)
	}
}

func TestBadPackets(t *testing.T) {
	b := blocker("ads.example.com\n")
	for _, p := range [][]byte{nil, make([]byte, 11), append(query("ads.example.com", 1)[:12], 63)} {
		if _, ok := b.Answer(p); ok {
			t.Fatal("answered a broken packet")
		}
	}
	r := query("ads.example.com", 1)
	r[2] |= 0x80 // a response, not a question
	if _, ok := b.Answer(r); ok {
		t.Fatal("answered a response")
	}
}
