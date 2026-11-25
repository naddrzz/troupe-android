package dev.troupe.android.agents

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A catalog agent's `status` (Troupe's `acp/catalog.rs`); anything unknown is [Unavailable]. */
enum class AgentStatus {
    Ready, InstallRequired, NeedsRuntime, ManualInstall, Unavailable;

    companion object {
        fun fromWire(value: String?): AgentStatus = when (value) {
            "ready" -> Ready
            "install-required" -> InstallRequired
            "needs-runtime" -> NeedsRuntime
            "manual-install" -> ManualInstall
            else -> Unavailable
        }
    }
}

/** How the host starts an agent it already has: the catalog's `installed` block. */
data class InstalledAgent(val command: String, val args: List<String>)

/** An `npx` or `uvx` distribution: the package to run, with its arguments and environment. */
data class PackageDist(val pkg: String, val args: List<String>, val env: Map<String, String>)

/** One entry of `list_acp_catalog`. */
data class CatalogAgent(
    val id: String,
    val name: String,
    val description: String,
    val status: AgentStatus,
    val installed: InstalledAgent?,
    val npx: PackageDist?,
    val uvx: PackageDist?,
    val runtimeRequirements: List<String>,
) {
    /** True when the missing runtime is `uv` rather than Node.js. */
    val needsUv: Boolean get() = npx == null && uvx != null
}

/**
 * A launch config as Troupe saves it under `acp/agents`. `spawn_agent` takes the six fields from
 * [configId] on; the server refuses `id` and `templateId` there.
 */
@Serializable
data class AgentConfig(
    val id: String,
    val templateId: String? = null,
    val configId: String = id,
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val allowTerminal: Boolean = false,
)

/** The config id Troupe gives a catalog agent. It is an identity only: the server never resolves it. */
fun registryConfigId(catalogId: String): String = "acp-registry:$catalogId"

/** Parses a `list_acp_catalog` reply; entries without an id are skipped, never a crash. */
fun parseCatalog(payload: JsonElement?): List<CatalogAgent> {
    val agents = (payload as? JsonObject)?.get("agents") as? JsonArray ?: return emptyList()
    return agents.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val id = obj.string("id") ?: return@mapNotNull null
        val dist = obj["distribution"] as? JsonObject
        CatalogAgent(
            id = id,
            name = obj.string("name") ?: id,
            description = obj.string("description").orEmpty(),
            status = AgentStatus.fromWire(obj.string("status")),
            installed = (obj["installed"] as? JsonObject)?.let { inst ->
                inst.string("command")?.let { InstalledAgent(it, inst.strings("args")) }
            },
            npx = (dist?.get("npx") as? JsonObject)?.toPackageDist(),
            uvx = (dist?.get("uvx") as? JsonObject)?.toPackageDist(),
            runtimeRequirements = obj.strings("runtimeRequirements"),
        )
    }
}

/**
 * The config that starts this agent, or null when the host cannot start it yet (Troupe's
 * `resolveSupportedAcpAgents`): the host's installed binary first, then `npx`, then `uvx`.
 */
fun CatalogAgent.deriveConfig(): AgentConfig? {
    if (status != AgentStatus.Ready) return null
    val configId = registryConfigId(id)
    installed?.let { return AgentConfig(configId, id, configId, name, it.command, it.args) }
    npx?.let { return AgentConfig(configId, id, configId, name, "npx", listOf("-y", it.pkg) + it.args, it.env) }
    uvx?.let { return AgentConfig(configId, id, configId, name, "uvx", listOf(it.pkg) + it.args, it.env) }
    return null
}

/** The config an install produced (`install_acp_agent`'s `{command, args}`). */
fun CatalogAgent.installedConfig(command: String, args: List<String>): AgentConfig {
    val configId = registryConfigId(id)
    return AgentConfig(configId, id, configId, name, command, args)
}

/** Troupe's featured agents, in the order onboarding lists them. */
val FEATURED_AGENT_IDS = listOf("codex-acp", "claude-acp", "gemini", "cursor", "opencode", "pi-acp")

/** Featured agents in their fixed order, then the rest as the catalog lists them. */
fun featuredFirst(agents: List<CatalogAgent>): List<CatalogAgent> {
    val featured = FEATURED_AGENT_IDS.mapNotNull { id -> agents.firstOrNull { it.id == id } }
    return featured + agents.filterNot { it.id in FEATURED_AGENT_IDS }
}

/** The agent a new chat starts with: the last one picked, else the first featured one, else any. */
fun pickDefault(launchable: List<AgentConfig>, lastSelected: String?): AgentConfig? =
    launchable.firstOrNull { it.id == lastSelected }
        ?: FEATURED_AGENT_IDS.firstNotNullOfOrNull { id -> launchable.firstOrNull { it.id == registryConfigId(id) } }
        ?: launchable.firstOrNull()

private fun JsonObject.toPackageDist(): PackageDist? {
    val pkg = string("package") ?: return null
    val env = (this["env"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
    }.toMap()
    return PackageDist(pkg, strings("args"), env)
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
