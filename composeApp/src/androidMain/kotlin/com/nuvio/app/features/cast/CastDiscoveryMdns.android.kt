package com.nuvio.app.features.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * The app-scoped `Context` that mDNS discovery needs.
 *
 * Discovery is `expect`/`actual` with no `Context` in the signature — the caller
 * is common UI — but `NsdManager` is a system service and has to come from an
 * `Application`. This follows the same shape as
 * [com.nuvio.app.core.device.DeviceHardwareFingerprint]: one object, initialised
 * once from [com.nuvio.app.NuvioApplication], so no screen has to thread a
 * `Context` through a cast call.
 */
internal object CastAndroidContext {
    @Volatile
    private var appContext: Context? = null

    /** Called once from [com.nuvio.app.NuvioApplication.onCreate]. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    val context: Context? get() = appContext
}

private val log = Logger.withTag("CastDiscoveryMdns")

/**
 * Finds Google Cast and AirPlay renderers with the platform's mDNS resolver.
 *
 * These two protocols have no SSDP footprint worth using. Chromecast answers
 * `_googlecast._tcp` with a TXT record carrying `fn` (the name on the label),
 * `md` (the model the capability table keys on) and `id`; AirPlay answers
 * `_airplay._tcp` with `model` and `deviceid`. Both give us `host:port`, which is
 * the only thing playback needs.
 *
 * ⚠️ **Every `NsdManager` call happens on the main looper.** `NsdManager` builds
 * its callback `Handler` from the constructing thread's `Looper`, and
 * `getSystemService(NSD_SERVICE)` constructs it — so asking for it from the IO
 * pool would throw "Can't create handler inside thread that has not called
 * Looper.prepare()". Callbacks therefore also arrive on main, which is why the
 * resolve loop lives here rather than on a worker.
 *
 * ⚠️ **Resolves are serialised.** Only one `resolveService` may be in flight at a
 * time (`FAILURE_ALREADY_ACTIVE` otherwise), and a `ResolveListener` instance
 * may not be reused. So discovery callbacks only enqueue; one loop drains the
 * queue, one resolve at a time, each bounded.
 */
internal object CastMdns {

    private const val CAST_SERVICE = "_googlecast._tcp."
    private const val AIRPLAY_SERVICE = "_airplay._tcp."
    private const val RESOLVE_TIMEOUT_MS = 1200L
    private const val DRAIN_TIMEOUT_MS = 1500L

    /**
     * One bounded mDNS scan. Never throws: no context, no service, or a resolver
     * that refuses all degrade to an empty list, and the caller shows the devices
     * it got from SSDP.
     */
    suspend fun discover(timeoutMillis: Long): List<CastDevice> {
        val context = CastAndroidContext.context ?: return emptyList()
        // ⚠️ `getSystemService` constructs `NsdManager`, and its constructor
        // builds a Handler on the calling thread's Looper — so this has to
        // happen on main, not on the IO thread that asked for a scan.
        return withContext(Dispatchers.Main.immediate) {
            val nsd = runCatching {
                context.getSystemService(Context.NSD_SERVICE) as NsdManager
            }.getOrNull() ?: return@withContext emptyList()
            scan(context, nsd, timeoutMillis)
        }
    }

    private suspend fun scan(
        context: Context,
        nsd: NsdManager,
        timeoutMillis: Long,
    ): List<CastDevice> = coroutineScope {
        val lock = acquireMulticastLock(context)
        val devices = ConcurrentHashMap<String, CastDevice>()
        val seen = ConcurrentHashMap.newKeySet<String>()
        val queue = Channel<Pair<CastProtocol, NsdServiceInfo>>(Channel.UNLIMITED)
        val listeners = listOf(
            CAST_SERVICE to CastProtocol.CAST,
            AIRPLAY_SERVICE to CastProtocol.AIRPLAY,
        ).mapNotNull { (serviceType, protocol) ->
            startDiscovery(nsd, serviceType, protocol, seen, queue)
        }

        val resolver = launch { resolveLoop(nsd, queue, devices) }
        try {
            delay(timeoutMillis)
        } finally {
            // Stop first so late finds do not extend the queue, then let the
            // resolves already in flight finish inside a bounded drain.
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            queue.close()
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) { resolver.join() } ?: resolver.cancel()
            runCatching { lock?.release() }
        }
        log.d { "mDNS found ${devices.size}: ${devices.values.joinToString { "${it.protocol}/${it.name}@${it.ip}:${it.port}" }}" }
        devices.values.toList()
    }

    /**
     * mDNS traffic is multicast, and a phone in power-save drops it unless it
     * holds the lock. Best-effort: a device without the permission still works,
     * just less reliably.
     */
    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("boomio-cast-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.onFailure { log.w { "multicast lock unavailable: ${it.message}" } }.getOrNull()

    private fun startDiscovery(
        nsd: NsdManager,
        serviceType: String,
        protocol: CastProtocol,
        seen: MutableSet<String>,
        queue: SendChannel<Pair<CastProtocol, NsdServiceInfo>>,
    ): NsdManager.DiscoveryListener? {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                log.d { "mDNS discovering $regType" }
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                val reported = service.serviceType ?: return
                if (!reported.contains(serviceType.trimEnd('.'))) return
                if (!seen.add("$serviceType|${service.serviceName}")) return
                queue.trySend(protocol to service)
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(regType: String) = Unit

            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                log.w { "mDNS discovery of $regType failed: $errorCode" }
            }

            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                log.w { "mDNS stop of $regType failed: $errorCode" }
            }
        }
        return try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            listener
        } catch (error: Exception) {
            log.w { "mDNS discovery of $serviceType could not start: ${error.message}" }
            null
        }
    }

    private suspend fun resolveLoop(
        nsd: NsdManager,
        queue: ReceiveChannel<Pair<CastProtocol, NsdServiceInfo>>,
        devices: MutableMap<String, CastDevice>,
    ) {
        for ((protocol, service) in queue) {
            val resolved = resolve(nsd, service) ?: continue
            val device = toDevice(protocol, resolved)
            if (device != null) {
                devices[device.id] = device
                log.d { "mDNS resolved ${device.protocol}/${device.name} at ${device.ip}:${device.port}" }
            }
        }
    }

    private suspend fun resolve(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    // Deprecated on API 34+ in favour of a callback registration,
                    // but still the only shape that works on every API the app
                    // supports (24+), and it returns the same NsdServiceInfo.
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (continuation.isActive) continuation.resume(serviceInfo)
                    }
                }
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(service, listener)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    private fun toDevice(protocol: CastProtocol, info: NsdServiceInfo): CastDevice? {
        // The house is IPv4-only. An IPv6-only renderer would be reachable in
        // theory, but the stream URL the phone hands over is not, so it is not a
        // target we can serve.
        //
        // `NsdServiceInfo.host` rather than `hostAddresses`: the latter only
        // exists on API 34+ and would throw NoSuchMethodError on the phones this
        // app still supports (minSdk 24).
        @Suppress("DEPRECATION")
        val ip = info.host?.takeIf { it is Inet4Address }?.hostAddress ?: return null
        val txt = info.attributes.mapValues { (_, value) -> String(value, Charsets.UTF_8) }
        val serviceName = info.serviceName.substringBefore("._").ifBlank { ip }
        return when (protocol) {
            CastProtocol.CAST -> CastModelCatalog.device(
                protocol = CastProtocol.CAST,
                ip = ip,
                // `fn` is the name the owner typed ("Living Room"); the service
                // name is the factory label ("Chromecast-3f2a").
                name = txt["fn"]?.takeIf { it.isNotBlank() } ?: serviceName,
                port = info.port.takeIf { it > 0 } ?: 8009,
                facts = txt,
                md = txt["md"],
            )

            CastProtocol.AIRPLAY -> CastModelCatalog.device(
                protocol = CastProtocol.AIRPLAY,
                ip = ip,
                name = serviceName,
                port = info.port.takeIf { it > 0 } ?: 7000,
                facts = txt,
                md = txt["model"],
            )

            else -> null
        }
    }
}
