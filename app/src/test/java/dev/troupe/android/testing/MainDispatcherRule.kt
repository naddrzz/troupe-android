package dev.troupe.android.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.Executors

/** ViewModels talk to a real (fake) server over sockets, so Main is a real thread, not virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "test-main").apply { isDaemon = true } }

    override fun starting(description: Description) = Dispatchers.setMain(executor.asCoroutineDispatcher())

    override fun finished(description: Description) {
        Dispatchers.resetMain()
        executor.shutdownNow()
    }
}
