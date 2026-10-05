package com.nuvio.app.features.boomio

import com.nuvio.app.core.network.IPv4FirstDns
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

// `config { dns(...) }` is the only hook that reaches the OkHttp engine Ktor builds
// underneath; there is no Ktor-level DNS setting. Both clients carry it, which is what
// makes the IPTV catalogue and the companion link follow the overlay pin.
internal actual fun createBoomioHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
    }
    expectSuccess = false
    engine { config { dns(IPv4FirstDns()) } }
}

internal actual fun createBoomioWebSocketClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) { connectTimeoutMillis = 10_000 }
    install(WebSockets)
    engine { config { dns(IPv4FirstDns()) } }
}
