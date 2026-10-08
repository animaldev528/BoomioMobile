package com.nuvio.app.core.network

import com.nuvio.app.core.mtls.withClientCertificate
import com.nuvio.app.core.overlay.withOverlayProxy
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

// `config { dns(...) }` is the only hook that reaches the OkHttp engine Ktor builds
// underneath; there is no Ktor-level DNS setting. This replaces supabase-kt's default
// engine — which is also OkHttp on Android, so nothing else about the client changes.
//
// `withClientCertificate()` rides along because auth is the one call that must work on the
// enforced plane: a device that cannot present its certificate cannot sign in, and a sign-in
// that silently falls back to a certless handshake fails with a TLS error the user reads as
// "the wrong password".
internal actual fun createSupabaseHttpEngine(): HttpClientEngine =
    OkHttp.create { config { dns(IPv4FirstDns()).withOverlayProxy().withClientCertificate() } }
