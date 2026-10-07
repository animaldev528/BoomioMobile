package com.nuvio.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.DeviceSessionRegistration
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.ui.NativeProfileSwitcherController
import com.nuvio.app.core.ui.NativeTabBridge
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.auth.AuthScreen
import com.nuvio.app.features.boomio.BoomioSessionRepository
import com.nuvio.app.features.boomio.DeviceSetupGate
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileEditScreen
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSelectionScreen
import com.nuvio.app.features.profiles.profileAvatarImageUrl
import com.nuvio.app.navigation.AppRoute

private enum class AppGateScreen {
    Loading,
    Auth,

    /**
     * The first-run device setup step, shown before [Auth] on a device that has never paired.
     *
     * It is a **separate screen rather than a section of the sign-in screen** because it is a
     * precondition for signing in, not part of it: an unpaired device has no address to reach the
     * server on, so it has nothing to sign in *to* yet.
     */
    DeviceSetup,
    ProfileSelection,
    ProfileEdit,
    Main,
}

@Composable
internal fun AppGate(
    initialTab: AppScreenTab,
    initialRoute: AppRoute,
    useNativeNavigation: Boolean,
    useNativeTabBar: Boolean,
    useTabletFloatingTabBar: Boolean,
    ownsAppRuntime: Boolean,
    bypassAppGate: Boolean,
    renderMainContent: Boolean,
    onNavigate: ((AppRoute, launchSingleTop: Boolean) -> Unit)?,
    onGoBack: (() -> Unit)?,
    onReplace: ((AppRoute) -> Unit)?,
    onActivate: ((AppScreenTab) -> Unit)?,
    onAppReady: ((Boolean) -> Unit)?,
    onMainContentMountChanged: ((Boolean) -> Unit)?,
    onMainContentVisibleChanged: ((Boolean) -> Unit)?,
    onTabTitles: ((home: String, search: String, library: String, profile: String, switchProfile: String, addProfile: String) -> Unit)?,
    nativeProfileSwitcherController: NativeProfileSwitcherController?,
    appGateController: AppGateController?,
) {
    if (bypassAppGate) {
        MainAppContent(
            initialTab = initialTab,
            initialRoute = initialRoute,
            useNativeNavigation = useNativeNavigation,
            useNativeTabBar = useNativeTabBar,
            useTabletFloatingTabBar = useTabletFloatingTabBar,
            ownsAppRuntime = ownsAppRuntime,
            showLaunchOverlay = appGateController == null,
            onNavigate = onNavigate,
            onGoBack = onGoBack,
            onReplace = onReplace,
            onActivate = onActivate,
            onTabTitles = onTabTitles,
            appGateController = appGateController,
            onRootContentReady = appGateController?.let { controller ->
                controller::reportMainContentReady
            },
            onSwitchProfile = appGateController?.let { controller ->
                controller::requestProfileSelection
            } ?: {},
        )
        return
    }

    LaunchedEffect(Unit) {
        if (!ownsAppRuntime) return@LaunchedEffect
        AuthRepository.initialize()
    }

    LaunchedEffect(Unit) {
        if (!ownsAppRuntime) return@LaunchedEffect
        NetworkStatusRepository.ensureStarted()
        MemberAccessRepository.ensureStarted()
        ProfileRepository.loadCachedProfiles()
        AvatarRepository.fetchAvatars()
    }

    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val profileAvatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val networkStatusUiState by remember {
        NetworkStatusRepository.uiState
    }.collectAsStateWithLifecycle()

    LaunchedEffect(authState) {
        if (!ownsAppRuntime) return@LaunchedEffect
        DeviceSessionRegistration.registerIfAuthenticated(force = true)
    }

    LaunchedEffect(
        profileState.activeProfile?.profileIndex,
        profileState.activeProfile?.name,
        profileState.activeProfile?.avatarColorHex,
        profileState.activeProfile?.avatarId,
        profileState.activeProfile?.avatarUrl,
        profileAvatars,
    ) {
        val activeProfile = profileState.activeProfile
        val avatarItem = activeProfile?.avatarId?.let { avatarId ->
            profileAvatars.find { it.id == avatarId }
        }
        NativeTabBridge.publishProfileTabIcon(
            name = activeProfile?.name,
            avatarColorHex = activeProfile?.avatarColorHex,
            avatarImageUrl = activeProfile?.let { profileAvatarImageUrl(it, avatarItem) },
            avatarBackgroundColorHex = avatarItem?.bgColor,
        )
    }

    var gateScreen by rememberSaveable { mutableStateOf(AppGateScreen.Loading.name) }

    /**
     * The boomio session, hydrated from disk by `BoomioSessionRepository.initialize()`.
     *
     * Non-null is exactly "this device has paired before", which is the question the setup step
     * asks. A device that paired on an earlier run skips the step entirely and goes straight to
     * the sign-in screen it already had — this must not re-ask on every launch.
     */
    val boomioSession by BoomioSessionRepository.session.collectAsStateWithLifecycle()

    /**
     * ⚠️ Saveable rather than plain `remember`: rotating the device mid-setup would otherwise
     * discard the skip and drop the user back onto the screen they had just dismissed. This is
     * the escape hatch for anyone running against a server that does not do boomio pairing at
     * all, so losing it is not cosmetic.
     */
    var deviceSetupSkipped by rememberSaveable { mutableStateOf(false) }

    val needsDeviceSetup = boomioSession == null && !deviceSetupSkipped
    var editingProfile by remember { mutableStateOf<NuvioProfile?>(null) }
    var autoSkipProfileSelection by rememberSaveable { mutableStateOf(false) }
    var profileSelectionLoading by rememberSaveable { mutableStateOf(false) }
    var profileSelectionTransitionActive by rememberSaveable { mutableStateOf(false) }
    var skipProfileSelectionEnterAnimation by remember { mutableStateOf(false) }
    var mainContentStarted by rememberSaveable { mutableStateOf(false) }
    val externalMainContentReady = if (!renderMainContent && appGateController != null) {
        val ready by appGateController.mainContentReady.collectAsStateWithLifecycle()
        ready
    } else {
        false
    }

    LaunchedEffect(gateScreen, onAppReady) {
        if (gateScreen != AppGateScreen.Main.name) {
            onAppReady?.invoke(false)
        }
    }

    LaunchedEffect(gateScreen, renderMainContent, onMainContentMountChanged) {
        if (renderMainContent) return@LaunchedEffect
        when (gateScreen) {
            AppGateScreen.Main.name -> {
                mainContentStarted = true
                onMainContentMountChanged?.invoke(true)
            }
            AppGateScreen.Loading.name,
            AppGateScreen.Auth.name,
            -> {
                mainContentStarted = false
                appGateController?.reportMainContentReady(false)
                onMainContentMountChanged?.invoke(false)
            }
            else -> onMainContentMountChanged?.invoke(mainContentStarted)
        }
    }

    LaunchedEffect(appGateController, renderMainContent) {
        if (renderMainContent) return@LaunchedEffect
        appGateController?.profileSelectionRequests?.collect {
            autoSkipProfileSelection = false
            profileSelectionLoading = false
            profileSelectionTransitionActive = false
            skipProfileSelectionEnterAnimation = true
            gateScreen = AppGateScreen.ProfileSelection.name
        }
    }

    LaunchedEffect(nativeProfileSwitcherController, appGateController, renderMainContent) {
        if (renderMainContent || appGateController == null) return@LaunchedEffect
        nativeProfileSwitcherController?.requestedManageProfiles?.collect {
            appGateController.requestProfileSelection()
        }
    }

    LaunchedEffect(nativeProfileSwitcherController, appGateController, renderMainContent) {
        if (renderMainContent || appGateController == null) return@LaunchedEffect
        nativeProfileSwitcherController?.selectedProfileIndices?.collect { profileIndex ->
            if (profileIndex == ProfileRepository.state.value.activeProfile?.profileIndex) return@collect
            val profile = ProfileRepository.state.value.profiles
                .firstOrNull { it.profileIndex == profileIndex }
                ?: return@collect
            autoSkipProfileSelection = false
            profileSelectionLoading = true
            profileSelectionTransitionActive = true
            skipProfileSelectionEnterAnimation = true
            appGateController.beginContentReload()
            ProfileRepository.selectProfile(profile.profileIndex)
            SyncManager.pullAllForProfile(profile.profileIndex)
            gateScreen = AppGateScreen.Main.name
            onActivate?.invoke(AppScreenTab.Home)
        }
    }

    LaunchedEffect(externalMainContentReady, renderMainContent) {
        if (!renderMainContent && externalMainContentReady) {
            profileSelectionLoading = false
        }
    }

    LaunchedEffect(
        renderMainContent,
        gateScreen,
        externalMainContentReady,
        onMainContentVisibleChanged,
    ) {
        if (!renderMainContent) {
            onMainContentVisibleChanged?.invoke(
                gateScreen == AppGateScreen.Main.name && externalMainContentReady,
            )
        }
    }

    fun rememberedStartupProfile(profiles: List<NuvioProfile>): NuvioProfile? {
        val currentProfileState = ProfileRepository.state.value
        if (
            !currentProfileState.rememberLastProfileEnabled ||
            !currentProfileState.hasEverSelectedProfile
        ) {
            return null
        }

        return profiles
            .find { it.profileIndex == ProfileRepository.activeProfileId }
            ?.takeUnless { it.pinEnabled }
    }

    fun selectProfile(profile: NuvioProfile, sync: Boolean) {
        if (!renderMainContent) {
            appGateController?.beginContentReload()
        }
        ProfileRepository.selectProfile(profile.profileIndex)
        if (sync) {
            SyncManager.pullAllForProfile(profile.profileIndex)
        }
    }

    fun enterProfileGate(profiles: List<NuvioProfile>, syncOnEnter: Boolean) {
        profileSelectionLoading = false
        profileSelectionTransitionActive = false
        if (profiles.isEmpty()) {
            autoSkipProfileSelection = true
            gateScreen = AppGateScreen.ProfileSelection.name
            return
        }

        rememberedStartupProfile(profiles)?.let { profile ->
            selectProfile(profile, sync = syncOnEnter)
            gateScreen = AppGateScreen.Main.name
            autoSkipProfileSelection = false
            return
        }

        autoSkipProfileSelection = true
        if (profiles.size == 1) {
            val onlyProfile = profiles.first()
            if (onlyProfile.pinEnabled) {
                gateScreen = AppGateScreen.ProfileSelection.name
                return
            }
            selectProfile(onlyProfile, sync = syncOnEnter)
            gateScreen = AppGateScreen.Main.name
            autoSkipProfileSelection = false
        } else {
            gateScreen = AppGateScreen.ProfileSelection.name
        }
    }

    // `needsDeviceSetup` is a key, not just a read: pairing completing changes it, and that change
    // is what carries the device off the setup step and on to sign-in.
    LaunchedEffect(authState, networkStatusUiState.condition, profileState.profiles, needsDeviceSetup) {
        val cachedProfiles = profileState.profiles
        val hasCachedProfileAccess =
            cachedProfiles.isNotEmpty() &&
                authState !is AuthState.Authenticated
        val allowCachedProfileAccess =
            hasCachedProfileAccess &&
                (
                    networkStatusUiState.condition != NetworkCondition.Online ||
                        (gateScreen != AppGateScreen.Auth.name &&
                            gateScreen != AppGateScreen.DeviceSetup.name)
                )

        when (authState) {
            is AuthState.Loading -> {
                if (hasCachedProfileAccess) {
                    enterProfileGate(cachedProfiles, syncOnEnter = false)
                } else {
                    gateScreen = AppGateScreen.Loading.name
                }
            }
            is AuthState.Unauthenticated -> {
                if (allowCachedProfileAccess) {
                    enterProfileGate(cachedProfiles, syncOnEnter = false)
                } else {
                    ProfileRepository.clearInMemory()
                    profileSelectionLoading = false
                    profileSelectionTransitionActive = false
                    // Setup first, sign-in second — and only for a device that has never paired.
                    // `needsDeviceSetup` is false the moment a session exists, so this is the same
                    // sign-in path as before for every device that has already been through it.
                    gateScreen = if (needsDeviceSetup) {
                        AppGateScreen.DeviceSetup.name
                    } else {
                        AppGateScreen.Auth.name
                    }
                }
            }
            is AuthState.Authenticated -> {
                val authenticatedState = authState as AuthState.Authenticated
                ProfileRepository.ensureLoaded(authenticatedState.userId)
                // ⚠️ `DeviceSetup` belongs in this list. A session can appear or a token can be
                // restored while the setup step is on screen — pairing completing is one way, and
                // a restore racing the first composition is another — and without it the device
                // would be authenticated and still sitting on a setup screen it no longer needs,
                // with nothing left to advance it.
                if (
                    gateScreen == AppGateScreen.Loading.name ||
                    gateScreen == AppGateScreen.Auth.name ||
                    gateScreen == AppGateScreen.DeviceSetup.name
                ) {
                    enterProfileGate(ProfileRepository.state.value.profiles, syncOnEnter = true)
                }
            }
        }
    }

    LaunchedEffect((authState as? AuthState.Authenticated)?.userId) {
        val authenticatedState = authState as? AuthState.Authenticated ?: return@LaunchedEffect
        ProfileRepository.ensureLoaded(authenticatedState.userId)
        ProfileRepository.pullProfiles()
    }

    LaunchedEffect(
        gateScreen,
        autoSkipProfileSelection,
        profileState.profiles,
        profileState.hasEverSelectedProfile,
        profileState.rememberLastProfileEnabled,
        profileState.activeProfile?.profileIndex,
        profileState.activeProfile?.pinEnabled,
    ) {
        if (
            autoSkipProfileSelection &&
            gateScreen == AppGateScreen.ProfileSelection.name
        ) {
            rememberedStartupProfile(profileState.profiles)?.let { profile ->
                selectProfile(profile, sync = true)
                gateScreen = AppGateScreen.Main.name
                autoSkipProfileSelection = false
                return@LaunchedEffect
            }

            if (profileState.profiles.size != 1) return@LaunchedEffect

            val onlyProfile = profileState.profiles.first()
            if (onlyProfile.pinEnabled) return@LaunchedEffect

            selectProfile(onlyProfile, sync = true)
            gateScreen = AppGateScreen.Main.name
            autoSkipProfileSelection = false
        }
    }

    val profileOverlayVisible =
        gateScreen == AppGateScreen.ProfileSelection.name || profileSelectionLoading
    val profileOverlayState = remember {
        MutableTransitionState(profileOverlayVisible)
    }
    profileOverlayState.targetState = profileOverlayVisible
    val launchOverlayVisible =
        !renderMainContent &&
            gateScreen == AppGateScreen.Main.name &&
            !externalMainContentReady
    val launchOverlayState = remember {
        MutableTransitionState(launchOverlayVisible)
    }
    launchOverlayState.targetState = launchOverlayVisible

    LaunchedEffect(
        renderMainContent,
        gateScreen,
        externalMainContentReady,
        profileSelectionLoading,
        profileOverlayState.currentState,
        profileOverlayState.isIdle,
        launchOverlayState.currentState,
        launchOverlayState.isIdle,
        onAppReady,
    ) {
        if (renderMainContent) return@LaunchedEffect
        val overlaysHidden =
            profileOverlayState.isIdle &&
                !profileOverlayState.currentState &&
                launchOverlayState.isIdle &&
                !launchOverlayState.currentState
        onAppReady?.invoke(
            gateScreen == AppGateScreen.Main.name &&
                externalMainContentReady &&
                !profileSelectionLoading &&
                overlaysHidden,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = gateScreen,
            label = "app_gate",
            transitionSpec = {
                (fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.94f))
                    .togetherWith(fadeOut(tween(250)))
            },
        ) { currentGate ->
            when (currentGate) {
                AppGateScreen.Loading.name -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.nuvio.colors.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioLoadingIndicator()
                    }
                }
                AppGateScreen.Auth.name -> {
                    AuthScreen(modifier = Modifier.fillMaxSize())
                }
                AppGateScreen.DeviceSetup.name -> {
                    // No success callback: the step ends by the session appearing, which the gate
                    // is already watching. `onSkip` is the only thing it has to be told.
                    DeviceSetupGate(
                        onSkip = { deviceSetupSkipped = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                AppGateScreen.ProfileSelection.name -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.nuvio.colors.background),
                    )
                }
                AppGateScreen.ProfileEdit.name -> {
                    PlatformBackHandler(enabled = gateScreen == AppGateScreen.ProfileEdit.name) {
                        gateScreen = AppGateScreen.ProfileSelection.name
                    }
                    ProfileEditScreen(
                        profile = editingProfile,
                        onBack = { gateScreen = AppGateScreen.ProfileSelection.name },
                        onSaved = { gateScreen = AppGateScreen.ProfileSelection.name },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                AppGateScreen.Main.name -> {
                    if (renderMainContent) {
                        MainAppContent(
                            initialTab = initialTab,
                            initialRoute = initialRoute,
                            useNativeNavigation = useNativeNavigation,
                            useNativeTabBar = useNativeTabBar,
                            useTabletFloatingTabBar = useTabletFloatingTabBar,
                            ownsAppRuntime = ownsAppRuntime,
                            showLaunchOverlay = !profileSelectionLoading,
                            onNavigate = onNavigate,
                            onGoBack = onGoBack,
                            onReplace = onReplace,
                            onActivate = onActivate,
                            onTabTitles = onTabTitles,
                            appGateController = appGateController,
                            onRootContentReady = { ready ->
                                if (ready) {
                                    profileSelectionLoading = false
                                }
                                onAppReady?.invoke(
                                    ready && gateScreen == AppGateScreen.Main.name,
                                )
                            },
                            onSwitchProfile = {
                                autoSkipProfileSelection = false
                                profileSelectionLoading = false
                                profileSelectionTransitionActive = false
                                skipProfileSelectionEnterAnimation = false
                                gateScreen = AppGateScreen.ProfileSelection.name
                            },
                        )
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visibleState = launchOverlayState,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.fillMaxSize(),
        ) {
            AppLaunchOverlay(
                profile = profileState.activeProfile ?: profileState.profiles.firstOrNull(),
                modifier = Modifier.fillMaxSize(),
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visibleState = profileOverlayState,
            enter = if (skipProfileSelectionEnterAnimation) {
                androidx.compose.animation.EnterTransition.None
            } else {
                fadeIn(tween(400))
            },
            exit = fadeOut(tween(400)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(NuvioTokens.Z.dialog),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val onBack: (() -> Unit)? = if (!autoSkipProfileSelection) {
                    {
                        skipProfileSelectionEnterAnimation = false
                        gateScreen = AppGateScreen.Main.name
                    }
                } else {
                    null
                }
                PlatformBackHandler(
                    enabled = gateScreen == AppGateScreen.ProfileSelection.name && !profileSelectionLoading,
                ) {
                    onBack?.invoke()
                }
                ProfileSelectionScreen(
                    onProfileSelected = { profile ->
                        if (
                            !profileSelectionLoading &&
                            (autoSkipProfileSelection || profile.profileIndex != ProfileRepository.state.value.activeProfile?.profileIndex)
                        ) {
                            profileSelectionLoading = true
                            profileSelectionTransitionActive = true
                            skipProfileSelectionEnterAnimation = false
                            selectProfile(
                                profile = profile,
                                sync = authState is AuthState.Authenticated,
                            )
                            gateScreen = AppGateScreen.Main.name
                            if (!renderMainContent) {
                                onActivate?.invoke(AppScreenTab.Home)
                            }
                        }
                    },
                    onEditProfile = { profile ->
                        editingProfile = profile
                        skipProfileSelectionEnterAnimation = false
                        gateScreen = AppGateScreen.ProfileEdit.name
                    },
                    onAddProfile = {
                        editingProfile = null
                        skipProfileSelectionEnterAnimation = false
                        gateScreen = AppGateScreen.ProfileEdit.name
                    },
                    interactionEnabled = !profileSelectionLoading,
                    onBack = onBack,
                    activeProfileIndex = if (autoSkipProfileSelection) null else profileState.activeProfile?.profileIndex,
                    contentVisible = !profileSelectionTransitionActive,
                    modifier = Modifier.fillMaxSize(),
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = profileSelectionTransitionActive,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AppLoadingContent(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
