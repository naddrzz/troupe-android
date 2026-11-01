package dev.troupe.android.core.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether each Paired Server is View-only (GLOSSARY): the one place every screen and action reads
 * it from. It comes from `/health.allowRemoteWrites == false` at pairing and on every connect, and
 * turns on at once when the server refuses a write with `FORBIDDEN` ([onRefusal]).
 */
@Singleton
class ViewOnlyRegistry @Inject constructor() {
    private val states = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    /** Whether [serverId] is View-only; false until the server has said otherwise. */
    fun state(serverId: String): StateFlow<Boolean> = flow(serverId).asStateFlow()

    fun set(serverId: String, viewOnly: Boolean) {
        flow(serverId).value = viewOnly
    }

    /** A request to [serverId] was refused with [code]: a `FORBIDDEN` refusal makes it View-only. */
    fun onRefusal(serverId: String, code: String?) {
        if (code.equals(FORBIDDEN, ignoreCase = true)) set(serverId, true)
    }

    private fun flow(serverId: String) = states.computeIfAbsent(serverId) { MutableStateFlow(false) }

    companion object {
        /** termul's refusal of a write on a View-only connection (`forbidden` on `/ws`). */
        const val FORBIDDEN = "FORBIDDEN"
    }
}
