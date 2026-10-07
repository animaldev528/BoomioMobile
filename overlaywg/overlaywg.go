// Package overlaywg is the production WireGuard transport for the Boomio overlay,
// built into an Android AAR by `gomobile bind`.
//
// It exists because Android permits exactly one active VpnService and NordVPN holds it,
// so the app cannot take that slot. A userspace WireGuard (wireguard-go) with gVisor's
// netstack needs no slot — but it also captures nothing: there is no interface for the
// kernel to route through, so a socket opened by the app does not traverse it. Everything
// that should use the tunnel has to ask for it explicitly, which is why the Kotlin side
// runs one loopback HTTP CONNECT relay and this package supplies only the dial the relay
// calls for hosts in its carried set.
//
// # Why the API is shaped like this
//
// `gomobile bind` can carry a `net.Conn` out to Java, but only as an opaque proxy object,
// and every method on it crosses the boundary as a JNI call anyway. Rather than put that
// object in Kotlin's way, this package keeps the connection on the Go side and hands back
// an integer handle. That keeps the Kotlin binding honest — a handful of static natives
// with no lifetime rules of their own — and it puts the blocking reads on Go's own
// goroutine pool, where a `Close` from another thread unblocks them the way Go's network
// stack already guarantees.
//
// # The one contract that matters across the boundary
//
// Read and Write deliberately have **no `error` return**. gobind turns a Go `error` into a
// thrown Java exception, and a thrown exception is the wrong shape for an end-of-stream
// that is expected on every single connection teardown — the relay would spend the whole
// session catching. Instead both return a count, and **-1 means the stream is finished**.
// That is exactly the sentinel `java.io.InputStream.read` already uses, so the Kotlin
// adapter translates nothing: a `-1` here is a `-1` there.
package overlaywg

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// dialTimeout bounds a TCP connect through the tunnel. Without it a blackholed path
// parks the calling thread until the kernel gives up, which on Android is minutes — and
// the relay would hold a client connection open for all of it rather than reporting 502.
const dialTimeout = 20 * time.Second

// Keypair carries a WireGuard keypair in both encodings, because the two ends of this
// system want different ones: `wg set` and the mDNS/DuckDNS adverts speak **base64**, and
// wireguard-go's IpcSet speaks **hex**.
type Keypair struct {
	PrivateKeyB64 string
	PublicKeyB64  string
	PrivateKeyHex string
	PublicKeyHex  string
}

var (
	mu   sync.Mutex
	dev  *device.Device
	tnet *netstack.Net

	conns  = map[int64]net.Conn{}
	nextID int64

	lastErrMu sync.Mutex
	lastErr   string
)

// GenerateKeypair mints a fresh keypair.
func GenerateKeypair() (*Keypair, error) {
	var priv [32]byte
	if _, err := rand.Read(priv[:]); err != nil {
		return nil, fmt.Errorf("rand: %w", err)
	}
	// The standard WireGuard clamping. wireguard-go would do this itself when it parses
	// the key, but doing it here means the base64 we hand to the server and the hex we
	// hand to IpcSet describe the *same* private key rather than one clamped and one not.
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64
	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		return nil, fmt.Errorf("curve25519: %w", err)
	}
	return &Keypair{
		PrivateKeyB64: base64.StdEncoding.EncodeToString(priv[:]),
		PublicKeyB64:  base64.StdEncoding.EncodeToString(pub),
		PrivateKeyHex: hex.EncodeToString(priv[:]),
		PublicKeyHex:  hex.EncodeToString(pub),
	}, nil
}

// Up builds the netstack TUN, wires it into wireguard-go, and brings the device up.
//
// localCIDR is this device's overlay address ("10.77.0.2/32"). allowedIPs is what may be
// routed into the tunnel ("10.77.0.0/24") — anything outside it is not carried even if it
// is dialled through this handle. dns is the overlay resolver ("10.77.0.1"), which matters
// because Dial takes the **hostname from the CONNECT line**, not an address: the relay
// never resolves anything itself, so the name has to resolve inside the tunnel to an
// overlay address. Passing an empty dns disables name resolution, leaving Dial able to
// reach literal addresses only.
//
// keepalive is in seconds and should stay non-zero: the whole point of the overlay on the
// WAN path is to hold the NAT mapping open, and with hairpin off a mapping that expires is
// a tunnel that silently stops accepting.
func Up(privHex, peerPubHex, endpoint, localCIDR, allowedIPs, dns string, mtu, keepalive int) error {
	mu.Lock()
	defer mu.Unlock()

	if dev != nil {
		return errors.New("overlaywg: tunnel already up; call Down() first")
	}
	local, err := netip.ParsePrefix(localCIDR)
	if err != nil {
		return fmt.Errorf("localCIDR %q: %w", localCIDR, err)
	}

	// ⚠️ The DNS servers must live *inside* allowedIPs, or netstack drops the query on its
	// way out and every name lookup fails with a timeout rather than an error — which reads
	// on the client as "the tunnel is up but nothing loads".
	var resolvers []netip.Addr
	if dns != "" {
		addr, err := netip.ParseAddr(dns)
		if err != nil {
			return fmt.Errorf("dns %q: %w", dns, err)
		}
		resolvers = append(resolvers, addr)
	}

	tunDev, t, err := netstack.CreateNetTUN([]netip.Addr{local.Addr()}, resolvers, mtu)
	if err != nil {
		return fmt.Errorf("CreateNetTUN: %w", err)
	}

	// Error level only. wireguard-go's info level logs a line per handshake attempt, and
	// on Android that is stdout noise on a path that retries for as long as the tunnel is
	// up and the server is unreachable.
	logger := device.NewLogger(device.LogLevelError, "overlaywg: ")
	d := device.NewDevice(tunDev, conn.NewDefaultBind(), logger)

	cfg := fmt.Sprintf(
		"private_key=%s\npublic_key=%s\nendpoint=%s\nallowed_ip=%s\npersistent_keepalive_interval=%d\n",
		privHex, peerPubHex, endpoint, allowedIPs, keepalive,
	)
	if err := d.IpcSet(cfg); err != nil {
		d.Close()
		return fmt.Errorf("IpcSet: %w", err)
	}
	if err := d.Up(); err != nil {
		d.Close()
		return fmt.Errorf("Up: %w", err)
	}

	dev = d
	tnet = t
	return nil
}

// Down tears the tunnel down and closes every connection opened through it. Safe to call
// when nothing is up.
func Down() error {
	mu.Lock()
	defer mu.Unlock()

	for id, c := range conns {
		_ = c.Close()
		delete(conns, id)
	}
	if dev == nil {
		return nil
	}
	dev.Close()
	dev = nil
	tnet = nil
	return nil
}

// Status returns wireguard-go's own view of the device: the peer's handshake time and the
// tx/rx byte counters, as IpcGet renders them.
//
// This is the client-side oracle. A tunnel can be "up" — device running, socket bound —
// with a peer that has never answered, and the only thing that distinguishes that from a
// working tunnel is whether these counters move.
func Status() string {
	mu.Lock()
	d := dev
	mu.Unlock()
	if d == nil {
		return "(tunnel is not up)"
	}
	s, err := d.IpcGet()
	if err != nil {
		return fmt.Sprintf("(IpcGet failed: %v)", err)
	}
	return s
}

// Dial opens a TCP connection through the tunnel and returns a handle to it.
//
// host may be a literal overlay address or a name, which is resolved through the tunnel's
// own resolver — see Up. The handle is what Read/Write/Close take, and it is only valid
// until Close; handles are never reused, so a stale handle fails closed rather than
// landing on somebody else's connection.
func Dial(host string, port int) (int64, error) {
	mu.Lock()
	t := tnet
	mu.Unlock()
	if t == nil {
		return 0, errors.New("overlaywg: tunnel is not up")
	}

	ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
	defer cancel()

	c, err := t.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		rememberError(err)
		return 0, err
	}

	mu.Lock()
	nextID++
	id := nextID
	conns[id] = c
	mu.Unlock()
	return id, nil
}

// Read reads up to n bytes into buf starting at off, blocking until at least one byte
// arrives.
//
// Returns the number of bytes read, or **-1 when the stream is finished** — end of stream,
// a reset, or the connection having been closed under it. This is the same sentinel
// java.io.InputStream.read uses, deliberately: the Kotlin adapter passes it straight
// through with no translation to get wrong.
//
// ⚠️ **The window is taken here rather than in Kotlin on purpose.** gobind copies a Java
// `byte[]` into a fresh Go slice on every call, so an API without off/n would force the
// Kotlin side to allocate and copy a second array per read — 32 KB a time on the playback
// path — purely to express an offset Go already understands natively.
func Read(id int64, buf []byte, off, n int) int {
	window, ok := sliceOf(buf, off, n)
	if !ok {
		return -1
	}
	mu.Lock()
	c := conns[id]
	mu.Unlock()
	if c == nil {
		return -1
	}
	// A zero-length read is 0, not end of stream — that is the java.io.InputStream
	// contract, and returning -1 here would make a caller reading in shrinking chunks
	// believe the stream had ended.
	if len(window) == 0 {
		return 0
	}
	read, err := c.Read(window)
	if read > 0 {
		return read
	}
	if err != nil {
		rememberError(err)
	}
	// A net.Conn never returns (0, nil); it blocks instead. So reaching here without an
	// error would still mean the stream produced nothing, which is what -1 says.
	return -1
}

// Write writes n bytes from buf starting at off, returning the number accepted or **-1 on
// failure**.
//
// ⚠️ A short count is a success, not a failure: a Go net.Conn may accept part of a buffer
// and report no error, and the caller is expected to loop. The Kotlin side does.
func Write(id int64, buf []byte, off, n int) int {
	window, ok := sliceOf(buf, off, n)
	if !ok {
		return -1
	}
	if len(window) == 0 {
		return 0
	}
	mu.Lock()
	c := conns[id]
	mu.Unlock()
	if c == nil {
		return -1
	}
	written, err := c.Write(window)
	if err != nil && written == 0 {
		rememberError(err)
		return -1
	}
	return written
}

// sliceOf validates an offset/length window, so a bad one fails closed instead of reading
// or writing bytes outside the region the caller named.
func sliceOf(buf []byte, off, n int) ([]byte, bool) {
	if off < 0 || n < 0 || off > len(buf) || off+n > len(buf) {
		return nil, false
	}
	return buf[off : off+n], true
}

// Close closes one connection. Closing an already-closed or unknown handle is not an
// error: the relay's teardown path races a client that has already given up, and making
// that race throw would only produce noise.
func Close(id int64) error {
	mu.Lock()
	c := conns[id]
	delete(conns, id)
	mu.Unlock()
	if c == nil {
		return nil
	}
	return c.Close()
}

// LastError describes the most recent read or write failure, for logs. It is diagnostics
// only — Read and Write cannot report *why* they ended, because a Java exception on every
// ordinary teardown is the wrong shape, and this is the escape hatch that costs nothing.
func LastError() string {
	lastErrMu.Lock()
	defer lastErrMu.Unlock()
	return lastErr
}

func rememberError(err error) {
	lastErrMu.Lock()
	lastErr = err.Error()
	lastErrMu.Unlock()
}
