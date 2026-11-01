package dev.troupe.android

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import dev.troupe.android.attention.AttentionCenter
import dev.troupe.android.attention.AttentionNotifier
import dev.troupe.android.core.nav.Screens
import javax.inject.Inject

/** Watches every Paired Server's Attention for as long as the process lives. */
@HiltAndroidApp
class TroupeApp : Application() {
    @Inject lateinit var attention: AttentionCenter
    @Inject lateinit var notifier: AttentionNotifier
    @Inject lateinit var screens: Screens

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> screens.onForeground(true)
                Lifecycle.Event.ON_STOP -> screens.onForeground(false)
                else -> Unit
            }
        })
        attention.start()
        notifier.start()
    }
}
