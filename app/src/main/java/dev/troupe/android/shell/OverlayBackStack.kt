package dev.troupe.android.shell

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * LIFO overlay back-stack matching `stores/overlay-stack-store.ts`.
 * When multiple sheets/drawers/popups are active, Android's system Back button dismisses
 * strictly the topmost registered overlay first.
 */
@Stable
class OverlayBackStack {
    private data class Entry(val id: String, val onDismiss: () -> Unit)

    private val entries = mutableStateListOf<Entry>()

    val hasOverlay: Boolean get() = entries.isNotEmpty()

    fun register(id: String, onDismiss: () -> Unit) {
        entries.removeAll { it.id == id }
        entries.add(Entry(id, onDismiss))
    }

    fun unregister(id: String) {
        entries.removeAll { it.id == id }
    }

    fun popTop(): Boolean {
        val top = entries.lastOrNull() ?: return false
        top.onDismiss()
        return true
    }
}

@Composable
fun rememberOverlayBackStack(): OverlayBackStack {
    val stack = remember { OverlayBackStack() }
    BackHandler(enabled = stack.hasOverlay) {
        stack.popTop()
    }
    return stack
}

@Composable
fun OverlayBackStack.Register(id: String, open: Boolean, onDismiss: () -> Unit) {
    val latestDismiss = rememberUpdatedState(onDismiss)
    DisposableEffect(id, open) {
        if (open) {
            register(id) { latestDismiss.value() }
        }
        onDispose {
            unregister(id)
        }
    }
}
