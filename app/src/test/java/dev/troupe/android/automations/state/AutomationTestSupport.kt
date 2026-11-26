package dev.troupe.android.automations.state

import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.eventually
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How many requests the fake server received for [path]. */
internal fun FakeTroupeServer.hits(path: String) = httpRequests.count { it.url.encodedPath == path }

/**
 * Returns once the `agents` watch is up, [path] has been requested, and its request count stopped moving for half a
 * second, so a reload caused by the subscription itself cannot land later.
 */
internal fun FakeTroupeServer.settle(path: String) {
    eventually { chat.watching("agents") }
    eventually { hits(path) >= 1 }
    var last = -1
    while (hits(path) != last) {
        last = hits(path)
        Thread.sleep(500)
    }
}

/** Sends an `automation_changed` event (a null [automationId] is project-wide) on the `agents` watch. */
internal fun FakeTroupeServer.automationChanged(projectId: String, automationId: String?) =
    chat.agentEvent("automation_changed", buildJsonObject {
        put("project_id", projectId)
        put("automation_id", automationId)
    })
