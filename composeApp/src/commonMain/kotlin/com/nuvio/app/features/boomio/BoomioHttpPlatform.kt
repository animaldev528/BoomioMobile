package com.nuvio.app.features.boomio

import io.ktor.client.HttpClient

/**
 * The Ktor clients the boomio feature uses to reach its own server.
 *
 * These must come from here rather than a bare `HttpClient { }`. A bare client takes
 * the platform-default resolver, which knows nothing about the overlay pin — so the
 * IPTV catalogue and the companion session would keep going to the public edge on a
 * LAN whose DNS does not point at the server. That LAN is the whole reason Tier 1
 * exists, so routing these through the platform factory is not optional.
 *
 * The timeouts live in each actual rather than being passed in: every caller wants
 * the same ones, and keeping them here means there is one place to change them.
 */
internal expect fun createBoomioHttpClient(): HttpClient

/**
 * The companion `/ws/phone` socket.
 *
 * Deliberately separate from [createBoomioHttpClient]: this connection is long-lived,
 * so it takes **only** a connect timeout — a request or socket timeout would kill an
 * idle socket while the 10s heartbeat is quiet. It also installs `WebSockets`, without
 * which every connect throws and the companion link never opens.
 */
internal expect fun createBoomioWebSocketClient(): HttpClient
