package main

import (
	"bytes"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/hex"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	oldConn "github.com/amnezia-vpn/amneziawg-go/conn"
	oldDevice "github.com/amnezia-vpn/amneziawg-go/device"
	oldTun "github.com/amnezia-vpn/amneziawg-go/tun/tuntest"
	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/tuntest"
)

type tunnel interface {
	IpcSet(string) error
	IpcGet() (string, error)
	Up() error
	Close()
}

func TestKeepaliveRangeIsAppliedByGo(t *testing.T) {
	vpn := device.NewDevice(tuntest.NewChannelTUN().TUN(), conn.NewStdNetBind(), device.NewLogger(device.LogLevelSilent, ""))
	t.Cleanup(vpn.Close)
	peer := newKey(t).PublicKey()
	peerConfig := "public_key=" + hex.EncodeToString(peer.Bytes()) + "\n"
	for _, tc := range []struct {
		name, interval, stored string
	}{
		{"disabled", "0", ""},
		{"fixed", "25", "25"},
		{"range", "22-35", "22-35"},
		{"uint16-boundary", "65535", "65535"},
		{"past-uint16", "65536", "65536"},
		{"uint32-maximum", "4294967295", "4294967295"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			apply(t, vpn, peerConfig+"persistent_keepalive_interval="+tc.interval+"\n")
			if getField(t, vpn, "persistent_keepalive_interval") != tc.stored {
				t.Fatal("native keepalive differs from the supplied setting")
			}
		})
	}
	for _, invalid := range []string{"-1", "31-22", "25.5", "4294967296", "22 - 35", "22-35-60"} {
		if vpn.IpcSet(peerConfig+"persistent_keepalive_interval="+invalid+"\n") == nil {
			t.Fatal("native parser accepted an invalid keepalive")
		}
	}
}

func TestRangedKeepaliveWithAWG2Peer(t *testing.T) {
	testAWG2Peer(t, "22-35", false)
}

func TestRecoveryHandshakeWithKeepaliveDisabled(t *testing.T) {
	testAWG2Peer(t, "0", true)
}

func testAWG2Peer(t *testing.T, keepalive string, initiateHandshake bool) {
	t.Helper()
	clientTun := tuntest.NewChannelTUN()
	serverTun := oldTun.NewChannelTUN()
	client := device.NewDevice(clientTun.TUN(), conn.NewStdNetBind(), device.NewLogger(device.LogLevelSilent, ""))
	server := oldDevice.NewDevice(serverTun.TUN(), oldConn.NewStdNetBind(), oldDevice.NewLogger(oldDevice.LogLevelSilent, ""))
	t.Cleanup(client.Close)
	t.Cleanup(server.Close)

	clientKey, serverKey := newKey(t), newKey(t)
	const awg2 = "s1=15\ns2=18\ns3=20\ns4=25\nh1=101-105\nh2=201-205\nh3=301-305\nh4=401-405\n"
	apply(t, client, "private_key="+hex.EncodeToString(clientKey.Bytes())+"\n"+awg2)
	apply(t, server, "private_key="+hex.EncodeToString(serverKey.Bytes())+"\n"+awg2)
	if client.Up() != nil || server.Up() != nil {
		t.Fatal("could not start the test tunnels")
	}

	clientIP := netip.MustParseAddr("192.0.2.1")
	serverIP := netip.MustParseAddr("192.0.2.2")
	apply(t, server, peerSettings(t, clientKey, client, clientIP))
	apply(t, client, peerSettings(t, serverKey, server, serverIP)+"persistent_keepalive_interval="+keepalive+"\n")
	if !initiateHandshake && getField(t, client, "persistent_keepalive_interval") != keepalive {
		t.Fatal("new engine did not preserve the range")
	}
	if initiateHandshake {
		var publicKey device.NoisePublicKey
		copy(publicKey[:], serverKey.PublicKey().Bytes())
		peer := client.LookupPeer(publicKey)
		if peer == nil || getField(t, client, "last_handshake_time_sec") != "0" {
			t.Fatal("recovery must start with a fresh peer")
		}
		peer.SendKeepalive()
		deadline := time.Now().Add(8 * time.Second)
		for getField(t, client, "last_handshake_time_sec") == "0" {
			if time.Now().After(deadline) {
				t.Fatal("empty AWG packet did not establish a new handshake")
			}
			time.Sleep(20 * time.Millisecond)
		}
	}

	checkDelivery(t, tuntest.Ping(serverIP, clientIP), clientTun.Outbound, serverTun.Inbound)
	checkDelivery(t, tuntest.Ping(clientIP, serverIP), serverTun.Outbound, clientTun.Inbound)
}

func peerSettings(t *testing.T, key *ecdh.PrivateKey, other tunnel, address netip.Addr) string {
	t.Helper()
	port := getField(t, other, "listen_port")
	if port == "" {
		t.Fatal("test tunnel has no listen port")
	}
	return "public_key=" + hex.EncodeToString(key.PublicKey().Bytes()) + "\n" +
		"endpoint=" + net.JoinHostPort("127.0.0.1", port) + "\n" +
		"allowed_ip=" + address.String() + "/32\n"
}

func checkDelivery(t *testing.T, packet []byte, outbound chan<- []byte, inbound <-chan []byte) {
	t.Helper()
	timer := time.NewTimer(8 * time.Second)
	defer timer.Stop()
	select {
	case outbound <- packet:
	case <-timer.C:
		t.Fatal("test tunnel did not accept a packet")
	}
	select {
	case received := <-inbound:
		if !bytes.Equal(received, packet) {
			t.Fatal("test tunnel changed a packet")
		}
	case <-timer.C:
		t.Fatal("test tunnel did not deliver a packet")
	}
}

func newKey(t *testing.T) *ecdh.PrivateKey {
	t.Helper()
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal("could not generate a test key")
	}
	return key
}

func apply(t *testing.T, vpn tunnel, settings string) {
	t.Helper()
	if vpn.IpcSet(settings) != nil {
		t.Fatal("native parser rejected the test configuration")
	}
}

func getField(t *testing.T, vpn tunnel, field string) string {
	t.Helper()
	settings, err := vpn.IpcGet()
	if err != nil {
		t.Fatal("could not read the applied test settings")
	}
	for _, line := range strings.Split(settings, "\n") {
		if name, value, ok := strings.Cut(line, "="); ok && name == field {
			return value
		}
	}
	return ""
}
