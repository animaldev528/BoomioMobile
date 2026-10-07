package overlaywg

// Code generated from the SERVER's implementation. DO NOT EDIT BY HAND.
//
// These vectors were produced by bsc/lib/overlay-provision-channel.js itself --
// `_deriveKeys` and `_seal`, the same functions that serve a real handshake --
// so the test that consumes them is an interop test against the real server
// rather than an echo of the spec written twice. The generator also asserts,
// before emitting anything, that the device-side derivation
// X25519(device_priv, ppk) lands on the same shared secret the server computes
// from X25519(server_priv, device_pub); if that ever stops holding, the
// generator fails instead of emitting a vector that would enshrine the bug.
//
// To regenerate after a protocol change, run the generator against a checkout
// of the server and copy the result over this file.

const (
	vecDevicePrivB64    = "AAIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eH2A="
	vecDevicePubB64     = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw="
	vecPpkB64           = "YFpyXSpK3+6xop4X7dYhwbdZPujNvESsbEq24vgF0jw="
	vecClientNonceB64   = "ERERERERERERERERERERERERERERERERERERERERERE="
	vecServerNonceB64   = "IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI="
	vecSharedSecretB64  = "yqFP7de/a7JQS1xN85yEucjmhItuBtnQ+7xeq8qqF1k="
	vecKc2sB64          = "oDUD0NCpdVbyz7VMik3tx0WMYfS5s2CT/sYls/ce+Z8="
	vecKs2cB64          = "0gNaeqgK+m1KxozaOEtCdaJP3UTUQb4nnI/MY/CVmFc="
	vecS2cPlaintextB64  = "eyJ0IjoiaGVsbG8iLCJ2IjoxfQ=="
	vecS2cSealedB64     = "OuXzJ6DQh63XnQwa94+4sVVZP5BSGecUNVcaKdyP2j4h6TE="
	vecC2sPlaintextB64  = "eyJ0IjoicGFpci5yZXF1ZXN0IiwiZGV2aWNlX2lkIjoidmVjIn0="
	vecC2sSealedB64     = "oTlcyc7mxAl93PftTfKniL9wTX6V2/++kAngTIunaBgr9PQ4OooKUXSc6Ucq0rMJce4PsI82"
)
