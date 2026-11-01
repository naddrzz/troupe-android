package dev.troupe.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.troupe.android.attention.AttentionCenter
import dev.troupe.android.attention.AttentionNotifier
import javax.inject.Inject

/** Watches every Paired Server's Attention for as long as the process lives. */
@HiltAndroidApp
class TroupeApp : Application() {
    @Inject lateinit var attention: AttentionCenter
    @Inject lateinit var notifier: AttentionNotifier

    override fun onCreate() {
        super.onCreate()
        attention.start()
        notifier.start()
    }
}
