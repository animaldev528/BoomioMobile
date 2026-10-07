package com.nuvio.app.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

// No engine config: the overlay pin is an Android-only Tier 1 mechanism, so iOS keeps
// supabase-kt's platform resolver. The shape stays identical to the Android actual so
// commonMain needs no platform knowledge.
internal actual fun createSupabaseHttpEngine(): HttpClientEngine = Darwin.create { }
