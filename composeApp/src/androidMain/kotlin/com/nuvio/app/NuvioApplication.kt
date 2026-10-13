package com.nuvio.app

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor
import com.nuvio.app.features.tracking.TrackerCredentialsStore

/**
 * Custom Application class that implements [SingletonImageLoader.Factory] to guarantee
 * the Coil ImageLoader (with [CustomPosterFallbackInterceptor]) is created before any
 * image request. Without this, Coil's auto-init ContentProvider creates the singleton
 * before MainActivity.onCreate, and interceptors registered later are ignored.
 */
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    /**
     * ⚠️ **The earliest hook in the process, and the tracker ids need one this early.**
     *
     * [TrackerCredentialsStore] overwrites the generated `TraktConfig.CLIENT_ID` and friends, and the
     * first read of those can happen at *object initialization* rather than at a call site:
     * `MdbListTracker` builds its configuration from `MdbListConfig.CLIENT_ID` the moment the object is
     * touched, and it is touched from `ensureTrackingProvidersRegistered()`, which is reachable from
     * several repositories. An `onCreate` in the activity would be early enough for every caller that
     * exists today, but "enough today" is not a property to rely on for a value that has to be right
     * before the first read — this runs before any activity, any deep link and any composition, so the
     * ordering is true by construction.
     *
     * Every other `initialize` in the app lives in `MainActivity.onCreate`; this one is the exception,
     * and the note there says so.
     */
    override fun onCreate() {
        super.onCreate()
        // Synchronous, and it must stay that way: it applies the ids a previous launch cached, so a
        // cold start off-network is already correct. The network half is `onAppForegrounded`, which
        // cannot run before there is a service origin to dial — see the store.
        TrackerCredentialsStore.initialize(this)
        // Also synchronous, and also ordering-critical: enrolment sends this to bsm so a
        // re-enrolling device lands back on its existing row, and a pairing request can be built
        // before any activity has run. Reads ANDROID_ID — cheap, no I/O.
        com.nuvio.app.core.device.DeviceHardwareFingerprint.initialize(this)
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .components {
                add(CustomPosterFallbackInterceptor())
                add(SvgDecoder.Factory())
                add(
                    KtorNetworkFetcherFactory(
                        cacheStrategy = { CacheControlCacheStrategy() },
                    ),
                )
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
}
