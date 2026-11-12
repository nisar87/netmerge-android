// Package tun takes the phone's traffic from the VPN, and sends every connection out on the network the splitter
// picks, bound to that network.
package tun

import (
	"io"
	"log"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"github.com/xjasonlyu/tun2socks/v2/core/option"
	"gvisor.dev/gvisor/pkg/tcpip/stack"

	"github.com/nisar87/netmerge-android/engine/dnsblock"
	"github.com/nisar87/netmerge-android/engine/split"
)

// The VPN's own DNS server addresses (what Android gives apps as their DNS). They exist only inside the engine:
// questions to them are answered by the block list or sent on to a real DNS server. Being inside the VPN, they do
// not answer encrypted DNS (port 853), so Android's "Private DNS: automatic" falls back to plain DNS, which the
// block list can see.
var (
	VirtualDNS4 = net.IPv4(10, 111, 0, 53).To4()
	VirtualDNS6 = net.ParseIP("fd6e:6d65:7267::53")
	upstreamDNS = []net.IP{net.IPv4(1, 1, 1, 1).To4(), net.IPv4(8, 8, 8, 8).To4()}
)

func isVirtualDNS(ip net.IP) bool { return ip.Equal(VirtualDNS4) || ip.Equal(VirtualDNS6) }

// Engine is one running VPN.
type Engine struct {
	Split split.Splitter
	stack *stack.Stack
	dev   interface{ Close() }
	stop  chan struct{}
	fd    int // the VPN's file descriptor (see closeTun)
	once  sync.Once
	fails atomic.Int64

	// Block answers DNS questions for blocked names (ads, trackers); always set, off until switched on.
	Block *dnsblock.Blocker
	dnsN  atomic.Uint32 // which upstream DNS server next

	connMu sync.Mutex
	conns  map[net.Conn]int64 // open outgoing connections and the network handle each is bound to
	drops  atomic.Int64
}

// Start runs the engine on the VPN's file descriptor.
func Start(fd int, mtu int, nets []split.Net) (*Engine, error) {
	return StartWith(fd, mtu, nets, dnsblock.New())
}

// StartWith is Start with a block list that outlives one engine (the list is loaded once, not at every start).
func StartWith(fd int, mtu int, nets []split.Net, block *dnsblock.Blocker) (*Engine, error) {
	e := &Engine{stop: make(chan struct{}), conns: map[net.Conn]int64{}, Block: block, fd: fd}
	e.Split.SetNets(nets)
	dev, err := fdbased.Open(strconv.Itoa(fd), uint32(mtu), 0)
	if err != nil {
		return nil, err
	}
	st, err := core.CreateStack(&core.Config{LinkEndpoint: dev, TransportHandler: e, Options: []option.Option{
		// What an app sends (uploads) waits in the engine's own TCP stack until it goes out. 1 MB per connection
		// (the default) let an app "send" many MB in the first second while nothing had left the phone yet: a
		// speed test showed a big false spike. 256 KB is still plenty on the phone's own (fast) path to the engine.
		option.WithTCPReceiveBufferSize(appSendBuffer),
	}})
	if err != nil {
		dev.Close()
		return nil, err
	}
	e.stack, e.dev = st, dev
	go e.learn()
	return e, nil
}

// Stop ends the engine and closes the VPN.
func (e *Engine) Stop() {
	e.once.Do(func() {
		close(e.stop)
		if e.stack != nil {
			// Destroy, not only Close: it also removes the NIC, which stops the reader that waits on the VPN's file
			// descriptor. A reader left waiting keeps the VPN open after the descriptor is closed (the kernel keeps
			// the file while a poll waits on it).
			e.stack.Destroy()
		}
		if e.dev != nil {
			e.dev.Close()
		}
		closeTun(e.fd)
	})
}

// closeTun closes the VPN's file descriptor if it is still open. The device's Close leaves it open, and Android keeps
// the VPN up for as long as it is open: when the app paused merging (battery) but stayed running, every app's traffic
// went into a VPN that no engine read any more (seen on the emulator: the VPN was still up 20 s later). The number is
// closed only while it still is the tun device (/dev/net/tun, char 10:200), never a file that reused the number.
func closeTun(fd int) {
	var st syscall.Stat_t
	if fd < 0 || syscall.Fstat(fd, &st) != nil {
		return
	}
	if st.Mode&syscall.S_IFMT == syscall.S_IFCHR && uint64(st.Rdev) == 10<<8|200 {
		syscall.Close(fd)
	}
}

// SetNets updates the networks. Connections on a network that left (or came back as a new network, with a new
// handle) are closed at once: their packets can no longer go anywhere, and without this an app waits a minute or more
// for a dead connection before it opens a new one (which then goes to a network that works).
func (e *Engine) SetNets(nets []split.Net) {
	e.Split.SetNets(nets)
	live := map[int64]bool{}
	for _, n := range nets {
		live[n.Handle] = true
	}
	e.connMu.Lock()
	var dead []net.Conn
	for c, h := range e.conns {
		if !live[h] {
			dead = append(dead, c)
		}
	}
	e.connMu.Unlock()
	for _, c := range dead {
		_ = c.Close()
	}
	e.drops.Add(int64(len(dead)))
}

// Open: outgoing connections open now.
func (e *Engine) Open() int {
	e.connMu.Lock()
	defer e.connMu.Unlock()
	return len(e.conns)
}

// Drops: connections closed because their network left.
func (e *Engine) Drops() int64 { return e.drops.Load() }

func (e *Engine) track(c net.Conn, handle int64) {
	e.connMu.Lock()
	e.conns[c] = handle
	e.connMu.Unlock()
}

func (e *Engine) untrack(c net.Conn) {
	e.connMu.Lock()
	delete(e.conns, c)
	e.connMu.Unlock()
}

// Fails: connections that could not be opened on any network.
func (e *Engine) Fails() int64 { return e.fails.Load() }

func (e *Engine) learn() {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	last := time.Now()
	for {
		select {
		case <-e.stop:
			return
		case now := <-t.C:
			e.Split.Tick(now.Sub(last).Seconds())
			last = now
		}
	}
}

func dest(id stack.TransportEndpointID) (net.IP, int) {
	return net.IP(id.LocalAddress.AsSlice()), int(id.LocalPort)
}

// dial opens a connection to the destination on the network the splitter picks; if that fails, on the others.
func (e *Engine) dial(proto string, ip net.IP, port int) (net.Conn, split.Net, error) {
	v6 := ip.To4() == nil
	addr := net.JoinHostPort(ip.String(), strconv.Itoa(port))
	tried := map[string]bool{}
	var lastErr error
	for i := 0; i < 4; i++ {
		n, ok := e.Split.Pick(v6)
		if !ok {
			break
		}
		if tried[n.Name] {
			continue
		}
		tried[n.Name] = true
		// 5 s: a working network answers far sooner; a network that is going away should not hold the app long,
		// the next try goes to another network.
		d := net.Dialer{Timeout: 5 * time.Second, Control: control(proto, n.Handle)}
		t0 := time.Now()
		c, err := d.Dial(proto, addr)
		if err == nil {
			if proto == "tcp" {
				e.Split.Answered(n.Name, time.Since(t0))
			}
			e.track(c, n.Handle)
			return c, n, nil
		}
		if proto == "tcp" {
			e.Split.Answered(n.Name, 5*time.Second) // could not answer: counts as very slow
		}
		lastErr = err
	}
	e.fails.Add(1)
	if lastErr == nil {
		lastErr = errNoNetwork
	}
	return nil, split.Net{}, lastErr
}

var errNoNetwork = &net.AddrError{Err: "no network can reach this address"}

// dialRanked opens a connection on the best network (Splitter.Ranked), or the next if that fails.
func (e *Engine) dialRanked(proto string, ip net.IP, port int) (net.Conn, split.Net, error) {
	addr := net.JoinHostPort(ip.String(), strconv.Itoa(port))
	var lastErr error = errNoNetwork
	for _, n := range e.Split.Ranked(ip.To4() == nil) {
		d := net.Dialer{Timeout: 5 * time.Second, Control: control(proto, n.Handle)}
		c, err := d.Dial(proto, addr)
		if err == nil {
			e.track(c, n.Handle)
			return c, n, nil
		}
		lastErr = err
	}
	e.fails.Add(1)
	return nil, split.Net{}, lastErr
}

// HandleTCP and HandleUDP are called from the stack's packet loop: they must return at once.
func (e *Engine) HandleTCP(c adapter.TCPConn) { go e.relayTCP(c) }

// HandleUDP: see HandleTCP.
func (e *Engine) HandleUDP(c adapter.UDPConn) { go e.relayUDP(c) }

// relayTCP relays one TCP connection of an app.
func (e *Engine) relayTCP(c adapter.TCPConn) {
	defer c.Close()
	ip, port := dest(c.ID())
	if isVirtualDNS(ip) {
		if port != 53 {
			return // encrypted DNS (853) is refused: Android then uses plain DNS, which can be filtered
		}
		ip = e.upstream()
	}
	out, n, err := e.dial("tcp", ip, port)
	if err != nil {
		return
	}
	defer func() { e.untrack(out); out.Close() }()
	rx, tx, active := e.Split.Counter(n.Name)
	active.Add(1)
	defer active.Add(-1)
	done := make(chan struct{}, 2)
	go func() { copyCount(out, c, tx); closeWrite(out); done <- struct{}{} }()
	go func() { copyCount(c, out, rx); closeWrite(c); done <- struct{}{} }()
	<-done
	<-done
}

// upstream: the real DNS server for the next question to the VPN's own DNS address (taking turns).
func (e *Engine) upstream() net.IP {
	return upstreamDNS[int(e.dnsN.Add(1))%len(upstreamDNS)]
}

// relayDNS handles one app's DNS flow: questions for blocked names are answered here; the rest go to the DNS
// server (the real one when the app asked the VPN's own address).
func (e *Engine) relayDNS(c adapter.UDPConn, ip net.IP) {
	defer c.Close()
	if isVirtualDNS(ip) {
		ip = e.upstream()
	}
	const idle = 10 * time.Second
	var out net.Conn
	var tx *atomic.Int64
	done := make(chan struct{})
	defer func() {
		if out != nil {
			out.Close()
			<-done
			e.untrack(out)
		}
	}()
	buf := make([]byte, 64<<10)
	for {
		_ = c.SetReadDeadline(time.Now().Add(idle))
		k, err := c.Read(buf)
		if err != nil {
			return
		}
		if ans, ok := e.Block.Answer(buf[:k]); ok {
			if _, err := c.Write(ans); err != nil {
				return
			}
			continue
		}
		if out == nil {
			// DNS goes on the best network (quick to answer), never by share: every page waits for its lookups
			o, n, err := e.dialRanked("udp", ip, 53)
			if err != nil {
				return
			}
			out = o
			rx, t, active := e.Split.Counter(n.Name)
			tx = t
			active.Add(1)
			go func() {
				defer close(done)
				defer active.Add(-1)
				copyPackets(c, out, rx, idle)
				c.Close() // the server went quiet: end the flow (the app asks again on a new one)
			}()
		}
		if _, err := out.Write(buf[:k]); err != nil {
			return
		}
		tx.Add(int64(k))
	}
}

// relayUDP relays one UDP flow (DNS, QUIC, calls) on one network for its whole life.
func (e *Engine) relayUDP(c adapter.UDPConn) {
	ip, port := dest(c.ID())
	if port == 53 {
		e.relayDNS(c, ip)
		return
	}
	defer c.Close()
	out, n, err := e.dial("udp", ip, port)
	if err != nil {
		return
	}
	defer func() { e.untrack(out); out.Close() }()
	rx, tx, active := e.Split.Counter(n.Name)
	active.Add(1)
	defer active.Add(-1)
	idle := 60 * time.Second
	done := make(chan struct{}, 2)
	go func() { copyPackets(out, c, tx, idle); done <- struct{}{} }()
	go func() { copyPackets(c, out, rx, idle); done <- struct{}{} }()
	<-done
	c.Close()
	out.Close()
	<-done
}

func copyCount(dst io.Writer, src io.Reader, n *atomic.Int64) {
	buf := make([]byte, 32<<10)
	for {
		k, err := src.Read(buf)
		if k > 0 {
			if _, werr := dst.Write(buf[:k]); werr != nil {
				return
			}
			n.Add(int64(k))
		}
		if err != nil {
			return
		}
	}
}

func copyPackets(dst net.Conn, src net.Conn, n *atomic.Int64, idle time.Duration) {
	buf := make([]byte, 64<<10)
	for {
		_ = src.SetReadDeadline(time.Now().Add(idle))
		k, err := src.Read(buf)
		if err != nil {
			return
		}
		if _, err := dst.Write(buf[:k]); err != nil {
			return
		}
		n.Add(int64(k))
	}
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	} else {
		_ = c.Close()
	}
}

func init() { log.SetFlags(0) }

// appSendBuffer: how much of an app's upload the engine's stack holds per connection (see Start).
const appSendBuffer = 256 << 10

// notSentLowat: the most that may wait unsent in a socket's own buffer. Without it Android lets several MB wait
// per connection, and an app's upload looks done long before it has left the phone (TCP_NOTSENT_LOWAT keeps what
// is already on its way unlimited, so speed does not suffer).
const notSentLowat = 128 << 10

// control prepares each outgoing socket: bound to its network, and for TCP a limit on unsent data.
func control(proto string, handle int64) func(network, address string, c syscall.RawConn) error {
	bind := bindToNetwork(handle)
	return func(network, address string, c syscall.RawConn) error {
		if bind != nil {
			if err := bind(network, address, c); err != nil {
				return err
			}
		}
		if proto == "tcp" {
			_ = c.Control(func(fd uintptr) {
				_ = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_TCP, 25 /* TCP_NOTSENT_LOWAT */, notSentLowat)
			})
		}
		return nil
	}
}
