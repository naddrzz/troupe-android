package dev.troupe.android.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ServersViewModel @Inject constructor(repository: PairedServerRepository) : ViewModel() {
    /** `null` until storage has been read, so the empty state never flashes for existing users. */
    val servers: StateFlow<List<PairedServer>?> =
        repository.servers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
