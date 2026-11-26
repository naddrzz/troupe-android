package dev.troupe.android.automations.state

import dev.troupe.android.automations.protocol.AutomationChanged
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** The `agents` watch's automation deltas as [AutomationEvent]s, against the fake Troupe Server. */
class AutomationEventsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root) }
    private val chat get() = server.chat
    private val received = CopyOnWriteArrayList<AutomationEvent>()
    private lateinit var collector: Job

    @After fun tearDown() {
        if (::collector.isInitialized) runBlocking { collector.cancelAndJoin() }
        graph.close()
        server.close()
    }

    /**
     * Collects the stream and returns once the host holds an `agents` watch for it and its first snapshot came in
     * as a [AutomationEvent.Resync]; that event is then cleared, so tests see only what follows.
     */
    private fun startCollecting() {
        val serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        val hub = runBlocking { graph.chatConnections.connection(serverId) }.watchHub
        collector = CoroutineScope(Dispatchers.IO).launch { AutomationEvents(hub).stream().collect { received += it } }
        eventually { chat.watching("agents") }
        eventually(message = { "events: $received" }) { received.isNotEmpty() }
        assertEquals(listOf<AutomationEvent>(AutomationEvent.Resync), received.toList())
        received.clear()
    }

    /** Whatever was loaded before the subscription started may have missed a change that sent no delta. */
    @Test fun firstSnapshotIsAResync() {
        startCollecting()
    }

    private fun change(projectId: String, automationId: String?) = server.automationChanged(projectId, automationId)

    @Test fun changeEventIsEmitted() {
        startCollecting()

        change("p1", "a1")

        eventually { received.isNotEmpty() }
        assertEquals(listOf<AutomationEvent>(AutomationEvent.Changed(AutomationChanged("p1", "a1"))), received.toList())
    }

    @Test fun projectWideChangeHasNullAutomationId() {
        startCollecting()

        change("p1", null)

        eventually { received.isNotEmpty() }
        assertEquals(listOf<AutomationEvent>(AutomationEvent.Changed(AutomationChanged("p1", null))), received.toList())
    }

    @Test fun resyncEventIsEmitted() {
        startCollecting()

        chat.agentEvent("automation_resync", buildJsonObject { put("missed", 3) })

        eventually { received.isNotEmpty() }
        assertEquals(listOf<AutomationEvent>(AutomationEvent.Resync), received.toList())
    }

    @Test fun otherAgentEventsAreIgnored() {
        startCollecting()

        chat.agentEvent("agent_started", buildJsonObject { put("agent_id", "ag-1") })
        change("p1", "a1")

        eventually { received.isNotEmpty() }
        assertEquals(listOf<AutomationEvent>(AutomationEvent.Changed(AutomationChanged("p1", "a1"))), received.toList())
    }

    @Test fun malformedPayloadIsDropped() {
        startCollecting()

        chat.agentEvent("automation_changed", buildJsonObject { put("x", 1) })
        change("p1", "a1")

        eventually { received.isNotEmpty() }
        assertEquals(listOf<AutomationEvent>(AutomationEvent.Changed(AutomationChanged("p1", "a1"))), received.toList())
    }

    @Test fun reconnectEmitsResync() {
        startCollecting()
        change("p1", "a1")
        eventually { received.size == 1 }
        assertEquals(listOf<AutomationEvent>(AutomationEvent.Changed(AutomationChanged("p1", "a1"))), received.toList())

        chat.closeAll(1011)

        eventually(message = { "events: $received" }) { received.size == 2 }
        assertEquals(AutomationEvent.Resync, received[1])
    }
}
