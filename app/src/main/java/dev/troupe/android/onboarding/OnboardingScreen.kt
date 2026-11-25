package dev.troupe.android.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.agents.AgentConfig
import dev.troupe.android.agents.AgentSetupList
import dev.troupe.android.agents.CatalogAgent
import dev.troupe.android.agents.registryConfigId
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMark
import dev.troupe.android.shell.AddProjectContent
import dev.troupe.android.workspace.ProjectApi
import dev.troupe.android.workspace.WorkspaceShellState

/**
 * Troupe's first run (`components/onboarding/`): Welcome, "Check your agents", "Where should
 * agents work?". Every step can be passed without doing it; [onFinish] gets the agent the user
 * picked, if any, and whether a Project was added, and runs once.
 */
@Composable
fun OnboardingScreen(
    state: WorkspaceShellState,
    darkTheme: Boolean,
    projectApi: ProjectApi,
    tokenProvider: suspend () -> String?,
    onSetDarkTheme: (Boolean) -> Unit,
    onInstallAgent: (CatalogAgent) -> Unit,
    onCheckAgents: () -> Unit,
    onCreateProject: (name: String, path: String, color: String, onDone: (String?) -> Unit) -> Unit,
    onFinish: (picked: AgentConfig?, projectCreated: Boolean) -> Unit,
) {
    val palette = TroupeTheme.palette
    var step by rememberSaveable { mutableIntStateOf(0) }
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    val setup = state.agentSetup
    // The pick follows an install: the agent just installed becomes the selected one.
    val picked = setup.launchable.firstOrNull { it.id == pickedId } ?: setup.selected

    BackHandler(enabled = step > 0) { if (browsing) browsing = false else step -= 1 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                0 -> WelcomeStep(darkTheme = darkTheme, onSetDarkTheme = onSetDarkTheme)
                1 -> {
                    StepHeading(2, R.string.onboarding_agents_title, R.string.onboarding_agents_body)
                    AgentSetupList(
                        state = setup,
                        selectedConfigId = picked?.id,
                        viewOnly = state.viewOnly,
                        onSelect = { pickedId = it.id },
                        onInstall = { agent ->
                            // The agent being installed becomes the pick once it is ready.
                            pickedId = registryConfigId(agent.id)
                            onInstallAgent(agent)
                        },
                        onCheckAgain = onCheckAgents,
                    )
                }
                else -> {
                    StepHeading(3, R.string.onboarding_project_title, R.string.onboarding_project_body)
                    val server = state.activeServer
                    if (browsing && server != null) {
                        AddProjectContent(
                            server = server,
                            projectApi = projectApi,
                            tokenProvider = tokenProvider,
                            onCreate = { name, path, color, onDone ->
                                onCreateProject(name, path, color) { error ->
                                    onDone(error)
                                    if (error == null) onFinish(picked, true)
                                }
                            },
                        )
                    } else {
                        ChoiceCard(
                            title = stringResource(R.string.onboarding_open_folder),
                            subtitle = stringResource(R.string.onboarding_open_folder_sub),
                            onClick = { browsing = true },
                        )
                    }
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (step) {
                0 -> TroupeButton(
                    text = stringResource(R.string.onboarding_get_started),
                    onClick = { step = 1 },
                    modifier = Modifier.fillMaxWidth(),
                )
                1 -> {
                    if (picked == null) {
                        Text(
                            text = stringResource(R.string.onboarding_agents_none),
                            style = TroupeTheme.typography.bodySmall,
                            color = palette.mutedForeground,
                        )
                    }
                    TroupeButton(
                        text = picked?.let { stringResource(R.string.onboarding_continue_with, it.name) }
                            ?: stringResource(R.string.onboarding_continue),
                        enabled = picked != null,
                        onClick = { step = 2 },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FooterRow(
                        onBack = { step = 0 },
                        secondary = stringResource(R.string.onboarding_later),
                        onSecondary = {
                            pickedId = null
                            step = 2
                        },
                    )
                }
                else -> FooterRow(
                    onBack = { if (browsing) browsing = false else step = 1 },
                    secondary = stringResource(if (picked != null) R.string.onboarding_skip_project else R.string.onboarding_skip),
                    onSecondary = { onFinish(picked, false) },
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(darkTheme: Boolean, onSetDarkTheme: (Boolean) -> Unit) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TroupeMark(size = 56.dp, tint = palette.secondary)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = typo.headlineSmall,
            color = palette.foreground,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = typo.bodyMedium,
            color = palette.mutedForeground,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
    Spacer(Modifier.height(8.dp))
    val shape = RoundedCornerShape(12.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(shape).background(palette.card).border(0.5.dp, palette.border, shape)) {
        listOf(
            R.string.onboarding_outline_agents,
            R.string.onboarding_outline_project,
            R.string.onboarding_outline_message,
        ).forEachIndexed { index, label ->
            if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = palette.border)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(palette.secondarySurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "${index + 1}", style = typo.labelMedium, color = palette.mutedForeground)
                }
                Text(text = stringResource(label), style = typo.bodyMedium, color = palette.foreground)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(R.string.onboarding_appearance), style = typo.bodyMedium, color = palette.mutedForeground)
        val segment = RoundedCornerShape(8.dp)
        Row(modifier = Modifier.clip(segment).border(0.5.dp, palette.border, segment).padding(2.dp)) {
            listOf(true to R.string.onboarding_appearance_dark, false to R.string.onboarding_appearance_light).forEach { (dark, label) ->
                val on = dark == darkTheme
                Box(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (on) palette.secondarySurface else androidx.compose.ui.graphics.Color.Transparent)
                        .selectable(selected = on, role = Role.RadioButton) { onSetDarkTheme(dark) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(label),
                        style = typo.labelLarge,
                        color = if (on) palette.foreground else palette.mutedForeground,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepHeading(step: Int, title: Int, body: Int) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Text(text = stringResource(R.string.onboarding_step, step), style = typo.labelLarge, color = palette.mutedForeground)
    Text(
        text = stringResource(title),
        style = typo.titleLarge,
        color = palette.foreground,
        modifier = Modifier.semantics { heading() },
    )
    Text(text = stringResource(body), style = typo.bodyMedium, color = palette.mutedForeground)
}

@Composable
private fun ChoiceCard(title: String, subtitle: String, onClick: () -> Unit) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.card)
            .border(0.5.dp, palette.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(TroupeIcons.Folder, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = typo.titleSmall, color = palette.foreground)
            Text(text = subtitle, style = typo.bodySmall, color = palette.mutedForeground)
        }
        Icon(TroupeIcons.ChevronRight, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun FooterRow(onBack: () -> Unit, secondary: String, onSecondary: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TroupeButton(text = stringResource(R.string.onboarding_back), variant = TroupeButtonVariant.Ghost, onClick = onBack)
        Spacer(Modifier.weight(1f).width(8.dp))
        TroupeButton(text = secondary, variant = TroupeButtonVariant.Ghost, onClick = onSecondary)
    }
}
