package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.settingsRootContent(
    isTablet: Boolean,
    onPageClick: (SettingsPage) -> Unit,
    onSwitchProfileClick: (() -> Unit)? = null,
    onWatchPartyClick: (() -> Unit)? = null,
    category: SettingsCategory? = null,
    // Boomio's own rows. They have no `SettingsPage` of their own: the backend rows are dialogs
    // hosted by this screen, and account administration is a role bsm answers for.
    onCompanionClick: () -> Unit = {},
    onAddTvClick: () -> Unit = {},
    onEnrollTvClick: () -> Unit = {},
    onAdminClick: (() -> Unit)? = null,
    onVpnClick: () -> Unit = {},
) {
    SettingsCategory.entries.filter { category == null || it == category }.forEach { group ->
        item(key = group.name) {
            SettingsGroup(isTablet = isTablet) {
                when (group) {
                    SettingsCategory.You -> {
                        SettingsPageRow(
                            page = SettingsPage.Account,
                            description = Res.string.compose_settings_root_account_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                        if (onSwitchProfileClick != null) {
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsNavigationRow(
                                title = stringResource(Res.string.compose_settings_root_switch_profile_title),
                                description = stringResource(Res.string.compose_settings_root_switch_profile_description),
                                icon = Icons.Default.People,
                                isTablet = isTablet,
                                onClick = onSwitchProfileClick,
                            )
                        }
                        if (onWatchPartyClick != null) {
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsNavigationRow(
                                title = stringResource(Res.string.party_title),
                                description = stringResource(Res.string.party_settings_description),
                                icon = Icons.Rounded.Groups,
                                isTablet = isTablet,
                                onClick = onWatchPartyClick,
                            )
                        }
                        // Ordered the way the two things actually happen: a TV gets its *device
                        // identity* (certificate + tunnel) first, and only then can it hold a *user
                        // session*. Same icon on purpose — the subject is the same TV; the words are
                        // what tell them apart.
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.compose_settings_page_enroll_tv),
                            description = stringResource(Res.string.compose_settings_root_enroll_tv_description),
                            icon = Icons.Rounded.Tv,
                            isTablet = isTablet,
                            onClick = onEnrollTvClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.compose_settings_page_add_tv),
                            description = stringResource(Res.string.compose_settings_root_add_tv_description),
                            icon = Icons.Rounded.Tv,
                            isTablet = isTablet,
                            onClick = onAddTvClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        // Sits with the TV rows rather than in General, because it answers the same
                        // kind of question they do: *how this device reaches the box*. Unlike them it
                        // is not gated — the tunnel is this device's own, so a profile that cannot
                        // administer anything can still be told what its own traffic is doing.
                        SettingsNavigationRow(
                            title = stringResource(Res.string.compose_settings_page_vpn),
                            description = stringResource(Res.string.compose_settings_root_vpn_description),
                            icon = Icons.Rounded.VpnKey,
                            isTablet = isTablet,
                            onClick = onVpnClick,
                        )
                        // Gated on the callback being present, exactly as the check-for-updates row is
                        // — and here the null is load-bearing rather than cosmetic: admin is a role
                        // that exists only in bsm, so a profile that does not hold it has no such
                        // screen to open. A Boolean would read as "the feature is off"; a null
                        // callback says "this person is not an administrator", which is the actual
                        // fact.
                        if (onAdminClick != null) {
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsNavigationRow(
                                title = stringResource(Res.string.compose_settings_page_admin),
                                description = stringResource(Res.string.compose_settings_root_admin_description),
                                icon = Icons.Rounded.AdminPanelSettings,
                                isTablet = isTablet,
                                onClick = onAdminClick,
                            )
                        }
                    }
                    SettingsCategory.Look -> {
                        SettingsPageRow(
                            page = SettingsPage.Appearance,
                            description = Res.string.settings_appearance_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsPageRow(
                            page = SettingsPage.Layout,
                            description = Res.string.compose_settings_root_layout_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                    }
                    SettingsCategory.Watch -> {
                        SettingsPageRow(
                            page = SettingsPage.ContentDiscovery,
                            description = Res.string.compose_settings_root_content_discovery_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsPageRow(
                            page = SettingsPage.Playback,
                            description = Res.string.settings_playback_subtitle,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                    }
                    SettingsCategory.Services -> {
                        SettingsPageRow(
                            page = SettingsPage.Integrations,
                            description = Res.string.compose_settings_root_integrations_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.compose_settings_page_companion),
                            description = stringResource(Res.string.compose_settings_root_companion_description),
                            icon = Icons.Rounded.Cast,
                            isTablet = isTablet,
                            onClick = onCompanionClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsPageRow(
                            page = SettingsPage.TraktAuthentication,
                            description = Res.string.compose_settings_root_tracking_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                    }
                    SettingsCategory.System -> {
                        SettingsPageRow(
                            page = SettingsPage.Notifications,
                            description = Res.string.compose_settings_root_notifications_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsPageRow(
                            page = SettingsPage.Advanced,
                            description = Res.string.compose_settings_root_advanced_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsPageRow(
                            page = SettingsPage.About,
                            description = Res.string.compose_settings_root_about_description,
                            isTablet = isTablet,
                            onPageClick = onPageClick,
                        )
                    }
                }
            }
        }
    }
    settingsFooterContent(isTablet = isTablet)
}

@Composable
private fun SettingsPageRow(
    page: SettingsPage,
    description: StringResource,
    isTablet: Boolean,
    onPageClick: (SettingsPage) -> Unit,
) {
    SettingsNavigationRow(
        title = stringResource(page.titleRes),
        description = stringResource(description),
        icon = page.icon,
        isTablet = isTablet,
        onClick = { onPageClick(page) },
    )
}
