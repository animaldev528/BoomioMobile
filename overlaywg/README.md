# `overlaywg` — the userspace WireGuard transport

The Go half of U2. It is built into an Android AAR by `gomobile bind` and consumed by
`composeApp/src/androidMain/kotlin/com/nuvio/app/core/overlay/` as the **carried** dialler
behind the loopback CONNECT relay.

**Why userspace at all.** Android allows exactly one active `VpnService` and NordVPN holds
it, so the app cannot take the slot. `wireguard-go` with gVisor's netstack needs no slot —
and captures nothing, which is why the Kotlin side has a relay at all. See
`docs/vpn-overlay-userland-architecture.md` in the backend repo.

## The boundary in one paragraph

`gomobile bind` turns a Go `error` into a **thrown Java exception**, and it maps `int` to
Java `long`. Both facts shape the API. `Dial` throws, because a connect failure is a real
failure the relay reports as `502`. `Read` and `Write` deliberately **do not** return an
error: an end-of-stream happens on every single connection teardown, and making the common
case an exception would mean catching on the hot path. They return a count instead, with
**`-1` meaning the stream is finished** — the same sentinel `java.io.InputStream.read`
already uses, so the Kotlin adapter translates nothing.

`Write` returning a short count is a **success**, not a failure: a Go `net.Conn` may accept
part of a buffer with no error, and the caller loops. The Kotlin side does.

## Build

On the build box (`.56`) — never on the media host, and never wrapped in `timeout`:

```sh
cd ~/overlaywg-prod
export PATH=/usr/local/go/bin:$HOME/go/bin:$PATH
go mod tidy
gomobile bind -target=android/arm64,android/arm -androidapi 24 -o /tmp/overlaywg.aar .
```

Then copy the result to the client:

```sh
cp /tmp/overlaywg.aar composeApp/libs/lib-overlaywg-release.aar
```

⚠️ **The `lib-` prefix is load-bearing.** `composeApp/build.gradle.kts` picks the AAR up
through `fileTree(mapOf("dir" to "libs", "include" to listOf("lib-*.aar")))`, so an AAR
named anything else sits in the directory looking installed and is silently not on the
classpath — and the failure surfaces as an unresolved reference in the Kotlin that uses it.

`-androidapi 24` matches the module's `minSdk`. Raising it there without raising `minSdk`
produces a library the app cannot load on its oldest supported devices.

## Prerequisites the build box already has

Go 1.27 at `/usr/local/go`, and `gomobile`/`gobind` in `~/go/bin`, both under the
**`kyle`** account — not root, because that is where the module cache lives
(`/home/kyle/go/pkg/mod`) and re-fetching gVisor is the expensive part.

Two things cost real time the first time and are worth not rediscovering:

- `gomobile` refuses to bind unless `golang.org/x/mobile` is in the module graph. The
  `tool` directive in `go.mod` keeps that pinned by the module rather than by whatever
  version a `go install` would have fetched today.
- `golang.zx2c4.com/wireguard/tun/netstack` exists both inside the main wireguard module
  and as a retired standalone module, which makes the import path ambiguous until an
  `exclude` directive retires one. It is in `go.mod`.

## What this package does not do

It does not discover anything, does not hold a peer address, and does not decide which
hosts are carried. The tunnel config comes from mDNS on the LAN or the DuckDNS TXT record
over the WAN; the carried set comes from the same host derivation the overlay pin already
uses. This package is only the dial.
