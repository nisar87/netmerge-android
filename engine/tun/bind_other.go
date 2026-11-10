//go:build !android

package tun

import "syscall"

// On a computer (tests), connections simply use the normal route.
func bindToNetwork(handle int64) func(network, address string, c syscall.RawConn) error {
	return nil
}
