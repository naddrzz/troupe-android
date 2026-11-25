package dev.troupe.android.workspace

import dev.troupe.android.chat.net.ChatConnection
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.core.store.PersistenceKeys
import dev.troupe.android.core.store.StoreReadResult
import dev.troupe.android.core.store.WebStoreClient
import dev.troupe.android.core.theme.AppearanceMode
import dev.troupe.android.core.theme.BundledThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subset of Troupe's `settings/app` shared LWW record used by Android.
 * Field names are camelCase and identical to `src/renderer/types/settings.ts` so changes on
 * desktop/web sync seamlessly to Android and vice versa without dropping unknown fields.
 */
@Serializable
data class AppSettings(
    val colorTheme: String = BundledThemes.DEFAULT_THEME_ID,
    val appearanceMode: String = "system",
    val terminalFontSize: Int = 14,
    val terminalFontFamily: String = "Paper Mono",
    val confirmTerminalClose: Boolean = true,
    val editorAutoSave: Boolean = false,
    val editorAutoSaveDelayMs: Int = 1000,
    val notifyOnAgentChatTurnFinished: Boolean = true,
    val notifyOnAgentChatNeedsYou: Boolean = true,
    val notifyOnTerminalIdle: Boolean = false,
    val defaultProjectColor: String = "blue",
) {
    fun resolvedAppearance(): AppearanceMode = AppearanceMode.fromWire(appearanceMode)
}

/**
 * Live holder for `shared/lww/settings/app`, observed by `MainActivity` to drive [dev.troupe.android.core.theme.TroupeTheme]
 * and by Settings/Terminal/Editor screens. Preserves unknown desktop-only JSON fields on write!
 */
@Singleton
class AppSettingsStore @Inject constructor() {
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Cached `settings/app` by Paired Server ID so background notifications use each server's own preference. */
    private val perServer = java.util.concurrent.ConcurrentHashMap<String, AppSettings>()
    @Volatile private var attachedServerId: String? = null

    /** Raw JSON object from the server so unknown desktop fields survive partial Android updates. */
    @Volatile private var rawServerJson: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
    private var storeClient: WebStoreClient? = null
    private var watchJob: Job? = null

    /** Returns the last known settings for [serverId], or defaults when that server has not been loaded yet. */
    fun settingsFor(serverId: String): AppSettings = perServer[serverId] ?: AppSettings()

    /** Drops cached settings for a removed Paired Server. */
    fun forget(serverId: String) {
        perServer.remove(serverId)
    }

    /**
     * Reads and watches `shared/lww/settings/app` for a Paired Server so [settingsFor] reflects
     * that server's preferences even when it is not currently the active workspace server.
     */
    fun observeServer(serverId: String, connection: ChatConnection, scope: CoroutineScope): Job =
        scope.launch {
            coroutineScope {
                val store = WebStoreClient(connection, connection.watchHub, deviceId = "", scope = this)
                launch {
                    connection.generation.collect { gen ->
                        if (gen <= 0) return@collect
                        when (val read = store.read(PersistenceKeys.APP_SETTINGS)) {
                            is StoreReadResult.Found -> applyJson(serverId, read.data)
                            else -> Unit
                        }
                    }
                }
                launch {
                    store.watch(PersistenceKeys.APP_SETTINGS).collect { element ->
                        if (element != null && element !is JsonNull) {
                            applyJson(serverId, element)
                        }
                    }
                }
            }
        }

    fun attach(serverId: String, client: WebStoreClient, scope: CoroutineScope) {
        watchJob?.cancel()
        attachedServerId = serverId
        storeClient = client
        rawServerJson = emptyMap()
        _settings.value = perServer[serverId] ?: AppSettings()
        watchJob = scope.launch {
            when (val read = client.read(PersistenceKeys.APP_SETTINGS)) {
                is StoreReadResult.Found -> applyJson(serverId, read.data)
                else -> Unit
            }
            client.watch(PersistenceKeys.APP_SETTINGS).collect { element ->
                if (element != null && element !is JsonNull) {
                    applyJson(serverId, element)
                }
            }
        }
    }

    fun detach() {
        watchJob?.cancel()
        watchJob = null
        attachedServerId = null
        storeClient = null
        rawServerJson = emptyMap()
        _settings.value = AppSettings()
    }

    fun update(scope: CoroutineScope, transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        attachedServerId?.let { perServer[it] = next }
        val client = storeClient ?: return
        scope.launch {
            val encoded = TroupeJson.encodeToJsonElement(AppSettings.serializer(), next) as? JsonObject ?: return@launch
            val merged = JsonObject(rawServerJson + encoded)
            rawServerJson = merged
            client.write(PersistenceKeys.APP_SETTINGS, merged)
        }
    }

    private fun applyJson(serverId: String, element: kotlinx.serialization.json.JsonElement) {
        val obj = element as? JsonObject ?: return
        val decoded = runCatching {
            TroupeJson.decodeFromJsonElement(AppSettings.serializer(), obj)
        }.getOrNull() ?: return
        perServer[serverId] = decoded
        if (attachedServerId == serverId) {
            rawServerJson = obj
            _settings.update { decoded }
        }
    }
}
