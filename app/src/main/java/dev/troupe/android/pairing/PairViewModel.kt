package dev.troupe.android.pairing

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.R
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.servers.PairedServerRepository
import dev.troupe.android.core.net.ConnectionFailure
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.terminal.net.TerminalSocketFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PairUiState {
    data object Idle : PairUiState
    data object Checking : PairUiState
    data class Error(@StringRes val message: Int, val arg: String? = null) : PairUiState
    data class Paired(val serverId: String) : PairUiState
}

@HiltViewModel
class PairViewModel @Inject constructor(
    private val api: ServerApi,
    private val sockets: TerminalSocketFactory,
    private val repository: PairedServerRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow<PairUiState>(PairUiState.Idle)
    val ui: StateFlow<PairUiState> = _ui

    /**
     * Pairs by typed address and token. Succeeds only when `/health` answers and the
     * `/terminal/ws` handshake accepts the token; nothing is saved otherwise.
     */
    fun onManual(url: String, token: String, name: String) = start(PairingLink.fromManual(url, token), name)

    /** Pairs with a `…#token=…` link copied from Troupe on the computer; named after its host. */
    fun onLinkPasted(raw: String) = start(PairingLink.parse(raw), name = "")

    /** A scanned QR code holds the same link as the one the user can copy. */
    fun onQrScanned(raw: String) = onLinkPasted(raw)

    private fun start(parsed: Result<PairingInput>, name: String) {
        if (_ui.value == PairUiState.Checking) return
        val input = parsed.getOrElse { error ->
            _ui.value = (error as? PairingError)?.toUi() ?: PairUiState.Error(R.string.pair_error_invalid_url)
            return
        }
        _ui.value = PairUiState.Checking
        viewModelScope.launch {
            _ui.value = pair(input, name.trim().ifEmpty { input.address.host })
        }
    }

    private suspend fun pair(input: PairingInput, name: String): PairUiState {
        when (val health = api.health(input.address)) {
            is ApiResult.Ok -> Unit
            ApiResult.Unauthorized -> return PairUiState.Error(R.string.pair_error_unauthorized)
            is ApiResult.Unreachable -> return PairUiState.Error(R.string.pair_error_unreachable)
            is ApiResult.ServerError -> return PairUiState.Error(R.string.pair_error_server, health.message)
        }
        val socket = sockets.create(input.address, input.token)
        val handshake = try {
            socket.connect()
        } finally {
            socket.close()
        }
        if (handshake is ConnectionState.Failed) {
            return when (handshake.reason) {
                ConnectionFailure.Unreachable -> PairUiState.Error(R.string.pair_error_unreachable)
                ConnectionFailure.Unauthorized -> PairUiState.Error(R.string.pair_error_unauthorized)
                ConnectionFailure.ProtocolMismatch -> PairUiState.Error(R.string.pair_error_protocol)
                ConnectionFailure.Refused -> PairUiState.Error(R.string.pair_error_server, handshake.message.orEmpty())
            }
        }
        return PairUiState.Paired(repository.add(name, input).id)
    }

    private fun PairingError.toUi(): PairUiState.Error = when (this) {
        PairingError.InvalidUrl -> PairUiState.Error(R.string.pair_error_invalid_url)
        PairingError.MissingToken -> PairUiState.Error(R.string.pair_error_missing_token)
        is PairingError.CleartextNotAllowed -> PairUiState.Error(R.string.pair_error_cleartext, host)
    }
}
