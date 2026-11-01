package dev.troupe.android.background

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.troupe.android.MainActivity
import dev.troupe.android.R
import dev.troupe.android.core.nav.Screens
import dev.troupe.android.servers.PairedServer
import dev.troupe.android.servers.PairedServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process, and so the Paired Servers' `/ws` connections, alive while the app is in the
 * background and a server stays connected: the Attention and finished-turn notifications come from
 * here. Its ongoing notification names those servers and offers Disconnect, which turns every
 * "Stay connected in background" off. It stops when the app is back in the foreground, when every
 * toggle is off or every server is removed.
 *
 * Type `specialUse`: holding a connection open for notifications is not a data sync, and Android 15
 * caps `dataSync` at 6 hours a day.
 */
@AndroidEntryPoint
class BackgroundConnectionService : Service() {
    @Inject lateinit var repository: PairedServerRepository
    @Inject lateinit var screens: Screens

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var servers: List<PairedServer> = emptyList()

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.background_channel))
                .setDescription(getString(R.string.background_channel_description))
                .build(),
        )
        // startForegroundService() needs startForeground() soon: before the server list is read.
        goForeground()
        scope.launch {
            combine(repository.servers, screens.foreground) { list, foreground -> list to foreground }.collect { (list, foreground) ->
                servers = list
                if (holdInBackground(foreground, list)) goForeground() else stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            scope.launch { repository.stopStayingConnected() }
        }
        // Not restarted after the process is killed: a restart from the background may not start in the foreground.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(
            if (servers.none { it.stayConnected }) getString(R.string.background_connected_public)
            else connectedContent(servers).let { getString(it.res, *it.args.toTypedArray()) },
        )
        .setOngoing(true)
        .setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.background_connected_public))
                .build(),
        )
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
        .addAction(
            0,
            getString(R.string.background_disconnect),
            PendingIntent.getService(
                this,
                0,
                Intent(this, BackgroundConnectionService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private companion object {
        const val CHANNEL = "background"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "dev.troupe.android.action.DISCONNECT"
    }
}
