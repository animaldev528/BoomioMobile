package com.nuvio.app.features.boomio

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

// No engine config here: the overlay pin is an Android-only Tier 1 mechanism, so iOS
// keeps the platform resolver. The shape stays identical to the Android actual so the
// commonMain callers need no platform knowledge.
internal actual fun createBoomioHttpClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
    }
    expectSuccess = false
}

internal actual fun createBoomioWebSocketClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) { connectTimeoutMillis = 10_000 }
    install(WebSockets)
}
