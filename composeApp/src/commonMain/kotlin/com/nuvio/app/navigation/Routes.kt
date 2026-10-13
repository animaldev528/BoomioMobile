package com.nuvio.app.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey {
    val title: String?
        get() = null

    val subtitle: String?
        get() = null

    /** Full-screen destinations such as the video player keep native navigation chrome hidden. */
    val hidesNavigationBar: Boolean
        get() = false

    /** Stable enough to apply Navigation 3 launchSingleTop semantics in SwiftUI. */
    val navigationIdentity: String
        get() = toString()

    /** Lets an explicitly cross-tab route select its native SwiftUI stack. */
    val preferredTabName: String?
        get() = null
}

@Serializable
sealed interface SettingsDestinationRoute : AppRoute {
    override val preferredTabName: String
        get() = "Settings"
}

@Serializable
data object TabsRoute : AppRoute

@Serializable
data class DetailRoute(
    val type: String,
    val id: String,
    override val title: String? = null,
) : AppRoute

@Serializable
data class PersonDetailRoute(
    val personId: Int,
    val personName: String,
    val personPhoto: String? = null,
    val castAvatarTransitionKey: String? = null,
    val preferCrew: Boolean = false,
) : AppRoute {
    override val title: String
        get() = personName
}

@Serializable
data class EntityBrowseRoute(
    val entityKind: String,
    val entityId: Int,
    val entityName: String,
    val sourceType: String = "tv",
) : AppRoute {
    override val title: String
        get() = entityName
}

/** A settings leaf promoted from the former in-screen page state machine. */
@Serializable
data class SettingsPageRoute(
    val pageName: String,
    override val title: String,
) : SettingsDestinationRoute

@Serializable
data class HomescreenSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class MetaScreenSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class ContinueWatchingSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class DownloadsRoute(override val title: String = "") : AppRoute {
    override val preferredTabName: String
        get() = "Library"
}

@Serializable
data class DownloadShowRoute(
    val showId: String,
    override val title: String,
) : AppRoute

@Serializable
data class AddonsSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class CompanionRoute(override val title: String = "") : SettingsDestinationRoute

/** Signs a TV in by approving the 6-character code it is showing. */
@Serializable
data class AddTvRoute(override val title: String = "") : SettingsDestinationRoute

/**
 * Enrols a TV — gives it its own certificate and tunnel — by approving the 6-character code on
 * its setup screen.
 *
 * A separate destination from [AddTvRoute] deliberately, and not merely a second page in the
 * settings tree: it reaches a different service (bsm, not Supabase) and it is the enrolment
 * plane rather than the sign-in one. It is a route for the same reason the other flows that talk
 * to a backend are routes rather than `SettingsPage` entries.
 */
@Serializable
data class EnrollTvRoute(override val title: String = "") : SettingsDestinationRoute

/**
 * Administrator controls: every account, TV and playback limit on the box.
 *
 * A route rather than a `SettingsPage` entry for the same reason [EnrollTvRoute] is, and it carries
 * its own weight: it reaches bsm on the `/tvpair` prefix, it is GLOBAL rather than scoped to the
 * signed-in account, and it is the only destination in the app that is refused outright to most
 * profiles. The settings row that opens it is hidden for a non-admin, but the route is not a secret
 * — the refusal is server-side, and this screen renders it.
 */
@Serializable
data class AdminSettingsRoute(override val title: String = "") : SettingsDestinationRoute

/**
 * The WireGuard details this device is using — read-only, and the same for every profile.
 *
 * A route rather than a `SettingsPage` entry, for the same reason [EnrollTvRoute] is one: it
 * describes a seam that exists only against a live deployment, and the values it shows are written
 * by enrollment rather than read from Settings. Unlike [AdminSettingsRoute] it is not gated — the
 * tunnel is this device's own, so a profile that cannot administer the box can still be told how its
 * own traffic reaches it.
 */
@Serializable
data class VpnSettingsRoute(override val title: String = "") : SettingsDestinationRoute

/**
 * P5 live-TV channel picker. Reached from the companion screen rather than the
 * settings root, because sending a tune needs an ACTIVE companion session — a
 * root-level entry could be opened unpaired and would silently do nothing.
 */
@Serializable
data class IptvChannelsRoute(override val title: String = "") : SettingsDestinationRoute

/** N3 watch-party flow, launched from a title's detail screen. */
@Serializable
data class WatchPartyRoute(
    val type: String,
    val imdbId: String,
    override val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
) : AppRoute

@Serializable
data class PluginsSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class AccountSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class SupportersContributorsSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class LicensesAttributionsSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class CollectionsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class CollectionEditorRoute(
    val collectionId: String? = null,
    override val title: String = "",
) : AppRoute

@Serializable
data class CollectionEditorPageRoute(
    val collectionId: String? = null,
    val pageName: String,
    override val title: String,
) : AppRoute

@Serializable
data class FolderDetailRoute(
    val collectionId: String,
    val folderId: String,
    override val title: String = "",
) : AppRoute

@Serializable
data class StreamRoute(
    val launchId: Long,
    override val title: String = "",
) : AppRoute

@Serializable
data class CatalogRoute(
    val launchId: Long,
    override val title: String = "",
    override val subtitle: String? = null,
) : AppRoute

@Serializable
data class PlayerRoute(
    val launchId: Long,
    override val title: String = "",
) : AppRoute {
    override val hidesNavigationBar: Boolean
        get() = true
}
