package com.nuvio.app.core.network

import com.nuvio.app.core.overlay.withOverlayProxy
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

// `config { dns(...) }` is the only hook that reaches the OkHttp engine Ktor builds
// underneath; there is no Ktor-level DNS setting. This replaces supabase-kt's default
// engine — which is also OkHttp on Android, so nothing else about the client changes.
internal actual fun createSupabaseHttpEngine(): HttpClientEngine =
    OkHttp.create { config { dns(IPv4FirstDns()).withOverlayProxy() } }
