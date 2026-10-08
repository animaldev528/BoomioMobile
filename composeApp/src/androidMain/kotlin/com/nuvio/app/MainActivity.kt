package com.nuvio.app

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent

import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.core.network.ServerConfigurationStorage
import com.nuvio.app.core.mtls.MtlsRegistration
import com.nuvio.app.core.overlay.OverlayEnrollment
import com.nuvio.app.core.overlay.OverlayEndpointDiscovery
import com.nuvio.app.core.overlay.OverlayLocalDiscovery
import com.nuvio.app.core.overlay.OverlayRelay
import com.nuvio.app.core.overlay.OverlaySession
import com.nuvio.app.core.overlay.OverlayTunnel
import com.nuvio.app.features.boomio.BoomioSessionRepository
import com.nuvio.app.features.boomio.BoomioSessionStorage
import com.nuvio.app.features.boomio.PrivateListeningSession
import com.nuvio.app.core.diagnostics.SentryInitializer
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.core.sync.SyncClientIdentityStorage
import com.nuvio.app.features.addons.AddonHttpClientProvider
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.collection.CollectionMobileSettingsStorage
import com.nuvio.app.features.collection.CollectionStorage
import com.nuvio.app.features.debrid.DebridSettingsStorage
import com.nuvio.app.features.downloads.DownloadsLiveStatusPlatform
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.library.LibraryDisplaySettingsStorage
import com.nuvio.app.features.membership.MemberAssetStorage
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.details.MetaScreenSettingsStorage
import com.nuvio.app.features.home.HomeCatalogSettingsStorage
import com.nuvio.app.features.mdblist.MdbListSettingsStorage
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsStorage
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.PlayerTrackPreferenceStorage
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.SubtitleFileCache
import com.nuvio.app.features.player.PlayerPictureInPictureManager
import com.nuvio.app.features.player.PipRemoteActionReceiver
import com.nuvio.app.features.p2p.P2pSettingsStorage
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.plugins.PluginStorage
import com.nuvio.app.features.profiles.AvatarStorage
import com.nuvio.app.features.profiles.ProfilePinCacheStorage
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.app.features.details.SeasonViewModeStorage
import com.nuvio.app.features.search.DiscoverSelectionStorage
import com.nuvio.app.features.search.SearchHistoryStorage
import com.nuvio.app.features.settings.SentrySettingsStorage
import com.nuvio.app.features.settings.AppIconPlatform
import com.nuvio.app.features.settings.ThemeSettingsStorage
import com.nuvio.app.features.trakt.TraktAuthStorage
import com.nuvio.app.features.trakt.TraktCommentsStorage
import com.nuvio.app.features.trakt.TraktLibraryStorage
import com.nuvio.app.features.trakt.TraktSettingsStorage
import com.nuvio.app.features.mdblist.PlatformMdbListAuthPersistence
import com.nuvio.app.features.mdblist.PlatformMdbListSyncStorage
import com.nuvio.app.features.simkl.SimklAuthStorage
import com.nuvio.app.features.simkl.SimklSyncStorage
import com.nuvio.app.features.tmdb.TmdbSettingsStorage
import com.nuvio.app.features.updater.AndroidAppUpdaterPlatform
import com.nuvio.app.core.ui.CardDepthStyleStorage
import com.nuvio.app.core.ui.PosterCardStyleStorage
import com.nuvio.app.core.poster.CustomPosterUrlStorage
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.streams.StreamLinkCacheStorage
import com.nuvio.app.features.streams.StreamBadgeSettingsStorage
import com.nuvio.app.features.streams.BingeGroupCacheStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesStorage
import com.nuvio.app.features.watchprogress.ResumePromptStorage
import com.nuvio.app.features.watchprogress.WatchProgressStorage

open class MainActivity : AppCompatActivity() {
    private var pipRemoteActionReceiver: PipRemoteActionReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.dark(
                scrim = 0xFF020404.toInt(),
            ),
        )
        ThemeSettingsStorage.initialize(applicationContext)
        AppIconPlatform.initialize(applicationContext)
        SentrySettingsStorage.initialize(applicationContext)
        SentryInitializer.start(application)
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.color.nuvio_background)
        pipRemoteActionReceiver = PipRemoteActionReceiver.register(this)
        SyncClientIdentityStorage.initialize(applicationContext)
        AddonHttpClientProvider.initialize(applicationContext)
        AddonStorage.initialize(applicationContext)
        AuthStorage.initialize(applicationContext)
        BoomioSessionStorage.initialize(applicationContext)
        BoomioSessionRepository.initialize()
        PrivateListeningSession.initialize(applicationContext)
        ServerConfigurationStorage.initialize(applicationContext)
        // Beside the server configuration because the browse reads the host list from
        // it. This only stores the context; the browse itself is foreground-triggered.
        OverlayLocalDiscovery.initialize(applicationContext)
        // The Tier 2 sibling. It watches for a tunnel two ways — one the platform or the
        // user's WireGuard app established, and the app's *own* userspace one — and does
        // nothing at all until `BOOMIO_OVERLAY_ADDR` is set. Only the platform path pins
        // DNS; see its two-paths doc for why the other one must not.
        OverlayTunnel.initialize(applicationContext)
        // The endpoint ladder (architecture §4.4): mDNS, then `boomio-local`, then a person. It
        // is *after* the two above on purpose — rung 1 borrows `OverlayLocalDiscovery`'s browse
        // rather than opening its own, so the browse has to exist first. Like them it only
        // stores the context here; the walk is foreground- and network-triggered.
        OverlayEndpointDiscovery.initialize(applicationContext)
        // The relay every engine has to be pointed at explicitly, because a userspace
        // tunnel captures nothing on its own. Process-scoped and takes no `Context`; it
        // opens no socket at all until `BOOMIO_OVERLAY_ADDR` is set, and every client
        // behaves exactly as it does today while `OverlayRelay.state` is `Down`.
        OverlayRelay.initialize()
        // Binds the tunnel to whatever the ladder found, and hands the relay a dialler that
        // reads the tunnel's state per dial. ⚠️ Order against the line above does not
        // matter, and that is by construction rather than luck: `OverlayRelay.start` binds
        // its diallers once per process, so a tunnel reference captured at start-up would
        // freeze the state at `Down` forever. `DeferredTunnelDialer` is what makes the
        // relay safe to start first — and it starts before the ladder has walked anything.
        OverlaySession.initialize(applicationContext)
        // Last of the overlay group, and it has to be: it needs the keypair the tunnel
        // created (it enrols *as* that public key), the ladder it nudges via `offerManual`
        // once an address arrives, and the companion session it authenticates with. This is
        // the piece that ends the build-time constants — before it, a device's own overlay
        // address (`BOOMIO_OVERLAY_LOCAL_CIDR`) was fixed at compile time and the second
        // client on an overlay could never be right.
        OverlayEnrollment.initialize(applicationContext)
        // The client certificate half of the mTLS plane: it mints a self-signed certificate for
        // the name the server assigned this device and registers it. It reads the name
        // `OverlayEnrollment` just applied, so it comes after it — and it *fires* from
        // `OverlayEnrollment.writeConfig`, which makes the ordering a dependency rather than a
        // preference.
        //
        // ⚠️ Note that `initialize` itself registers nothing. `writeConfig` runs synchronously
        // inside `OverlayEnrollment.initialize` above, so its registration request was made before
        // this call and was correctly dropped as "not started yet". The first real attempt comes
        // from the enrollment refresh on its own coroutine — which is the right trigger anyway, and
        // costs nothing when it does not happen: a device that was already registered needs no
        // second POST (the plan short-circuits), and one that was not will be re-triggered the
        // moment a refresh succeeds.
        MtlsRegistration.initialize(applicationContext)
        LibraryStorage.initialize(applicationContext)
        WatchedStorage.initialize(applicationContext)
        MetaScreenSettingsStorage.initialize(applicationContext)
        com.nuvio.app.features.shuffle.EpisodeShuffleStorage.initialize(applicationContext)
        HomeCatalogSettingsStorage.initialize(applicationContext)
        PlayerSettingsStorage.initialize(applicationContext)
        PlayerTrackPreferenceStorage.initialize(applicationContext)
        P2pSettingsStorage.initialize(applicationContext)
        P2pStreamingEngine.initialize(applicationContext)
        ExternalPlayerPlatform.initialize(applicationContext)
        SubtitleFileCache.initialize(applicationContext)
        ProfileStorage.initialize(applicationContext)
        AvatarStorage.initialize(applicationContext)
        ProfilePinCacheStorage.initialize(applicationContext)
        MemberAssetStorage.initialize(applicationContext)
        DiscoverSelectionStorage.initialize(applicationContext)
        SearchHistoryStorage.initialize(applicationContext)
        SeasonViewModeStorage.initialize(applicationContext)
        PosterCardStyleStorage.initialize(applicationContext)
        CustomPosterUrlStorage.initialize(applicationContext)
        CardDepthStyleStorage.initialize(applicationContext)
        DebridSettingsStorage.initialize(applicationContext)
        TmdbSettingsStorage.initialize(applicationContext)
        MdbListSettingsStorage.initialize(applicationContext)
        TraktAuthStorage.initialize(applicationContext)
        TraktCommentsStorage.initialize(applicationContext)
        TraktLibraryStorage.initialize(applicationContext)
        TraktSettingsStorage.initialize(applicationContext)
        PlatformMdbListAuthPersistence.initialize(applicationContext)
        PlatformMdbListSyncStorage.initialize(applicationContext)
        SimklAuthStorage.initialize(applicationContext)
        SimklSyncStorage.initialize(applicationContext)
        LibraryDisplaySettingsStorage.initialize(applicationContext)
        ContinueWatchingPreferencesStorage.initialize(applicationContext)
        ResumePromptStorage.initialize(applicationContext)
        ContinueWatchingEnrichmentStorage.initialize(applicationContext)
        EpisodeReleaseNotificationsStorage.initialize(applicationContext)
        WatchProgressStorage.initialize(applicationContext)
        StreamLinkCacheStorage.initialize(applicationContext)
        StreamBadgeSettingsStorage.initialize(applicationContext)
        BingeGroupCacheStorage.initialize(applicationContext)
        PluginStorage.initialize(applicationContext)
        CollectionMobileSettingsStorage.initialize(applicationContext)
        CollectionStorage.initialize(applicationContext)
        DownloadsStorage.initialize(applicationContext)
        DownloadsPlatformDownloader.initialize(applicationContext)
        DownloadsLiveStatusPlatform.initialize(applicationContext)
        AndroidAppUpdaterPlatform.initialize(applicationContext)
        PlatformLocalAccountDataCleaner.initialize(applicationContext)
        EpisodeReleaseNotificationPlatform.initialize(applicationContext)
        EpisodeReleaseNotificationPlatform.bindActivity(this)
        handleIncomingAppIntent(intent)

        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingAppIntent(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PlayerPictureInPictureManager.onUserLeaveHint(this)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlayerPictureInPictureManager.onPictureInPictureModeChanged(this, isInPictureInPictureMode)
    }

    override fun onDestroy() {
        EpisodeReleaseNotificationPlatform.unbindActivity(this)
        val receiver = pipRemoteActionReceiver
        if (receiver != null) {
            runCatching { unregisterReceiver(receiver) }
            pipRemoteActionReceiver = null
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        if (EpisodeReleaseNotificationPlatform.handlePermissionRequestResult(requestCode, grantResults)) {
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun handleIncomingAppIntent(intent: Intent?) {
        val appUrl = intent?.dataString?.trim().orEmpty()
        if (appUrl.isBlank()) return
        handleAppUrl(appUrl)
    }
}
