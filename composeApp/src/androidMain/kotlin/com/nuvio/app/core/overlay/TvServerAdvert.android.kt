package com.nuvio.app.core.overlay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "TvServerAdvert"

/**
 * The service type the server itself publishes, so the TV's existing browse finds this advert
 * without knowing it is a phone.
 */
private const val SERVICE_TYPE = "_boomio-overlay._udp"

/** Shown in NSD browsers. The TV never reads it; DnsSd instance names are just labels. */
private const val SERVICE_NAME = "Boomio overlay"

internal actual object TvServerAdvert {

    private var appContext: Context? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    /**
     * Stores the context. Called from `MainActivity` beside the other overlay initializers, for
     * the same reason they are there: NSD needs an application context and the screens that use
     * this are composables.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun availability(): TvAdvertAvailability {
        val endpoint = OverlayEndpointDiscovery.current() ?: return TvAdvertAvailability.NotConfigured
        if (payloadOrNull(endpoint) == null) return TvAdvertAvailability.NotConfigured
        // At home `current` is the LAN address. There is nothing to share: the server's own
        // advert is already answering on this wire.
        if (endpoint.host.isPrivateAddress()) return TvAdvertAvailability.AlreadyOnServerLan
        return TvAdvertAvailability.Ready
    }

    actual suspend fun start(): TvAdvertResult {
        val context = appContext ?: return TvAdvertResult.Failed("advert is not initialized")
        val endpoint = OverlayEndpointDiscovery.current() ?: return TvAdvertResult.NotConfigured
        val payload = payloadOrNull(endpoint) ?: return TvAdvertResult.NotConfigured
        if (endpoint.host.isPrivateAddress()) return TvAdvertResult.AlreadyOnServerLan

        stop()

        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return TvAdvertResult.Failed("mDNS is unavailable on this device")

        val info = NsdServiceInfo().apply {
            // ⚠️ The SRV target is this phone, and no API lets us change it. That is why the
            // payload must name `wan=`: the TV's ladder falls through to the published plane
            // instead of dialling the phone. `port` is the seed port the fallback reuses, so it
            // must be the server's tunnel port, not the phone's.
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = payload.wanPort
            setAttribute("v", "2")
            setAttribute("svc", payload.serviceName)
            setAttribute("wan", payload.wanAddress)
            setAttribute("wanport", payload.wanPort.toString())
            setAttribute("port", payload.wanPort.toString())
            setAttribute("pk", payload.serverPublicKey)
        }

        return suspendCancellableCoroutine { continuation ->
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "Advertising '${info.serviceName}' at ${payload.wanAddress}:${payload.wanPort}")
                    if (continuation.isActive) continuation.resume(TvAdvertResult.Started)
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Registration failed for '${info.serviceName}': code=$errorCode")
                    nsd = null
                    registration = null
                    if (continuation.isActive) {
                        continuation.resume(TvAdvertResult.Failed("registration failed ($errorCode)"))
                    }
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.i(TAG, "Stopped advertising '${info.serviceName}'")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Unregistration failed for '${info.serviceName}': code=$errorCode")
                }
            }

            nsd = manager
            registration = listener
            runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure {
                    Log.w(TAG, "registerService threw", it)
                    nsd = null
                    registration = null
                    if (continuation.isActive) continuation.resume(TvAdvertResult.Failed(it.message))
                }
        }
    }

    actual fun stop() {
        val manager = nsd
        val listener = registration
        nsd = null
        registration = null
        if (manager == null || listener == null) return
        runCatching { manager.unregisterService(listener) }
            .onFailure { Log.w(TAG, "unregisterService failed", it) }
    }

    /**
     * The server this phone can actually hand over, or null when it does not hold enough to be
     * useful.
     *
     * ⚠️ **`svc` is required, not optional.** Every URL and certificate check on the TV stays on
     * that name; an address alone would fail TLS. A phone that reached the server by typing a
     * bare IP has no name to share, and is honestly reported as not configured.
     */
    private fun payloadOrNull(endpoint: OverlayEndpoint): TvAdvertPayload? {
        val key = endpoint.serverPublicKeyBase64?.takeIf { it.isNotBlank() } ?: return null
        val targets = OverlayEndpointDiscovery.publishedTargets() ?: return null
        val serviceName = targets.svcName ?: return null
        val wan = targets.inOrder().firstOrNull { !it.isLan } ?: return null
        return TvAdvertPayload(
            serviceName = serviceName,
            wanAddress = wan.host,
            wanPort = wan.tunnelPort,
            serverPublicKey = key,
        )
    }

    private fun String.isPrivateAddress(): Boolean {
        val parts = split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 169 && parts[1] == 254)
    }
}
