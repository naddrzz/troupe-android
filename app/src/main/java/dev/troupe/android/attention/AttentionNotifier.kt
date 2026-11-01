package dev.troupe.android.attention

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.troupe.android.R
import dev.troupe.android.background.finishedContent
import dev.troupe.android.core.nav.ChatLink
import dev.troupe.android.core.nav.Screens
import dev.troupe.android.core.nav.openChatIntent
import dev.troupe.android.lock.LockSettingsStore
import dev.troupe.android.servers.PairedServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts new Attention on the "Needs attention" channel ([mayAlert] decides when) and takes a
 * notification down once its Attention is gone; in the background it also posts "<Session>
 * finished" for servers that stay connected ([mayNotifyFinished]). Every notification is
 * `VISIBILITY_PRIVATE`, with a public version without names; while the app lock is on the private
 * one says no more. Tapping one opens its Agent Chat.
 *
 * On Android 13+ the first notification without permission, or turning "Stay connected in
 * background" on, makes [permissionWanted] ask the app to show its rationale, once; what was held
 * meanwhile is posted by [onPermissionResult].
 */
@Singleton
class AttentionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val center: AttentionCenter,
    private val screens: Screens,
    private val lock: LockSettingsStore,
    private val repository: PairedServerRepository,
    private val store: DataStore<Preferences>,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val manager = NotificationManagerCompat.from(context)
    private val alerts = AttentionAlerts()
    private val held = LinkedHashMap<String, AttentionItem>()
    /** Ids of the Paired Servers that stay connected in the background. */
    private var stayConnected: Set<String> = emptySet()
    private val _permissionWanted = MutableStateFlow(false)
    /** The app should explain and ask for the notification permission now. */
    val permissionWanted: StateFlow<Boolean> = _permissionWanted.asStateFlow()

    fun start() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.attention_channel))
                .setDescription(context.getString(R.string.attention_channel_description))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(TURNS_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.turns_channel))
                .setDescription(context.getString(R.string.turns_channel_description))
                .build(),
        )
        scope.launch {
            repository.servers.collect { servers ->
                stayConnected = servers.filter { it.stayConnected }.map { it.id }.toSet()
                if (stayConnected.isNotEmpty() && !canPost()) askOnce()
            }
        }
        scope.launch {
            center.servers.collect { servers ->
                val changes = alerts.update(servers)
                changes.gone.forEach { (serverId, key) ->
                    held.remove(tag(serverId, key))
                    manager.cancel(tag(serverId, key), 0)
                }
                changes.added.filter { mayAlert(it, screens.foreground.value, screens.current.value, stayConnected) }.forEach { notify(it) }
            }
        }
        scope.launch {
            center.finished.collect { turn ->
                if (mayNotifyFinished(turn, screens.foreground.value, stayConnected)) notifyFinished(turn)
            }
        }
    }

    /** The user answered the rationale or the system prompt; asked once either way. */
    fun onPermissionResult(granted: Boolean) {
        scope.launch {
            store.edit { it[ASKED] = true }
            _permissionWanted.value = false
            val waiting = held.values.toList()
            held.clear()
            if (granted) waiting.forEach { notify(it) }
        }
    }

    @SuppressLint("MissingPermission") // canPost() checks it.
    private suspend fun notify(item: AttentionItem) {
        if (!canPost()) {
            if (askOnce()) held[tag(item.serverId, item.key)] = item
            return
        }
        val locked = lock.settings.first().enabled
        val content = attentionContent(item, locked)
        val tag = tag(item.serverId, item.key)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title.resolve())
            .setContentText(content.text?.resolve())
            .apply { if (!locked) setSubText(repository.server(item.serverId)?.name) }
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion())
            .setAutoCancel(true)
            .setContentIntent(openChatIntent(context, ChatLink(item.serverId, item.sessionId, item.sessionTitle), tag.hashCode()))
            .build()
        manager.notify(tag, 0, notification)
    }

    @SuppressLint("MissingPermission") // canPost() checks it.
    private suspend fun notifyFinished(turn: FinishedTurn) {
        if (!canPost()) return
        val locked = lock.settings.first().enabled
        val content = finishedContent(turn, locked)
        val tag = "${turn.serverId}/finished/${turn.sessionId}"
        val notification = NotificationCompat.Builder(context, TURNS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title.resolve())
            .setContentText(content.text?.resolve())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(TURNS_CHANNEL, R.string.turn_finished_locked))
            .setAutoCancel(true)
            .setContentIntent(openChatIntent(context, ChatLink(turn.serverId, turn.sessionId, turn.sessionTitle), tag.hashCode()))
            .build()
        manager.notify(tag, 0, notification)
    }

    /** On Android 13+, asks the app to explain and request the permission unless it already did; true when it will. */
    private suspend fun askOnce(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || store.data.first()[ASKED] == true) return false
        _permissionWanted.value = true
        return true
    }

    private fun publicVersion(channel: String = CHANNEL, @StringRes title: Int = R.string.attention_needs_attention) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .build()

    private fun canPost(): Boolean = manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun Text.resolve(): String = context.getString(res, *args.toTypedArray())

    private fun tag(serverId: String, key: String) = "$serverId/$key"

    companion object {
        const val CHANNEL = "attention"
        const val TURNS_CHANNEL = "turns"
        private val ASKED = booleanPreferencesKey("notifications_permission_asked")
    }
}
