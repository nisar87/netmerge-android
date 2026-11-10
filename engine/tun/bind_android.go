//go:build android

package tun

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
*/
import "C"

import (
	"fmt"
	"syscall"
)

// bindToNetwork makes a socket leave through one Android network (Wi-Fi or mobile data), the way
// Network.bindSocket does in Java. handle is Network.getNetworkHandle().
func bindToNetwork(handle int64) func(network, address string, c syscall.RawConn) error {
	return func(network, address string, c syscall.RawConn) error {
		var rc C.int
		err := c.Control(func(fd uintptr) {
			rc = C.android_setsocknetwork(C.net_handle_t(handle), C.int(fd))
		})
		if err != nil {
			return err
		}
		if rc != 0 {
			return fmt.Errorf("bind to network %d failed (%d)", handle, rc)
		}
		return nil
	}
}
