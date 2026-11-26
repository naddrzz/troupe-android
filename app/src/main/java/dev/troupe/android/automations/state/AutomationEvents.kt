package dev.troupe.android.automations.state

import dev.troupe.android.automations.protocol.AutomationChanged
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.core.store.WatchHub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

sealed interface AutomationEvent {
    /** An automation (or, with a null id, a project's automations as a whole) changed on the server. */
    data class Changed(val change: AutomationChanged) : AutomationEvent

    /**
     * Updates may have been missed: reload whatever is shown. Sent for every snapshot of the watch, the first one
     * included: a load begun before the subscription started may have read data that a change, sent before there
     * was anyone to send its delta to, has since replaced.
     */
    data object Resync : AutomationEvent
}

/**
 * Automation events of a Paired Server, from its `agents` watch. The server carries every
 * project's events on the one subscription (scope `""`), so callers filter by project.
 */
class AutomationEvents(private val hub: WatchHub) {
    fun stream(): Flow<AutomationEvent> = flow {
        hub.watch(WatchHub.AGENTS).collect { update ->
            val event = if (update.reset) AutomationEvent.Resync else (update.delta as? JsonObject)?.let(::parse)
            if (event != null) emit(event)
        }
    }

    private fun parse(delta: JsonObject): AutomationEvent? = when ((delta["event"] as? JsonPrimitive)?.contentOrNull) {
        "automation_changed" -> delta["payload"]?.let {
            try {
                AutomationEvent.Changed(TroupeJson.decodeFromJsonElement<AutomationChanged>(it))
            } catch (_: SerializationException) {
                null
            }
        }
        "automation_resync" -> AutomationEvent.Resync
        else -> null
    }
}
