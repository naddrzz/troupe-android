package dev.troupe.android.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.net.ViewOnlyRegistry
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.pairing.TroupeServerLink
import dev.troupe.android.servers.PairedServerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupWizardMode { Setup, Reconfigure }

data class ServerSetupUiState(
    val mode: SetupWizardMode = SetupWizardMode.Setup,
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val restarting: Boolean = false,
    val answers: ServerSetupAnswers = ServerSetupAnswers(),
    val fieldErrors: SetupFieldErrors = SetupFieldErrors(),
    val configError: String? = null,
    val error: String? = null,
    val needsSetupToken: Boolean = false,
    val completedServerId: String? = null,
)

@HiltViewModel
class ServerSetupViewModel @Inject constructor(
    private val setupApi: ServerSetupApi,
    private val serverApi: ServerApi,
    private val repository: PairedServerRepository,
    private val viewOnly: ViewOnlyRegistry,
) : ViewModel() {

    private val _ui = MutableStateFlow(ServerSetupUiState())
    val ui: StateFlow<ServerSetupUiState> = _ui.asStateFlow()

    private var address: ServerAddress? = null
    private var token: String? = null
    private var existingServerId: String? = null
    private var serverName: String = ""

    fun loadForFirstRun(targetAddress: ServerAddress, setupToken: String?) {
        address = targetAddress
        token = setupToken
        existingServerId = null
        serverName = targetAddress.host
        _ui.value = ServerSetupUiState(
            mode = SetupWizardMode.Setup,
            loading = true,
            needsSetupToken = setupToken.isNullOrBlank(),
        )
        if (setupToken.isNullOrBlank()) {
            _ui.update { it.copy(loading = false) }
            return
        }
        fetchSetupStatus(targetAddress, setupToken)
    }

    fun loadForReconfigure(serverId: String) {
        existingServerId = serverId
        _ui.value = ServerSetupUiState(mode = SetupWizardMode.Reconfigure, loading = true)
        viewModelScope.launch {
            val server = repository.server(serverId)
            val authToken = repository.token(serverId)
            if (server == null || authToken == null) {
                _ui.update { it.copy(loading = false, error = "Server credentials are unavailable.") }
                return@launch
            }
            address = server.address
            token = authToken
            serverName = server.name
            when (val res = setupApi.getServerConfig(server.address, authToken)) {
                is ApiResult.Ok -> _ui.update {
                    it.copy(loading = false, answers = res.value.config, error = null)
                }
                ApiResult.Unauthorized -> _ui.update {
                    it.copy(loading = false, error = "Authentication failed. Re-enter your server token.")
                }
                is ApiResult.Unreachable -> _ui.update {
                    it.copy(loading = false, error = "Cannot reach server (${res.cause}).")
                }
                is ApiResult.ServerError -> _ui.update {
                    it.copy(loading = false, error = res.message)
                }
            }
        }
    }

    fun submitSetupToken(rawToken: String) {
        val target = address ?: return
        val clean = rawToken.trim()
        if (clean.isEmpty()) return
        token = clean
        _ui.update { it.copy(loading = true, needsSetupToken = false, error = null) }
        fetchSetupStatus(target, clean)
    }

    private fun fetchSetupStatus(target: ServerAddress, setupToken: String) {
        viewModelScope.launch {
            when (val res = setupApi.getStatus(target, setupToken)) {
                is ApiResult.Ok -> _ui.update {
                    it.copy(
                        loading = false,
                        answers = res.value.defaults,
                        configError = res.value.configError,
                        error = null,
                    )
                }
                ApiResult.Unauthorized -> _ui.update {
                    it.copy(
                        loading = false,
                        needsSetupToken = true,
                        error = "The setup token was rejected. Copy the ?setup= token printed by troupe-server.",
                    )
                }
                is ApiResult.Unreachable -> _ui.update {
                    it.copy(loading = false, error = "Cannot reach server (${res.cause}).")
                }
                is ApiResult.ServerError -> _ui.update {
                    it.copy(loading = false, error = res.message)
                }
            }
        }
    }

    fun updateAnswers(transform: (ServerSetupAnswers) -> ServerSetupAnswers) {
        _ui.update { it.copy(answers = transform(it.answers), fieldErrors = SetupFieldErrors(), error = null) }
    }

    fun apply() {
        val target = address ?: return
        val current = _ui.value
        if (current.submitting || current.restarting) return
        if (current.answers.port !in 1..65535) {
            _ui.update {
                it.copy(fieldErrors = SetupFieldErrors(port = "Enter a valid port between 1 and 65535."))
            }
            return
        }
        val targetIsLoopback = isLoopbackHost(target.host)
        if (isLoopbackHost(current.answers.host) && !targetIsLoopback) {
            _ui.update {
                it.copy(
                    fieldErrors = SetupFieldErrors(
                        host = "Local-only (127.0.0.1) cannot be reached from Android over the network. Choose All interfaces (0.0.0.0).",
                    ),
                )
            }
            return
        }
        _ui.update { it.copy(submitting = true, error = null, fieldErrors = SetupFieldErrors()) }

        viewModelScope.launch {
            val res = when (current.mode) {
                SetupWizardMode.Setup -> setupApi.apply(target, token, current.answers)
                SetupWizardMode.Reconfigure -> setupApi.applyServerConfig(target, token, current.answers)
            }
            when (res) {
                is ApiResult.Ok -> {
                    val reply = res.value
                    if (!reply.ok) {
                        _ui.update {
                            it.copy(
                                submitting = false,
                                fieldErrors = reply.fieldErrors,
                                error = if (!reply.fieldErrors.hasAny()) "Validation failed." else null,
                            )
                        }
                        return@launch
                    }
                    _ui.update { it.copy(submitting = false, restarting = true) }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        waitForRestartAndFinish(target, reply, current.answers)
                    }
                }
                ApiResult.Unauthorized -> _ui.update {
                    it.copy(
                        submitting = false,
                        needsSetupToken = current.mode == SetupWizardMode.Setup,
                        error = "Authorization rejected by server.",
                    )
                }
                is ApiResult.Unreachable -> _ui.update {
                    it.copy(submitting = false, error = "Lost connection to server (${res.cause}).")
                }
                is ApiResult.ServerError -> _ui.update {
                    it.copy(submitting = false, error = res.message)
                }
            }
        }
    }

    private suspend fun waitForRestartAndFinish(
        previousAddress: ServerAddress,
        reply: SetupApplyReply,
        answers: ServerSetupAnswers,
    ) {
        val replyHost = reply.host?.trim()?.takeIf { it.isNotEmpty() }
        val nextHost = when {
            replyHost == null -> previousAddress.host
            TroupeServerLink.isWildcard(replyHost) -> previousAddress.host
            isLoopbackHost(replyHost) && !isLoopbackHost(previousAddress.host) -> previousAddress.host
            else -> replyHost
        }
        val nextPort = reply.port ?: previousAddress.baseUrl.port
        val nextAddress = ServerAddress(
            previousAddress.baseUrl.newBuilder()
                .host(nextHost)
                .port(nextPort)
                .build(),
        )

        val finalToken = reply.webAuthToken ?: if (_ui.value.mode == SetupWizardMode.Reconfigure) token else null

        // Server restarts ~400ms after answering /setup/apply or /server/config.
        delay(600)
        var ready = false
        var allowRemoteWrites = answers.allowRemoteWrites
        repeat(16) {
            when (val health = serverApi.health(nextAddress, finalToken)) {
                is ApiResult.Ok -> {
                    if (!health.value.setupRequired) {
                        allowRemoteWrites = health.value.allowRemoteWrites
                        ready = true
                        return@repeat
                    }
                }
                else -> Unit
            }
            delay(500)
        }

        val saved = repository.upsert(
            name = serverName.ifBlank { nextAddress.host },
            address = nextAddress,
            token = finalToken,
            makeActive = true,
        )
        val priorId = existingServerId
        if (priorId != null && priorId != saved.id) {
            repository.remove(priorId)
        }
        viewOnly.set(saved.id, !allowRemoteWrites)
        if (ready) {
            _ui.update { it.copy(restarting = false, completedServerId = saved.id) }
        } else {
            _ui.update {
                it.copy(
                    restarting = false,
                    completedServerId = saved.id,
                    error = "Server is still restarting; saved new settings.",
                )
            }
        }
    }

    private fun isLoopbackHost(host: String): Boolean {
        val clean = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        return clean == "127.0.0.1" || clean == "localhost" || clean == "::1"
    }
}
