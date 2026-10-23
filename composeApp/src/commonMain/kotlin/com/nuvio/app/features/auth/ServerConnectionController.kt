package com.nuvio.app.features.auth

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.network.ServerAliases
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.ServerDiscoveryException
import com.nuvio.app.core.network.ServerDiscoveryFailure
import com.nuvio.app.core.network.ServerDiscoveryService
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.boomio.BoomioConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ServerSwitchFailure {
    SessionClear,
    Save,
    Restart,
}

data class ServerConnectionUiState(
    val activeServer: ServerConfiguration = ServerConfigurationRepository.active.value,
    val isDiscovering: Boolean = false,
    val isSwitching: Boolean = false,
    val discoveredServer: ServerConfiguration? = null,
    val failure: ServerDiscoveryFailure? = null,
    val statusCode: Int? = null,
    val switchFailure: ServerSwitchFailure? = null,
)

object ServerConnectionController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ServerConnectionUiState())
    val state: StateFlow<ServerConnectionUiState> = _state.asStateFlow()
    private var discoveryJob: Job? = null

    fun discover(url: String) {
        if (_state.value.isDiscovering || _state.value.isSwitching) return
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            _state.update {
                it.copy(
                    isDiscovering = true,
                    discoveredServer = null,
                    failure = null,
                    statusCode = null,
                    switchFailure = null,
                )
            }
            ServerDiscoveryService.discover(url).fold(
                onSuccess = { server ->
                    _state.update { it.copy(isDiscovering = false, discoveredServer = server) }
                },
                onFailure = { error ->
                    val discoveryError = error as? ServerDiscoveryException
                    _state.update {
                        it.copy(
                            isDiscovering = false,
                            failure = discoveryError?.failure ?: ServerDiscoveryFailure.ConnectionFailed,
                            statusCode = discoveryError?.statusCode,
                        )
                    }
                },
            )
        }
    }

    fun connectDiscovered() {
        val server = _state.value.discoveredServer ?: return
        switchServer { ServerConfigurationRepository.saveCustom(server) }
    }

    fun useOfficial() {
        if (!_state.value.activeServer.isCustom) return
        switchServer(ServerConfigurationRepository::useOfficial)
    }

    fun resetDiscovery() {
        if (_state.value.isSwitching) return
        discoveryJob?.cancel()
        discoveryJob = null
        _state.update {
            it.copy(
                isDiscovering = false,
                discoveredServer = null,
                failure = null,
                statusCode = null,
                switchFailure = null,
            )
        }
    }

    /**
     * Points every boomio base at the server that is now saved, or back at the build's floor.
     *
     * ⚠️ **Canonicalised, because the saved URL may be an address.** A person can type the box's
     * private literal, and the edge has no certificate for it — `ServerAliases` rewrites it to the
     * name the box publishes under, which is routable on the LAN and off it.
     */
    private fun applyActiveServiceOrigin() {
        val active = ServerConfigurationRepository.active.value
        if (active.isCustom && active.backendUrl.isNotBlank()) {
            BoomioConfig.serviceOrigin = ServerAliases.canonicalInput(active.backendUrl)
        } else {
            BoomioConfig.resetServiceOrigin()
        }
    }

    private fun switchServer(save: () -> Boolean) {
        if (_state.value.isSwitching) return
        scope.launch {
            _state.update { it.copy(isSwitching = true, failure = null, switchFailure = null) }
            try {
                if (AuthRepository.prepareForServerSwitch().isFailure) {
                    _state.update {
                        it.copy(isSwitching = false, switchFailure = ServerSwitchFailure.SessionClear)
                    }
                    return@launch
                }
                if (!save()) {
                    _state.update { it.copy(isSwitching = false, switchFailure = ServerSwitchFailure.Save) }
                    return@launch
                }
                SupabaseProvider.reset()
                AuthRepository.reinitialize()
                // ⚠️ **The choice has to reach the boomio plane, not only auth.** [save] writes the
                // Supabase configuration, but `/bsm` (TV pairing), `/bsc` (overlay enrolment) and
                // `/bsf` are all derived from [BoomioConfig.serviceOrigin] — which, until this
                // line, only discovery ever wrote. A phone switched to the second box therefore
                // signed in there and then enrolled its VPN against the first one, and the next
                // cold start was back on the compile-time name. Applied before the restart so a
                // failed restart cannot leave the two halves on different servers.
                applyActiveServiceOrigin()
                NetworkStatusRepository.requestRefresh(force = true)
                _state.value = ServerConnectionUiState(
                    activeServer = ServerConfigurationRepository.active.value,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _state.update { it.copy(isSwitching = false, switchFailure = ServerSwitchFailure.Restart) }
            }
        }
    }
}
