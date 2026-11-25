package dev.troupe.android.workspace

import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.core.store.PersistenceKeys
import dev.troupe.android.core.store.StoreReadResult
import dev.troupe.android.core.store.WebStoreClient
import dev.troupe.android.core.theme.AppearanceMode
import dev.troupe.android.core.theme.BundledThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    /** Raw JSON object from the server so unknown desktop fields survive partial Android updates. */
    @Volatile private var rawServerJson: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
    private var storeClient: WebStoreClient? = null
    private var watchJob: Job? = null

    fun attach(client: WebStoreClient, scope: CoroutineScope) {
        watchJob?.cancel()
        storeClient = client
        watchJob = scope.launch {
            when (val read = client.read(PersistenceKeys.APP_SETTINGS)) {
                is StoreReadResult.Found -> applyJson(read.data)
                else -> Unit
            }
            client.watch(PersistenceKeys.APP_SETTINGS).collect { element ->
                if (element != null && element !is JsonNull) {
                    applyJson(element)
                }
            }
        }
    }

    fun detach() {
        watchJob?.cancel()
        watchJob = null
        storeClient = null
    }

    fun update(scope: CoroutineScope, transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        val client = storeClient ?: return
        scope.launch {
            val encoded = TroupeJson.encodeToJsonElement(AppSettings.serializer(), next) as? JsonObject ?: return@launch
            val merged = JsonObject(rawServerJson + encoded)
            rawServerJson = merged
            client.write(PersistenceKeys.APP_SETTINGS, merged)
        }
    }

    private fun applyJson(element: kotlinx.serialization.json.JsonElement) {
        val obj = element as? JsonObject ?: return
        rawServerJson = obj
        val decoded = runCatching {
            TroupeJson.decodeFromJsonElement(AppSettings.serializer(), obj)
        }.getOrNull() ?: return
        _settings.update { decoded }
    }
}
