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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.ConcurrentHashMap
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
    private data class CachedServerSettings(
        val settings: AppSettings,
        val rawJson: Map<String, JsonElement>,
    )

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Cached `settings/app` + raw JSON by Paired Server ID so notifications and edits preserve server state. */
    private val perServer = ConcurrentHashMap<String, CachedServerSettings>()
    /** Server IDs whose initial `settings/app` read or watch delivery has completed. */
    private val loadedServers = MutableStateFlow<Set<String>>(emptySet())
    private val writeMutex = Mutex()
    @Volatile private var attachedServerId: String? = null
    private var storeClient: WebStoreClient? = null
    private var watchJob: Job? = null

    /** Returns the last known settings for [serverId], or defaults when that server has not been loaded yet. */
    fun settingsFor(serverId: String): AppSettings = perServer[serverId]?.settings ?: AppSettings()

    /** True once [serverId] has completed its initial `settings/app` load (or reported unsupported/missing). */
    fun isLoaded(serverId: String): Boolean = serverId in loadedServers.value

    /** Waits briefly for [serverId]'s initial `settings/app` read to complete before returning its settings. */
    suspend fun awaitSettingsFor(serverId: String, timeoutMs: Long = 3_000L): AppSettings {
        if (serverId !in loadedServers.value) {
            withTimeoutOrNull(timeoutMs) {
                loadedServers.first { serverId in it }
            }
        }
        return settingsFor(serverId)
    }

    /** Drops cached settings for a removed Paired Server. */
    fun forget(serverId: String) {
        perServer.remove(serverId)
        loadedServers.update { it - serverId }
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
                        val read = store.read(PersistenceKeys.APP_SETTINGS)
                        writeMutex.withLock {
                            applyReadResult(serverId, read)
                        }
                    }
                }
                launch {
                    store.watch(PersistenceKeys.APP_SETTINGS).collect { element ->
                        writeMutex.withLock {
                            applyWatchElement(serverId, element)
                        }
                    }
                }
            }
        }

    fun attach(serverId: String, client: WebStoreClient, scope: CoroutineScope) {
        watchJob?.cancel()
        attachedServerId = serverId
        storeClient = client
        _settings.value = perServer[serverId]?.settings ?: AppSettings()
        watchJob = scope.launch {
            val initialRead = client.read(PersistenceKeys.APP_SETTINGS)
            writeMutex.withLock {
                if (serverId !in loadedServers.value) {
                    applyReadResult(serverId, initialRead)
                }
            }
            client.watch(PersistenceKeys.APP_SETTINGS).collect { element ->
                writeMutex.withLock {
                    applyWatchElement(serverId, element)
                }
            }
        }
    }

    fun detach() {
        watchJob?.cancel()
        watchJob = null
        attachedServerId = null
        storeClient = null
        _settings.value = AppSettings()
    }

    fun update(scope: CoroutineScope, transform: (AppSettings) -> AppSettings) {
        val serverId = attachedServerId
        val next = transform(_settings.value)
        _settings.value = next
        if (serverId != null) {
            val cachedRaw = perServer[serverId]?.rawJson.orEmpty()
            perServer[serverId] = CachedServerSettings(next, cachedRaw)
        }
        val client = storeClient ?: return
        scope.launch {
            writeMutex.withLock {
                val baseRaw = if (serverId != null && serverId !in loadedServers.value) {
                    when (val read = client.read(PersistenceKeys.APP_SETTINGS)) {
                        is StoreReadResult.Found -> (read.data as? JsonObject).orEmpty()
                        StoreReadResult.NotFound -> emptyMap()
                        is StoreReadResult.Error -> {
                            // Never write with an empty merge base on transport failure; preserve desktop fields.
                            if (read.code == "WS_ERROR") return@withLock
                            emptyMap()
                        }
                    }
                } else {
                    serverId?.let { perServer[it]?.rawJson }.orEmpty()
                }
                val encoded = TroupeJson.encodeToJsonElement(AppSettings.serializer(), next) as? JsonObject
                    ?: return@withLock
                val merged = JsonObject(baseRaw + encoded)
                if (serverId != null) {
                    perServer[serverId] = CachedServerSettings(next, merged)
                    loadedServers.update { it + serverId }
                }
                client.write(PersistenceKeys.APP_SETTINGS, merged)
            }
        }
    }

    private fun applyReadResult(serverId: String, read: StoreReadResult) {
        when (read) {
            is StoreReadResult.Found -> {
                applyJson(serverId, read.data)
                loadedServers.update { it + serverId }
            }
            StoreReadResult.NotFound -> {
                clearServerCache(serverId)
                loadedServers.update { it + serverId }
            }
            is StoreReadResult.Error -> {
                // Non-transport errors (e.g. `unsupported` on older hosts) mean defaults apply immediately.
                if (read.code != "WS_ERROR") {
                    loadedServers.update { it + serverId }
                }
            }
        }
    }

    private fun applyWatchElement(serverId: String, element: JsonElement?) {
        if (element == null || element is JsonNull) {
            clearServerCache(serverId)
        } else {
            applyJson(serverId, element)
        }
        loadedServers.update { it + serverId }
    }

    private fun clearServerCache(serverId: String) {
        perServer.remove(serverId)
        if (attachedServerId == serverId) {
            _settings.value = AppSettings()
        }
    }

    private fun applyJson(serverId: String, element: JsonElement) {
        val obj = element as? JsonObject ?: return
        val decoded = runCatching {
            TroupeJson.decodeFromJsonElement(AppSettings.serializer(), obj)
        }.getOrNull() ?: return
        perServer[serverId] = CachedServerSettings(decoded, obj)
        if (attachedServerId == serverId) {
            _settings.update { decoded }
        }
    }
}
