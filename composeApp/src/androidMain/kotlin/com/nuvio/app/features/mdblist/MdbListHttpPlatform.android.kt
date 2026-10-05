package com.nuvio.app.features.mdblist

import io.ktor.client.HttpClient
import com.nuvio.app.core.network.IPv4FirstDns
import com.nuvio.app.core.overlay.withOverlayProxy
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

internal actual fun createMdbListHttpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
            dns(IPv4FirstDns())
            withOverlayProxy()
        }
    }
}
