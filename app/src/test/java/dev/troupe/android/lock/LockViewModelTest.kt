package dev.troupe.android.lock

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import dev.troupe.android.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** App lock, driven the way MainActivity and the Settings screen drive it, with a fake clock and authenticator. */
class LockViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy {
        LockSettingsStore(
            DataStoreFactory.create(
                OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(tmp.root, "troupe.preferences_pb").toOkioPath() },
                scope = scope,
            ),
        )
    }
    private var now = 0L
    private val auth = FakeAuthenticator()

    @After fun tearDown() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    /** A fresh ViewModel is a fresh launch of the app; waits until the stored setting has been read. */
    private fun launch(): LockViewModel {
        val vm = LockViewModel(store) { now }
        settled(vm)
        return vm
    }

    private fun settled(vm: LockViewModel): LockUiState =
        runBlocking { withTimeout(5_000) { vm.state.first { it.gate != Gate.Loading } } }

    private fun onMain(block: () -> Unit) = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun awaitEnabled(vm: LockViewModel, enabled: Boolean) =
        runBlocking { withTimeout(5_000) { vm.state.first { it.enabled == enabled } } }

    private fun awaitGate(vm: LockViewModel, gate: Gate) =
        runBlocking { withTimeout(5_000) { vm.state.first { it.gate == gate } } }

    private fun enableLock(vm: LockViewModel) {
        onMain { vm.setEnabled(true, auth) }
        awaitEnabled(vm, true)
    }

    /** Background for [millis] on the fake clock, then come back. */
    private fun away(vm: LockViewModel, millis: Long) {
        onMain { vm.onBackground() }
        now += millis
        onMain { vm.onForeground() }
    }

    @Test fun lockIsOffByDefaultAndTheAppOpensUnlocked() {
        val state = settled(launch())
        assertFalse(state.enabled)
        assertEquals(LockDelay.OneMinute, state.delay)
        assertEquals(Gate.Unlocked, state.gate)
    }

    @Test fun turningTheLockOnNeedsASuccessfulPrompt() {
        val vm = launch()
        auth.succeeds = false
        onMain { vm.setEnabled(true, auth) }
        runBlocking { withTimeout(5_000) { while (auth.prompts == 0) kotlinx.coroutines.yield() } }
        assertFalse(settled(vm).enabled)

        auth.succeeds = true
        enableLock(vm)
        assertEquals(2, auth.prompts)
        assertEquals(Gate.Unlocked, settled(vm).gate)
    }

    @Test fun aDeviceWithoutBiometricsOrScreenLockCannotTurnItOn() {
        val vm = launch()
        auth.availability = LockAvailability.NoScreenLock
        onMain { vm.setEnabled(true, auth) }
        assertEquals(0, auth.prompts)
        assertFalse(settled(vm).enabled)
    }

    @Test fun withTheLockOnTheAppOpensLockedAndUnlocksOnlyOnSuccess() {
        enableLock(launch())

        val relaunched = launch()
        assertEquals(Gate.Locked, settled(relaunched).gate)

        auth.succeeds = false
        onMain { relaunched.unlock(auth) }
        runBlocking { withTimeout(5_000) { while (auth.prompts < 2) kotlinx.coroutines.yield() } }
        assertEquals(Gate.Locked, settled(relaunched).gate)

        auth.succeeds = true
        onMain { relaunched.unlock(auth) }
        awaitGate(relaunched, Gate.Unlocked)
    }

    /** Removing the screen lock needs the old credential, so whoever did it may open Troupe instead of being stuck. */
    @Test fun aScreenLockRemovedAfterwardsDoesNotLockTheOwnerOut() {
        enableLock(launch())
        auth.availability = LockAvailability.NoScreenLock

        val relaunched = launch()
        onMain { relaunched.unlock(auth) }

        awaitGate(relaunched, Gate.Unlocked)
        assertEquals(1, auth.prompts)
    }

    @Test fun defaultDelayLocksAfterMoreThanOneMinuteInTheBackground() {
        val vm = launch()
        enableLock(vm)

        away(vm, 59_000)
        assertEquals(Gate.Unlocked, settled(vm).gate)

        away(vm, 61_000)
        assertEquals(Gate.Locked, settled(vm).gate)
    }

    @Test fun immediatelyLocksOnAnyReturnFromBackground() {
        val vm = launch()
        enableLock(vm)
        onMain { vm.setDelay(LockDelay.Immediately) }
        runBlocking { withTimeout(5_000) { vm.state.first { it.delay == LockDelay.Immediately } } }

        away(vm, 1)
        assertEquals(Gate.Locked, settled(vm).gate)
    }

    @Test fun fiveMinutesKeepsTheAppOpenForShorterBreaks() {
        val vm = launch()
        enableLock(vm)
        onMain { vm.setDelay(LockDelay.FiveMinutes) }
        runBlocking { withTimeout(5_000) { vm.state.first { it.delay == LockDelay.FiveMinutes } } }

        away(vm, 4 * 60_000)
        assertEquals(Gate.Unlocked, settled(vm).gate)

        away(vm, 5 * 60_000 + 1)
        assertEquals(Gate.Locked, settled(vm).gate)
    }

    @Test fun theDelayIsStored() {
        val vm = launch()
        onMain { vm.setDelay(LockDelay.FiveMinutes) }
        runBlocking { withTimeout(5_000) { vm.state.first { it.delay == LockDelay.FiveMinutes } } }

        assertEquals(LockDelay.FiveMinutes, settled(launch()).delay)
    }

    @Test fun leavingForTheDeviceCredentialScreenDoesNotLockAgain() {
        val vm = launch()
        enableLock(vm)
        onMain { vm.setDelay(LockDelay.Immediately) }
        runBlocking { withTimeout(5_000) { vm.state.first { it.delay == LockDelay.Immediately } } }
        away(vm, 1)
        assertEquals(Gate.Locked, settled(vm).gate)

        val answer = CompletableDeferred<Boolean>()
        auth.answer = { answer.await() }
        onMain { vm.unlock(auth) }
        // The system's PIN screen covers Troupe while the prompt is open; success may arrive before Troupe is back.
        onMain { vm.onBackground() }
        now += 5_000
        answer.complete(true)
        awaitGate(vm, Gate.Unlocked)
        onMain { vm.onForeground() }

        assertEquals(Gate.Unlocked, settled(vm).gate)
    }

    @Test fun turningTheLockOffStopsLocking() {
        val vm = launch()
        enableLock(vm)
        onMain { vm.setEnabled(false, auth) }
        awaitEnabled(vm, false)

        away(vm, 10 * 60_000)
        assertEquals(Gate.Unlocked, settled(vm).gate)
        assertEquals(Gate.Unlocked, settled(launch()).gate)
        assertTrue(auth.prompts == 1)
    }
}

private class FakeAuthenticator : Authenticator {
    var availability = LockAvailability.Available
    var succeeds = true
    var answer: suspend () -> Boolean = { succeeds }
    @Volatile var prompts = 0

    override fun availability() = availability

    override suspend fun authenticate(): Boolean {
        prompts++
        return answer()
    }
}
