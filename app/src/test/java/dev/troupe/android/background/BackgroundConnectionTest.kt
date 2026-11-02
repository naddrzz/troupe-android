package dev.troupe.android.background

import dev.troupe.android.R
import dev.troupe.android.attention.FinishedTurn
import dev.troupe.android.attention.NotificationContent
import dev.troupe.android.attention.Text
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.servers.PairedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the background connection runs, and what its notifications say. */
class BackgroundConnectionTest {
    private fun server(name: String, stay: Boolean) = PairedServer(name.lowercase(), name, ServerAddress.of("http://10.0.0.2/"), stay)

    @Test fun runsOnlyInTheBackgroundWhileAServerStaysConnected() {
        val laptop = server("Laptop", stay = true)
        val vps = server("VPS", stay = false)

        assertTrue(holdInBackground(foreground = false, servers = listOf(laptop, vps)))
        assertFalse("the app is back", holdInBackground(foreground = true, servers = listOf(laptop, vps)))
        assertFalse("every toggle off", holdInBackground(foreground = false, servers = listOf(vps, laptop.copy(stayConnected = false))))
        assertFalse("every server removed", holdInBackground(foreground = false, servers = emptyList()))
    }

    @Test fun theOngoingNotificationNamesTheServersThatStayConnected() {
        val servers = listOf(server("Laptop", stay = true), server("VPS", stay = false), server("Desktop", stay = true))

        assertEquals(Text(R.string.background_connected, listOf("Laptop, Desktop")), connectedContent(servers, locked = false))
    }

    @Test fun whileTheAppLockIsOnTheOngoingNotificationNamesNoServer() {
        val servers = listOf(server("Laptop", stay = true))

        assertEquals(Text(R.string.background_connected_public), connectedContent(servers, locked = true))
    }

    @Test fun aFinishedTurnNamesItsSessionAndProject() {
        assertEquals(
            NotificationContent(Text(R.string.turn_finished, listOf("Fix login")), Text(R.string.attention_chat, listOf("troupe"))),
            finishedContent(FinishedTurn("srv", "s1", "troupe", "Fix login"), locked = false),
        )
        assertEquals(
            NotificationContent(Text(R.string.turn_finished_untitled), null),
            finishedContent(FinishedTurn("srv", "s1", null, null), locked = false),
        )
    }

    @Test fun whileTheAppLockIsOnAFinishedTurnNamesNothing() {
        assertEquals(
            NotificationContent(Text(R.string.turn_finished_locked), null),
            finishedContent(FinishedTurn("srv", "s1", "troupe", "Fix login"), locked = true),
        )
    }
}
