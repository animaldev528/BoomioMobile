package overlaywg

// The provisioning handshake's cryptography.
//
// # Why this lives in Go rather than in Kotlin
//
// The device has to prove it is talking to the real server *before* it hands over a
// session token, and there is no TLS on this channel (a certificate is impossible on the
// name that carries the discovery tuple — DuckDNS gives each name one TXT slot and the
// ACME DNS-01 challenge needs the same slot the tuple occupies). So the proof is X25519:
// the device derives a shared secret against the server's published provisioning key,
// and a reply it cannot decrypt is a reply from somebody else.
//
// That means the client needs X25519, HKDF-SHA256 and AES-256-GCM. Kotlin has the third
// (`javax.crypto` "AES/GCM/NoPadding") but not the first two: there is no BouncyCastle
// and no Tink on this classpath, and JCA's "XDH" only exists from API 33 while this app
// ships to API 24. This package already vendors `golang.org/x/crypto` and already calls
// curve25519 for keypair generation, so the missing primitives cost nothing here and
// would otherwise mean either a new dependency or a hand-rolled curve — and hand-rolled
// X25519 is exactly the thing that must never be written.
//
// The split is therefore: **Go owns every operation that touches a key**, and Kotlin
// owns the socket, the 4-byte framing and the message state machine. A Kotlin test never
// needs to be trusted about crypto, and the Go half is testable with plain `go test`
// against vectors produced by the server's own implementation.
//
// # The schedule, which must match bsc/lib/overlay-provision-channel.js exactly
//
//	S      = X25519(device_priv, ppk)
//	salt   = client_nonce ‖ server_nonce          (that order, 64 bytes)
//	kc2s   = HKDF-SHA256(ikm=S, salt, "boomio-provision v1 c2s", 32)
//	ks2c   = HKDF-SHA256(ikm=S, salt, "boomio-provision v1 s2c", 32)
//
// Sealed = AES-256-GCM, 12-byte nonce `00000000 00000000 ‖ uint32_be(counter)`,
// 16-byte tag appended, AAD `boomio-provision-v1`. **Counters are independent per
// direction and both start at 0** — `HELLO_ACK` is the server's 0, and the device's
// first sealed message is its own 0.
//
// ⚠️ There is no forward secrecy. `device_priv` is the device's long-lived WireGuard
// key, so a later compromise of the server's provisioning key decrypts past sessions.
// Accepted and documented upstream: this channel carries a one-time config handover, and
// the device is not authenticated by its key at all — it is authorised by the
// human-approved user code.

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
)

const (
	// The AAD is a fixed protocol string, not a description of the message: it binds
	// every frame to this protocol so a ciphertext from some other use of the same
	// key pair cannot be replayed into it.
	provisionAAD = "boomio-provision-v1"

	hkdfInfoC2S = "boomio-provision v1 c2s"
	hkdfInfoS2C = "boomio-provision v1 s2c"

	provisionKeyLen   = 32
	provisionNonceLen = 32
)

// ProvisionSession is the post-handshake state: one AEAD key per direction and a
// counter per direction.
//
// The counters are the replay defence. They are not transmitted — each side simply
// expects the next number — so a frame replayed or reordered fails to open rather than
// being silently accepted twice.
type ProvisionSession struct {
	send cipher.AEAD
	recv cipher.AEAD

	sendCounter uint32
	recvCounter uint32
}

// NewProvisionSession completes the handshake's key agreement.
//
// devicePrivB64 is this device's WireGuard **private** key, base64. serverPubB64 is the
// `ppk` value the device read out of the DuckDNS tuple. clientNonceB64/serverNonceB64
// are the two 32-byte nonces in base64, client first.
//
// It returns an error — which gobind surfaces to Kotlin as a thrown exception — for
// anything that is not a usable handshake: malformed base64, a key or nonce of the wrong
// length, or an X25519 output that is all zeroes. That last one is not hypothetical: a
// low-order public key forces a constant shared secret, and `curve25519.X25519` reports
// it rather than returning it. Refusing is the whole point of the check.
func NewProvisionSession(devicePrivB64, serverPubB64, clientNonceB64, serverNonceB64 string) (*ProvisionSession, error) {
	priv, err := decodeFixed(devicePrivB64, 32, "device private key")
	if err != nil {
		return nil, err
	}
	pub, err := decodeFixed(serverPubB64, 32, "server public key")
	if err != nil {
		return nil, err
	}
	clientNonce, err := decodeFixed(clientNonceB64, provisionNonceLen, "client nonce")
	if err != nil {
		return nil, err
	}
	serverNonce, err := decodeFixed(serverNonceB64, provisionNonceLen, "server nonce")
	if err != nil {
		return nil, err
	}

	secret, err := curve25519.X25519(priv, pub)
	if err != nil {
		// The low-order-point case. Distinguished in the message because it means
		// something specific and actionable — a bad or hostile `ppk` — rather than a
		// generic crypto failure.
		return nil, fmt.Errorf("provision: key agreement failed (bad or low-order server key): %w", err)
	}

	// The salt is both nonces in the order the client sent them, so the server's
	// derivation and this one agree without either side transmitting a salt.
	salt := make([]byte, 0, len(clientNonce)+len(serverNonce))
	salt = append(salt, clientNonce...)
	salt = append(salt, serverNonce...)

	c2s, err := deriveKey(secret, salt, hkdfInfoC2S)
	if err != nil {
		return nil, err
	}
	s2c, err := deriveKey(secret, salt, hkdfInfoS2C)
	if err != nil {
		return nil, err
	}

	return &ProvisionSession{send: c2s, recv: s2c}, nil
}

// Seal encrypts one outbound message and advances the send counter.
func (p *ProvisionSession) Seal(plaintext []byte) ([]byte, error) {
	if p.sendCounter == ^uint32(0) {
		// Unreachable in practice — the server caps a connection at 200 messages —
		// but a wrapped counter would reuse a nonce, which is the one failure mode
		// that silently destroys GCM's guarantees.
		return nil, errors.New("provision: send counter exhausted")
	}
	out := p.send.Seal(nil, gcmNonce(p.sendCounter), plaintext, []byte(provisionAAD))
	p.sendCounter++
	return out, nil
}

// Open decrypts one inbound message and advances the receive counter.
//
// A failure here is terminal for the connection rather than retryable: it means the
// frame was forged, replayed, reordered, or sealed by a peer that does not hold the
// shared secret. The caller must treat it as a hard stop — in particular, an
// unopenable HELLO_ACK means the server is not who `ppk` said it was, and nothing
// should be sent to it.
func (p *ProvisionSession) Open(sealed []byte) ([]byte, error) {
	if p.recvCounter == ^uint32(0) {
		return nil, errors.New("provision: receive counter exhausted")
	}
	out, err := p.recv.Open(nil, gcmNonce(p.recvCounter), sealed, []byte(provisionAAD))
	if err != nil {
		return nil, errors.New("provision: message did not authenticate")
	}
	p.recvCounter++
	return out, nil
}

// SendCounter is how many messages have been sealed. Used by tests and by the client
// to assert its own ordering; it is not part of the wire format.
func (p *ProvisionSession) SendCounter() int { return int(p.sendCounter) }

// RecvCounter is how many messages have been opened.
func (p *ProvisionSession) RecvCounter() int { return int(p.recvCounter) }

// deriveKey runs one leg of the key schedule.
//
// Extract-then-Expand spelled out rather than the one-shot helper, because this
// is the step the Go side has to agree with the server about and HKDF's two
// stages are exactly what the doc calls kc2s/ks2c. Same salt and secret for
// both legs; only the info string differs.
func deriveKey(secret, salt []byte, info string) (cipher.AEAD, error) {
	prk := hkdf.Extract(sha256.New, secret, salt)
	key := make([]byte, provisionKeyLen)
	if _, err := io.ReadFull(hkdf.Expand(sha256.New, prk, []byte(info)), key); err != nil {
		return nil, fmt.Errorf("provision: hkdf expand: %w", err)
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("provision: aes: %w", err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("provision: gcm: %w", err)
	}
	return aead, nil
}

// gcmNonce is the protocol's fixed 12-byte nonce layout: eight zero bytes then the
// counter big-endian. Spelled with a full 12-byte buffer rather than the equivalent
// 4-byte form so it reads the same as the server's `gcmNonce`, which is the thing it
// has to match.
func gcmNonce(counter uint32) []byte {
	n := make([]byte, 12)
	n[8] = byte(counter >> 24)
	n[9] = byte(counter >> 16)
	n[10] = byte(counter >> 8)
	n[11] = byte(counter)
	return n
}

// decodeFixed decodes standard base64 and insists on an exact length, so a truncated
// key fails here rather than as a confusing crypto error later.
func decodeFixed(value string, want int, what string) ([]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(value)
	if err != nil {
		return nil, fmt.Errorf("provision: %s is not base64: %w", what, err)
	}
	if len(raw) != want {
		return nil, fmt.Errorf("provision: %s must be %d bytes, got %d", what, want, len(raw))
	}
	return raw, nil
}
