package com.nuvio.app.features.boomio

import com.nuvio.app.core.mtls.withClientCertificate
import com.nuvio.app.core.network.IPv4FirstDns
import com.nuvio.app.core.overlay.withOverlayProxy
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

// `config { dns(...) }` is the only hook that reaches the OkHttp engine Ktor builds
// underneath; there is no Ktor-level DNS setting. Both clients carry it, which is what
// makes the IPTV catalogue and the companion link follow the overlay pin.
//
// ⚠️ The relay is the *other* half of that, and both are needed. The pin only rewrites
// names the app resolves itself; under the app's own userspace tunnel the pin stands down
// entirely (netstack installs no kernel route), and what carries boomio traffic then is
// this proxy. `withOverlayProxy()` is inert — it answers "direct" — whenever no relay is up.
//
// `withClientCertificate()` is the mTLS half and is applied to both for the same reason the
// proxy is: this is the plane that talks to Boomio's own hosts. It is inert until the device
// has a registered certificate, and *then* it needs P2.5's rebuild to take effect on clients
// that were built before that — see `MtlsSsl`.
internal actual fun createBoomioHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
    }
    expectSuccess = false
    engine { config { dns(IPv4FirstDns()).withOverlayProxy().withClientCertificate() } }
}

internal actual fun createBoomioWebSocketClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) { connectTimeoutMillis = 10_000 }
    install(WebSockets)
    // A `wss://` companion link reaches OkHttp as `https`, so it takes the CONNECT path
    // through the relay rather than the absolute-form one the relay refuses.
    engine { config { dns(IPv4FirstDns()).withOverlayProxy().withClientCertificate() } }
}
