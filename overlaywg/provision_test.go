package overlaywg

import (
	"bytes"
	"encoding/base64"
	"strings"
	"testing"
)

func mustB64(t *testing.T, s string) []byte {
	t.Helper()
	raw, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		t.Fatalf("bad base64 in vector: %v", err)
	}
	return raw
}

func newVectorSession(t *testing.T) *ProvisionSession {
	t.Helper()
	s, err := NewProvisionSession(
		vecDevicePrivB64, vecPpkB64, vecClientNonceB64, vecServerNonceB64)
	if err != nil {
		t.Fatalf("NewProvisionSession: %v", err)
	}
	return s
}

// The interop test. Everything else here is about behaviour under failure; this
// is the one that says the Go client and the Node server actually speak the
// same protocol, using vectors the server itself produced.
func TestProvisionSessionMatchesServerVectors(t *testing.T) {
	s := newVectorSession(t)

	// 1. The server's first sealed frame (HELLO_ACK's payload) opens at counter 0.
	sealed := mustB64(t, vecS2cSealedB64)
	want := mustB64(t, vecS2cPlaintextB64)

	got, err := s.Open(sealed)
	if err != nil {
		t.Fatalf("Open(server frame): %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("server frame decrypted to %q, want %q", got, want)
	}

	// 2. Our own first sealed frame is byte-identical to the server's, which is
	//    what proves the c2s key and the counter layout agree. GCM is
	//    deterministic given key, nonce, plaintext and AAD, so equality here is
	//    a strict statement about all four.
	out, err := s.Seal(mustB64(t, vecC2sPlaintextB64))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	if !bytes.Equal(out, mustB64(t, vecC2sSealedB64)) {
		t.Fatalf("device frame = %s, want %s",
			base64.StdEncoding.EncodeToString(out), vecC2sSealedB64)
	}

	// 3. Both directions start at 0 for their OWN counter and advance separately.
	if s.SendCounter() != 1 || s.RecvCounter() != 1 {
		t.Fatalf("counters = send %d / recv %d, want 1 / 1",
			s.SendCounter(), s.RecvCounter())
	}
}

// The trust anchor: this is the property the whole design rests on. A device
// that derives against a key it did not get from the server's own tuple cannot
// read the server's reply — so a reply that DOES decrypt is proof of the server,
// and a reply that does not must stop the handshake before anything is sent.
func TestProvisionWrongServerKeyCannotOpen(t *testing.T) {
	// A different, well-formed `ppk` — e.g. what a spoofed TXT record would
	// carry. Minted through the production path so it is a realistic key.
	wrong, err := GenerateKeypair()
	if err != nil {
		t.Fatalf("GenerateKeypair: %v", err)
	}

	s, err := NewProvisionSession(
		vecDevicePrivB64, wrong.PublicKeyB64,
		vecClientNonceB64, vecServerNonceB64)
	if err != nil {
		t.Fatalf("NewProvisionSession: %v", err)
	}

	if _, err := s.Open(mustB64(t, vecS2cSealedB64)); err == nil {
		t.Fatal("a frame sealed to the real key opened under a different server key")
	}
}

func TestProvisionOpenRejectsTampering(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func([]byte) []byte
	}{
		{"flipped body bit", func(b []byte) []byte { b[0] ^= 0x01; return b }},
		{"flipped tag bit", func(b []byte) []byte { b[len(b)-1] ^= 0x01; return b }},
		{"truncated", func(b []byte) []byte { return b[:len(b)-1] }},
		{"empty", func(b []byte) []byte { return nil }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := newVectorSession(t)
			if _, err := s.Open(tc.mutate(mustB64(t, vecS2cSealedB64))); err == nil {
				t.Fatal("tampered frame authenticated")
			}
			// A failed open must not consume the counter, or a single corrupt
			// frame would desynchronise the rest of the session.
			if s.RecvCounter() != 0 {
				t.Fatalf("receive counter advanced on a failed open: %d", s.RecvCounter())
			}
		})
	}
}

// Replay defence. The counter is never transmitted; each side simply expects the
// next number, so a frame that arrives twice fails the second time.
func TestProvisionCountersRejectReplay(t *testing.T) {
	s := newVectorSession(t)
	sealed := mustB64(t, vecS2cSealedB64)

	if _, err := s.Open(sealed); err != nil {
		t.Fatalf("first open: %v", err)
	}
	if _, err := s.Open(sealed); err == nil {
		t.Fatal("the same frame opened twice")
	}
}

// The same plaintext sealed twice must not produce the same bytes — that is what
// the counter is for, and it is the one GCM property whose failure is silent.
func TestProvisionSealIsNotDeterministicAcrossCounters(t *testing.T) {
	s := newVectorSession(t)
	msg := []byte("same bytes")

	first, err := s.Seal(msg)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	second, err := s.Seal(msg)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	if bytes.Equal(first, second) {
		t.Fatal("two frames sealed under different counters are identical (nonce reuse)")
	}
}

func TestProvisionRejectsMalformedInput(t *testing.T) {
	valid := vecDevicePrivB64
	for _, tc := range []struct {
		name                            string
		priv, pub, clientN, serverN string
		wantSubstr                      string
	}{
		{"device key not base64", "!!!!", vecPpkB64, vecClientNonceB64, vecServerNonceB64, "not base64"},
		{"device key too short", base64.StdEncoding.EncodeToString(make([]byte, 16)), vecPpkB64, vecClientNonceB64, vecServerNonceB64, "32 bytes"},
		{"ppk too long", valid, base64.StdEncoding.EncodeToString(make([]byte, 33)), vecClientNonceB64, vecServerNonceB64, "32 bytes"},
		{"nonce too short", valid, vecPpkB64, base64.StdEncoding.EncodeToString(make([]byte, 8)), vecServerNonceB64, "client nonce"},
		{"server nonce missing", valid, vecPpkB64, vecClientNonceB64, "", "server nonce"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := NewProvisionSession(tc.priv, tc.pub, tc.clientN, tc.serverN)
			if err == nil {
				t.Fatal("expected an error")
			}
			if !strings.Contains(err.Error(), tc.wantSubstr) {
				t.Fatalf("error %q does not mention %q", err, tc.wantSubstr)
			}
		})
	}
}

// A low-order public key forces a constant shared secret, which is the classic
// way to make a key agreement produce a value an attacker already knows.
// curve25519 reports it; the handshake must refuse rather than proceed.
func TestProvisionRejectsLowOrderServerKey(t *testing.T) {
	zero := base64.StdEncoding.EncodeToString(make([]byte, 32))
	if _, err := NewProvisionSession(
		vecDevicePrivB64, zero, vecClientNonceB64, vecServerNonceB64); err == nil {
		t.Fatal("an all-zero server key produced a session")
	}
}

// The nonce is the protocol's, not ours: eight zero bytes then the counter
// big-endian. Pinned because it is the kind of thing that is easy to change on
// one side only, and the resulting failure is an opaque auth error.
func TestGcmNonceLayout(t *testing.T) {
	for _, c := range []uint32{0, 1, 255, 256, 0x01020304, ^uint32(0)} {
		n := gcmNonce(c)
		want := []byte{
			0, 0, 0, 0, 0, 0, 0, 0,
			byte(c >> 24), byte(c >> 16), byte(c >> 8), byte(c),
		}
		if !bytes.Equal(n, want) {
			t.Fatalf("gcmNonce(%d) = %v, want %v", c, n, want)
		}
	}
}
