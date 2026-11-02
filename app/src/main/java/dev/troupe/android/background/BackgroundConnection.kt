package dev.troupe.android.background

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.troupe.android.R
import dev.troupe.android.attention.FinishedTurn
import dev.troupe.android.attention.NotificationContent
import dev.troupe.android.attention.Text
import dev.troupe.android.servers.PairedServer
import dev.troupe.android.servers.PairedServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the foreground service holds the sockets: the app is in the background and a server stays connected. */
internal fun holdInBackground(foreground: Boolean, servers: List<PairedServer>): Boolean =
    !foreground && servers.any { it.stayConnected }

/** The ongoing notification: "Troupe is connected to <names>" of the servers that stay connected; while the app lock is on, no names. */
internal fun connectedContent(servers: List<PairedServer>, locked: Boolean): Text {
    val names = servers.filter { it.stayConnected }.map { it.name }
    return if (locked || names.isEmpty()) Text(R.string.background_connected_public)
    else Text(R.string.background_connected, listOf(names.joinToString(", ")))
}

/** "<Session> finished", with its Project below; while the app lock is on, no names. */
internal fun finishedContent(turn: FinishedTurn, locked: Boolean): NotificationContent = when {
    locked -> NotificationContent(Text(R.string.turn_finished_locked), null)
    turn.sessionTitle != null -> NotificationContent(Text(R.string.turn_finished, listOf(turn.sessionTitle)), turn.projectName?.let { Text(R.string.attention_chat, listOf(it)) })
    else -> NotificationContent(Text(R.string.turn_finished_untitled), turn.projectName?.let { Text(R.string.attention_chat, listOf(it)) })
}

/**
 * Starts the background connection when the app is hidden and a Paired Server stays connected. The
 * service stops itself when the app comes back, every toggle is off or every server is removed.
 */
@Singleton
class BackgroundConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    repository: PairedServerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Null until the paired list is first read. */
    private val stayConnected: StateFlow<Boolean?> =
        repository.servers.map { list -> list.any { it.stayConnected } }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The app's activity stopped (not for a configuration change). Starting a foreground service is
     * allowed here, as the app leaves a visible state; when it isn't, the app stays as it was.
     */
    fun onAppHidden() {
        when (stayConnected.value) {
            true -> start()
            false -> Unit
            // Hidden before the list was read: decide once it is.
            null -> scope.launch { if (stayConnected.filterNotNull().first()) start() }
        }
    }

    private fun start() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, BackgroundConnectionService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: not allowed right now; the next hide tries again.
        }
    }
}
