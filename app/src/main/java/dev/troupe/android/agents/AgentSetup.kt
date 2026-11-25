package dev.troupe.android.agents

import dev.troupe.android.chat.net.ChatSocket
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.core.store.PersistenceKeys
import dev.troupe.android.core.store.StoreReadResult
import dev.troupe.android.core.store.StoreWriteResult
import dev.troupe.android.core.store.WebStoreClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** What the active Paired Server can run, and what the user has set up on it. */
data class AgentSetupState(
    /** False until the first catalog read ended, in success or not. */
    val loaded: Boolean = false,
    val catalog: List<CatalogAgent> = emptyList(),
    /** The configs under `acp/agents`, shared with desktop and browser. */
    val saved: List<AgentConfig> = emptyList(),
    /** Config id of the agent last picked on this device. */
    val lastSelected: String? = null,
    /** Catalog ids whose install is running. */
    val installing: Set<String> = emptySet(),
    val error: String? = null,
) {
    /** Saved configs first, then every ready catalog agent that is not saved yet. */
    val launchable: List<AgentConfig>
        get() = saved + catalog.mapNotNull { it.deriveConfig() }.filter { derived -> saved.none { it.id == derived.id } }

    val selected: AgentConfig? get() = pickDefault(launchable, lastSelected)

    fun isReady(agent: CatalogAgent): Boolean = launchable.any { it.id == registryConfigId(agent.id) }
}

/**
 * The agents of one `/ws` connection: the host's catalog (`list_acp_catalog`), the configs saved
 * under `acp/agents` and this device's last pick. Troupe only starts an agent whose config is
 * saved, so installing one and launching a ready one both [save] it first.
 */
class AgentSetup(
    private val socket: ChatSocket,
    private val store: WebStoreClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AgentSetupState())
    val state: StateFlow<AgentSetupState> = _state.asStateFlow()
    private val saveMutex = Mutex()

    init {
        scope.launch {
            store.watch(PersistenceKeys.ACP_AGENTS).collect { value ->
                if (value != null) _state.update { it.copy(saved = parseSaved(value)) }
            }
        }
    }

    /** Reads the catalog, the saved configs and the last pick again. */
    suspend fun refresh() {
        val catalog = try {
            socket.request("list_acp_catalog", JsonObject(emptyMap()), null, CATALOG_TIMEOUT)
        } catch (e: IOException) {
            return _state.update { it.copy(loaded = true, error = "Could not reach the server.") }
        }
        val saved = when (val read = store.read(PersistenceKeys.ACP_AGENTS)) {
            is StoreReadResult.Found -> parseSaved(read.data)
            StoreReadResult.NotFound -> emptyList()
            is StoreReadResult.Error -> null
        }
        val last = (store.read(PersistenceKeys.LAST_SELECTED_AGENT) as? StoreReadResult.Found)?.data
        _state.update {
            it.copy(
                loaded = true,
                catalog = if (catalog.ok) parseCatalog(catalog.payload) else it.catalog,
                saved = saved ?: it.saved,
                // A pick made here wins: its write may not have reached the host yet.
                lastSelected = it.lastSelected ?: (last as? JsonObject)?.string("agentId"),
                error = if (catalog.ok) null else catalog.message ?: "Could not list agents.",
            )
        }
    }

    /** Has the host download and install a catalog agent, then saves and picks its config. */
    suspend fun install(agent: CatalogAgent) {
        if (agent.id in _state.value.installing) return
        _state.update { it.copy(installing = it.installing + agent.id, error = null) }
        try {
            val error = requestInstall(agent)
            refresh()
            _state.update { it.copy(error = error ?: it.error) }
        } finally {
            // Also when the caller is cancelled: a stale id would refuse every later install.
            _state.update { it.copy(installing = it.installing - agent.id) }
        }
    }

    /** Null when the agent is installed, saved and picked; otherwise what to tell the user. */
    private suspend fun requestInstall(agent: CatalogAgent): String? =
        try {
            val reply = socket.request(
                "install_acp_agent",
                buildJsonObject { put("agentId", agent.id) },
                UUID.randomUUID().toString(),
                INSTALL_TIMEOUT,
            )
            val outcome = reply.payload as? JsonObject
            val command = outcome?.string("command")
            when {
                !reply.ok -> reply.message ?: "Could not install ${agent.name}."
                command == null -> "${agent.name} is still installing. Check again in a moment."
                else -> {
                    val config = agent.installedConfig(command, outcome.strings("args"))
                    if (save(config)) {
                        select(config.id)
                        null
                    } else {
                        // Installed on the host, but without a saved config it cannot start a chat.
                        _state.value.error ?: "${agent.name} is installed, but saving it failed. Check again to retry."
                    }
                }
            }
        } catch (e: IOException) {
            "Lost the connection while installing ${agent.name}. Check again in a moment."
        }

    /**
     * Adds [config] to `acp/agents` unless one with its id is there. The list is read first, so
     * entries and fields this app does not know are written back as they were.
     */
    suspend fun save(config: AgentConfig): Boolean = saveMutex.withLock {
        val current = when (val read = store.read(PersistenceKeys.ACP_AGENTS)) {
            is StoreReadResult.Found -> read.data as? JsonArray ?: JsonArray(emptyList())
            StoreReadResult.NotFound -> JsonArray(emptyList())
            is StoreReadResult.Error -> {
                _state.update { it.copy(error = read.message ?: "Could not save the agent.") }
                return false
            }
        }
        if (current.any { (it as? JsonObject)?.string("id") == config.id }) {
            _state.update { it.copy(saved = parseSaved(current)) }
            return true
        }
        val next = JsonArray(current + TroupeJson.encodeToJsonElement(AgentConfig.serializer(), config))
        val written = store.write(PersistenceKeys.ACP_AGENTS, next, immediate = true)
        if (written is StoreWriteResult.Error) {
            _state.update { it.copy(error = written.message ?: "Could not save the agent.") }
            return false
        }
        _state.update { it.copy(saved = parseSaved(next)) }
        true
    }

    /** Remembers [configId] as the agent new chats on this device start with. */
    fun select(configId: String) {
        _state.update { it.copy(lastSelected = configId) }
        scope.launch {
            store.write(
                PersistenceKeys.LAST_SELECTED_AGENT,
                buildJsonObject {
                    put("agentId", configId)
                    put("mode", "acp")
                },
                immediate = true,
            )
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun parseSaved(value: JsonElement): List<AgentConfig> = (value as? JsonArray).orEmpty().mapNotNull {
        runCatching { TroupeJson.decodeFromJsonElement(AgentConfig.serializer(), it) }.getOrNull()
    }

    private companion object {
        val CATALOG_TIMEOUT = 30.seconds
        /** Troupe's own budget: 120 s download plus 180 s install on the host. */
        val INSTALL_TIMEOUT = 330.seconds
    }
}
