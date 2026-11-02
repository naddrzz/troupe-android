package dev.troupe.android.core.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The screen on top of the navigation stack. */
sealed interface Screen {
    /** A Paired Server's screen: its Attention and Projects. */
    data class Server(val serverId: String) : Screen
    data class Chat(val serverId: String, val sessionId: String) : Screen
    data object Other : Screen

    /** Whether this screen already shows what waits in [sessionId] on [serverId]. */
    fun shows(serverId: String, sessionId: String): Boolean = when (this) {
        is Server -> this.serverId == serverId
        is Chat -> this.serverId == serverId && this.sessionId == sessionId
        Other -> false
    }
}

/** Whether the app is in the foreground, and on which screen. */
@Singleton
class Screens @Inject constructor() {
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()
    private val _current = MutableStateFlow<Screen>(Screen.Other)
    val current: StateFlow<Screen> = _current.asStateFlow()

    fun onForeground(foreground: Boolean) {
        _foreground.value = foreground
    }

    fun onScreen(screen: Screen) {
        _current.value = screen
    }
}

/** Where a notification leads: an Agent Chat on a Paired Server. */
data class ChatLink(val serverId: String, val sessionId: String, val title: String?)
