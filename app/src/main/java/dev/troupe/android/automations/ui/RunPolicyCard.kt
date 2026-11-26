package dev.troupe.android.automations.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.automations.protocol.AgentPolicy
import dev.troupe.android.automations.protocol.AgentTool
import dev.troupe.android.automations.protocol.Automation
import dev.troupe.android.automations.protocol.McpToolPolicy
import dev.troupe.android.automations.protocol.NodeId
import dev.troupe.android.automations.protocol.RunPolicy
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.theme.tabularNums

/**
 * What the automation will do once armed, exactly as the host derived it (`RunPolicy`), as the web's `RunPolicyCard`
 * shows it. Read-only: approving a revision is done from Troupe on a computer for now, which the header's hint says.
 */
@Composable
fun RunPolicyCard(automation: Automation, policy: RunPolicy, modifier: Modifier = Modifier) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val resources = LocalResources.current
    val labels = nodeLabels(automation.graph)
    Column(modifier = modifier.automationCard()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AutomationSectionHeading(stringResource(R.string.automations_policy_title), Modifier.weight(1f))
            Text(
                text = stringResource(R.string.automations_policy_revision, automation.revision),
                style = typo.labelMedium.tabularNums(),
                color = palette.mutedForeground,
            )
        }
        PolicyRow(R.string.automations_policy_agents) {
            if (policy.agents.isEmpty()) {
                NoneText()
            } else {
                policy.agents.forEach { agent ->
                    PolicyText(nodeDetail(resources, labelOrId(labels, agent.nodeId), agentDetail(resources, agent)))
                }
            }
        }
        PolicyRow(R.string.automations_policy_secrets) {
            PerNodeList(policy.secrets.map { it.nodeId to it.names }, labels, resources)
        }
        PolicyRow(R.string.automations_policy_hosts) {
            PerNodeList(policy.outboundHosts.map { it.nodeId to it.hosts }, labels, resources)
        }
        if (policy.mcpTools.isNotEmpty()) {
            PolicyRow(R.string.automations_policy_mcp_tools) {
                policy.mcpTools.forEach { call ->
                    PolicyText(nodeDetail(resources, labelOrId(labels, call.nodeId), mcpCallDetail(resources, call)))
                }
            }
        }
        PolicyRow(R.string.automations_policy_on_failure) { PolicyText(policy.failurePolicy) }
        if (policy.untrustedFlow) {
            Text(
                text = stringResource(R.string.automations_policy_untrusted),
                style = typo.bodySmall,
                color = palette.warning,
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.warningTint)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PolicyRow(@StringRes term: Int, content: @Composable ColumnScope.() -> Unit) {
    HorizontalDivider(color = TroupeTheme.palette.border, thickness = 0.5.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(term), style = TroupeTheme.typography.labelMedium, color = TroupeTheme.palette.mutedForeground)
        content()
    }
}

@Composable
private fun PolicyText(text: String) {
    Text(text, style = TroupeTheme.typography.bodyMedium, color = TroupeTheme.palette.foreground)
}

@Composable
private fun NoneText() {
    Text(stringResource(R.string.automations_policy_none), style = TroupeTheme.typography.bodyMedium, color = TroupeTheme.palette.mutedForeground)
}

/** One line per node: its label and what it was granted. */
@Composable
private fun PerNodeList(entries: List<Pair<NodeId, List<String>>>, labels: Map<NodeId, String>, resources: Resources) {
    if (entries.isEmpty()) {
        NoneText()
        return
    }
    val separator = resources.getString(R.string.automations_list_separator)
    entries.forEach { (nodeId, items) ->
        PolicyText(resources.getString(R.string.automations_policy_node_items, labelOrId(labels, nodeId), items.joinToString(separator)))
    }
}

private fun nodeDetail(resources: Resources, label: String, detail: String) =
    resources.getString(R.string.automations_policy_node_detail, label, detail)

private fun agentDetail(resources: Resources, agent: AgentPolicy): String {
    val model = agent.model
    val parts = buildList {
        add(if (model != null) resources.getString(R.string.automations_policy_agent_model, agent.agentId, model) else agent.agentId)
        add(resources.getString(R.string.automations_policy_agent_timeout, formatDuration(resources, agent.timeoutMs)))
        add(agentToolsLabel(resources, agent.tools))
        if (AgentTool.Other in agent.tools) {
            add(
                if (agent.mcpServers.isEmpty()) {
                    resources.getString(R.string.automations_policy_no_mcp_servers)
                } else {
                    resources.getString(R.string.automations_policy_mcp_servers, agent.mcpServers.joinToString(resources.getString(R.string.automations_list_separator)) { it.name })
                },
            )
        }
    }
    return parts.joinToString(resources.getString(R.string.automations_policy_separator))
}

private fun agentToolsLabel(resources: Resources, tools: List<AgentTool>): String =
    if (tools.isEmpty()) {
        resources.getString(R.string.automations_policy_no_tools)
    } else {
        val names = tools.joinToString(resources.getString(R.string.automations_list_separator)) { resources.getString(toolLabelRes(it)) }
        resources.getString(R.string.automations_policy_tools, names)
    }

@StringRes
private fun toolLabelRes(tool: AgentTool): Int = when (tool) {
    AgentTool.Read -> R.string.automations_tool_read
    AgentTool.Search -> R.string.automations_tool_search
    AgentTool.Edit -> R.string.automations_tool_edit
    AgentTool.Execute -> R.string.automations_tool_execute
    AgentTool.Fetch -> R.string.automations_tool_fetch
    AgentTool.Other -> R.string.automations_tool_other
}

private fun mcpCallDetail(resources: Resources, call: McpToolPolicy): String {
    val serverName = call.serverName
    return if (serverName == null) {
        resources.getString(R.string.automations_policy_mcp_call_unconfigured, call.tool, call.serverId)
    } else {
        resources.getString(R.string.automations_policy_mcp_call, call.tool, serverName, call.serverId)
    }
}
