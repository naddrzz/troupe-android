package dev.troupe.android.pairing

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.R
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ConnectionFailure
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.net.ViewOnlyRegistry
import dev.troupe.android.servers.PairedServerRepository
import dev.troupe.android.terminal.net.TerminalSocketFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConnectUiState {
    data object Idle : ConnectUiState
    data object Checking : ConnectUiState

    /**
     * Pasted URL used a wildcard bind (`0.0.0.0` or `::`). Ask for the server's reachable
     * LAN / Tailscale IP or hostname while preserving port, path, `?setup=`, and `#token=`.
     */
    data class NeedsHost(val rawInput: String, val parsed: ParsedServerLink) : ConnectUiState

    /**
     * Server answered `/health` OK, but rejected an empty or stale token. Present the
     * 1:1 `TokenGateScreen` so the user can enter or paste the bearer token.
     */
    data class NeedsToken(
        val address: ServerAddress,
        val serverName: String,
        val chatSessionId: String?,
        val rejected: Boolean = false,
        val checking: Boolean = false,
    ) : ConnectUiState

    /** Server answered `/health` with `setupRequired == true`; launch first-run setup wizard. */
    data class NeedsSetup(
        val address: ServerAddress,
        val setupToken: String?,
    ) : ConnectUiState

    data class Error(@StringRes val message: Int, val arg: String? = null) : ConnectUiState

    data class Connected(
        val serverId: String,
        val chatSessionId: String? = null,
    ) : ConnectUiState
}

/**
 * ViewModel for the Troupe V2 universal Connect / Paste-Link screen.
 * Accepts `#token=` links, ungated `http://host:port` URLs, multi-line terminal output,
 * `?setup=<token>` links, `0.0.0.0` wildcard addresses, QR scans, and manual entries.
 */
@HiltViewModel
class ConnectServerViewModel @Inject constructor(
    private val api: ServerApi,
    private val sockets: TerminalSocketFactory,
    private val repository: PairedServerRepository,
    private val viewOnly: ViewOnlyRegistry,
) : ViewModel() {

    private val _ui = MutableStateFlow<ConnectUiState>(ConnectUiState.Idle)
    val ui: StateFlow<ConnectUiState> = _ui.asStateFlow()

    fun reset() {
        _ui.value = ConnectUiState.Idle
    }

    fun connectLink(raw: String, replacementHost: String? = null, customName: String = "") {
        if (_ui.value is ConnectUiState.Checking) return
        val parsed = TroupeServerLink.parse(raw, replacementHost).getOrElse { error ->
            _ui.value = (error as? PairingError)?.toUi()
                ?: ConnectUiState.Error(R.string.pair_error_invalid_url)
            return
        }
        if (parsed.isWildcardHost) {
            _ui.value = ConnectUiState.NeedsHost(rawInput = raw, parsed = parsed)
            return
        }
        val name = customName.trim().ifEmpty { parsed.address.host }
        probeAndConnect(parsed, name)
    }

    fun submitReplacementHost(host: String) {
        val current = _ui.value as? ConnectUiState.NeedsHost ?: return
        connectLink(current.rawInput, replacementHost = host)
    }

    fun connectManual(url: String, token: String, name: String) {
        if (_ui.value is ConnectUiState.Checking) return
        val parsed = TroupeServerLink.parse(url).getOrElse { error ->
            _ui.value = (error as? PairingError)?.toUi()
                ?: ConnectUiState.Error(R.string.pair_error_invalid_url)
            return
        }
        if (parsed.isWildcardHost) {
            _ui.value = ConnectUiState.NeedsHost(rawInput = url, parsed = parsed)
            return
        }
        val cleanToken = token.trim().ifEmpty { parsed.token }
        val resolved = parsed.copy(token = cleanToken)
        probeAndConnect(resolved, name.trim().ifEmpty { resolved.address.host })
    }

    fun submitGateToken(rawToken: String) {
        val current = _ui.value as? ConnectUiState.NeedsToken ?: return
        if (current.checking) return
        val cleanToken = extractTokenOrRaw(rawToken)
        if (cleanToken.isEmpty()) return
        _ui.value = current.copy(checking = true, rejected = false)
        viewModelScope.launch {
            val result = verifyAndSave(
                address = current.address,
                token = cleanToken,
                name = current.serverName,
                chatSessionId = current.chatSessionId,
                allowRemoteWrites = null,
            )
            _ui.value = result
        }
    }

    private fun probeAndConnect(parsed: ParsedServerLink, name: String) {
        _ui.value = ConnectUiState.Checking
        viewModelScope.launch {
            val health = when (val res = api.health(parsed.address)) {
                is ApiResult.Ok -> res.value
                ApiResult.Unauthorized -> {
                    _ui.value = ConnectUiState.NeedsToken(
                        address = parsed.address,
                        serverName = name,
                        chatSessionId = parsed.chatSessionId,
                        rejected = !parsed.token.isNullOrEmpty(),
                    )
                    return@launch
                }
                is ApiResult.Unreachable -> {
                    _ui.value = ConnectUiState.Error(R.string.pair_error_unreachable)
                    return@launch
                }
                is ApiResult.ServerError -> {
                    _ui.value = ConnectUiState.Error(R.string.pair_error_server, res.message)
                    return@launch
                }
            }

            if (health.setupRequired) {
                _ui.value = ConnectUiState.NeedsSetup(
                    address = parsed.address,
                    setupToken = parsed.setupToken,
                )
                return@launch
            }

            _ui.value = verifyAndSave(
                address = parsed.address,
                token = parsed.token,
                name = name,
                chatSessionId = parsed.chatSessionId,
                allowRemoteWrites = health.allowRemoteWrites,
            )
        }
    }

    private suspend fun verifyAndSave(
        address: ServerAddress,
        token: String?,
        name: String,
        chatSessionId: String?,
        allowRemoteWrites: Boolean?,
    ): ConnectUiState {
        val remoteWrites = allowRemoteWrites ?: when (val h = api.health(address)) {
            is ApiResult.Ok -> h.value.allowRemoteWrites
            is ApiResult.Unreachable -> return ConnectUiState.Error(R.string.pair_error_unreachable)
            ApiResult.Unauthorized -> return ConnectUiState.NeedsToken(address, name, chatSessionId, rejected = true)
            is ApiResult.ServerError -> return ConnectUiState.Error(R.string.pair_error_server, h.message)
        }

        val socket = sockets.create(address, token.orEmpty())
        val handshake = try {
            socket.connect()
        } finally {
            socket.close()
        }
        if (handshake is ConnectionState.Failed) {
            return when (handshake.reason) {
                ConnectionFailure.Unauthorized -> ConnectUiState.NeedsToken(
                    address = address,
                    serverName = name,
                    chatSessionId = chatSessionId,
                    rejected = !token.isNullOrEmpty(),
                )
                ConnectionFailure.ProtocolMismatch -> ConnectUiState.Error(R.string.pair_error_protocol)
                ConnectionFailure.Unreachable -> ConnectUiState.Error(R.string.pair_error_unreachable)
                ConnectionFailure.Refused -> ConnectUiState.Error(R.string.pair_error_server, handshake.message.orEmpty())
            }
        }

        val saved = repository.upsert(name = name, address = address, token = token, makeActive = true)
        viewOnly.set(saved.id, !remoteWrites)
        return ConnectUiState.Connected(serverId = saved.id, chatSessionId = chatSessionId)
    }

    /** Lets users paste either a bare token or a full `#token=` link into the Token Gate input. */
    private fun extractTokenOrRaw(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val parsed = TroupeServerLink.parse(trimmed).getOrNull()
        return parsed?.token ?: trimmed
    }

    private fun PairingError.toUi(): ConnectUiState.Error = when (this) {
        PairingError.InvalidUrl -> ConnectUiState.Error(R.string.pair_error_invalid_url)
        PairingError.MissingToken -> ConnectUiState.Error(R.string.pair_error_missing_token)
        is PairingError.CleartextNotAllowed -> ConnectUiState.Error(R.string.pair_error_cleartext, host)
    }
}
