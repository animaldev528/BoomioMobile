package com.nuvio.app.core.network

import io.ktor.client.engine.HttpClientEngine

/**
 * The HTTP engine the Supabase client is built on.
 *
 * ⚠️ **This is not cosmetic.** `SupabaseProvider` is the app's spine — auth, profiles,
 * the catalogue, watch progress and Storage all ride this one client, and it builds its
 * own `HttpClient` internally. Unlike every other client in the app it therefore never
 * passes through the `IPv4FirstDns` seam. Left on supabase-kt's platform-default engine,
 * the whole app keeps resolving the server's public FQDN and keeps going to the public
 * edge on exactly the LAN Tier 1 exists for.
 *
 * Observed on device 2026-10-05: the phone was on public DNS (1.1.1.1), discovery had
 * found `192.168.68.65` and pinned it, and yet `/rest/v1/rpc/get_avatar_catalog` still
 * timed out after 10 s while **zero** requests reached the LAN edge — the pin was landing
 * on a client the app's real traffic never used.
 *
 * supabase-kt exposes `httpEngine` on its builder, so covering this needs no upstream
 * change. The engines' own defaults are preserved per platform; only the DNS is added.
 */
internal expect fun createSupabaseHttpEngine(): HttpClientEngine
