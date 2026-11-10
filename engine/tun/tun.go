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
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/stack"

	"github.com/nisar87/netmerge-android/engine/split"
)

// Engine is one running VPN.
type Engine struct {
	Split split.Splitter
	stack *stack.Stack
	dev   interface{ Close() }
	stop  chan struct{}
	once  sync.Once
	fails atomic.Int64
}

// Start runs the engine on the VPN's file descriptor.
func Start(fd int, mtu int, nets []split.Net) (*Engine, error) {
	e := &Engine{stop: make(chan struct{})}
	e.Split.SetNets(nets)
	dev, err := fdbased.Open(strconv.Itoa(fd), uint32(mtu), 0)
	if err != nil {
		return nil, err
	}
	st, err := core.CreateStack(&core.Config{LinkEndpoint: dev, TransportHandler: e})
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
			e.stack.Close()
		}
		if e.dev != nil {
			e.dev.Close()
		}
	})
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
	for i := 0; i < 3; i++ {
		n, ok := e.Split.Pick(v6)
		if !ok {
			break
		}
		if tried[n.Name] {
			continue
		}
		tried[n.Name] = true
		d := net.Dialer{Timeout: 10 * time.Second, Control: bindToNetwork(n.Handle)}
		c, err := d.Dial(proto, addr)
		if err == nil {
			return c, n, nil
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

// HandleTCP relays one TCP connection of an app.
func (e *Engine) HandleTCP(c adapter.TCPConn) {
	defer c.Close()
	ip, port := dest(c.ID())
	out, n, err := e.dial("tcp", ip, port)
	if err != nil {
		return
	}
	defer out.Close()
	rx, tx, active := e.Split.Counter(n.Name)
	active.Add(1)
	defer active.Add(-1)
	done := make(chan struct{}, 2)
	go func() { copyCount(out, c, tx); closeWrite(out); done <- struct{}{} }()
	go func() { copyCount(c, out, rx); closeWrite(c); done <- struct{}{} }()
	<-done
	<-done
}

// HandleUDP relays one UDP flow (DNS, QUIC, calls) on one network for its whole life.
func (e *Engine) HandleUDP(c adapter.UDPConn) {
	defer c.Close()
	ip, port := dest(c.ID())
	out, n, err := e.dial("udp", ip, port)
	if err != nil {
		return
	}
	defer out.Close()
	rx, tx, active := e.Split.Counter(n.Name)
	active.Add(1)
	defer active.Add(-1)
	idle := 60 * time.Second
	if port == 53 {
		idle = 10 * time.Second
	}
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
