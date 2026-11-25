package dev.troupe.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.theme.TroupeColors
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.SheetGroup
import dev.troupe.android.core.ui.SheetRow
import dev.troupe.android.core.ui.SheetRowDivider
import dev.troupe.android.core.ui.SheetStackContent
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMobileSheet
import dev.troupe.android.core.ui.TroupeSpinner
import dev.troupe.android.core.ui.rememberSheetStack
import dev.troupe.android.servers.PairedServer
import dev.troupe.android.workspace.BrowseDirectoryResponse
import dev.troupe.android.workspace.ProjectApi
import dev.troupe.android.workspace.WorkspaceShellState
import kotlinx.coroutines.launch

private sealed interface ProjectSheetRoute {
    data object Servers : ProjectSheetRoute
    data object AddProject : ProjectSheetRoute
}

/**
 * Stacked bottom sheet for switching Projects, switching Paired Servers, and creating a new
 * Project directly from Android by browsing the server's filesystem (`GET /fs/browse` + `POST /projects`).
 */
@Composable
fun ProjectAndServerSheet(
    state: WorkspaceShellState,
    projectApi: ProjectApi,
    tokenProvider: suspend () -> String?,
    onDismiss: () -> Unit,
    onSelectProject: (String) -> Unit,
    onSetDefaultProject: (String) -> Unit,
    onCreateProject: (name: String, path: String, color: String, onDone: (String?) -> Unit) -> Unit,
    onSelectServer: (String) -> Unit,
    onAddServer: () -> Unit,
    onOpenChat: (sessionId: String, title: String?, projectId: String?) -> Unit = { _, _, _ -> },
    onShowingProjectsRoot: (Boolean) -> Unit = {},
) {
    val stack = rememberSheetStack<ProjectSheetRoute>()
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography

    LaunchedEffect(stack.top) {
        onShowingProjectsRoot(stack.top == null)
    }

    val title = when (stack.top) {
        null -> stringResource(R.string.projects_title)
        ProjectSheetRoute.Servers -> stringResource(R.string.servers_title)
        ProjectSheetRoute.AddProject -> stringResource(R.string.shell_add_project)
    }

    TroupeMobileSheet(
        title = title,
        onDismiss = onDismiss,
        canPop = stack.canPop,
        onPop = { stack.pop() },
    ) {
        SheetStackContent(
            current = stack.top,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) { route ->
            when (route) {
                null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // Active Paired Server row -> pushes Servers switcher
                        state.activeServer?.let { server ->
                            val otherServersAttention = state.attentionItemsByServer
                                .filterKeys { it != server.id }
                                .values
                                .sumOf { it.size }
                            SheetGroup {
                                SheetRow(
                                    title = server.name,
                                    subtitle = if (server.stayConnected) {
                                        "${server.address.host} · ${stringResource(R.string.servers_stays_connected)}"
                                    } else {
                                        server.address.host
                                    },
                                    leading = {
                                        Icon(
                                            imageVector = TroupeIcons.Server,
                                            contentDescription = null,
                                            tint = palette.brandText,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                    trailing = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            if (otherServersAttention > 0) {
                                                dev.troupe.android.core.ui.CountBadge(
                                                    count = otherServersAttention,
                                                    tone = palette.warning,
                                                )
                                            }
                                            Text(
                                                text = stringResource(R.string.shell_switch_server),
                                                style = typo.labelMedium,
                                                color = palette.mutedForeground,
                                            )
                                            Icon(
                                                imageVector = TroupeIcons.ChevronRight,
                                                contentDescription = null,
                                                tint = palette.mutedForeground,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    },
                                    onClick = { stack.push(ProjectSheetRoute.Servers) },
                                )
                            }
                        }

                        // Attention rows directly above the Projects list (#12)
                        if (state.attentionItems.isNotEmpty()) {
                            dev.troupe.android.attention.AttentionSection(
                                items = state.attentionItems,
                                onOpen = { item ->
                                    onDismiss()
                                    onOpenChat(item.sessionId, item.sessionTitle, item.projectId)
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(0.5.dp, palette.warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                            )
                        }

                        // Active server's projects
                        val visibleProjects = state.projects.filterNot { it.isArchived }
                        if (visibleProjects.isEmpty()) {
                            Text(
                                text = stringResource(R.string.projects_empty),
                                style = typo.bodyMedium,
                                color = palette.mutedForeground,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                            )
                        } else {
                            SheetGroup {
                                visibleProjects.forEachIndexed { idx, project ->
                                    if (idx > 0) SheetRowDivider()
                                    val isSelected = project.id == state.activeProject?.id
                                    val isDefault = project.id == state.defaultProjectId || project.isDefault
                                    SheetRow(
                                        title = project.name,
                                        subtitle = project.path,
                                        picked = isSelected,
                                        leading = {
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .clip(CircleShape)
                                                    .background(TroupeColors.project(project.color)),
                                            )
                                        },
                                        trailing = {
                                            if (isSelected && !isDefault) {
                                                Text(
                                                    text = stringResource(R.string.projects_current_badge),
                                                    style = typo.labelSmall,
                                                    color = palette.mutedForeground,
                                                    modifier = Modifier.padding(end = 6.dp),
                                                )
                                            }
                                            if (isDefault) {
                                                Text(
                                                    text = stringResource(R.string.projects_default_badge),
                                                    style = typo.labelSmall,
                                                    color = palette.mutedForeground,
                                                    modifier = Modifier
                                                        .clip(CircleShape)
                                                        .background(palette.secondarySurface)
                                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                                )
                                            } else if (isSelected && !state.viewOnly) {
                                                Text(
                                                    text = stringResource(R.string.shell_set_default),
                                                    style = typo.labelSmall,
                                                    color = palette.brandText,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .clickable { onSetDefaultProject(project.id) }
                                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                                )
                                            }
                                        },
                                        onClick = {
                                            onSelectProject(project.id)
                                            onDismiss()
                                        },
                                    )
                                }
                            }
                        }

                        if (!state.viewOnly && state.activeServer != null) {
                            TroupeButton(
                                text = stringResource(R.string.shell_add_project),
                                icon = TroupeIcons.Plus,
                                variant = TroupeButtonVariant.Secondary,
                                onClick = { stack.push(ProjectSheetRoute.AddProject) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                ProjectSheetRoute.Servers -> {
                    ServersSwitcherContent(
                        servers = state.servers,
                        activeServerId = state.activeServer?.id,
                        attentionByServer = state.attentionItemsByServer,
                        onSelect = { id ->
                            onSelectServer(id)
                            onDismiss()
                        },
                        onAddServer = {
                            onDismiss()
                            onAddServer()
                        },
                    )
                }

                ProjectSheetRoute.AddProject -> {
                    val server = state.activeServer
                    if (server != null) {
                        AddProjectContent(
                            server = server,
                            projectApi = projectApi,
                            tokenProvider = tokenProvider,
                            onCreate = { name, path, color, onDone ->
                                onCreateProject(name, path, color) { err ->
                                    onDone(err)
                                    if (err == null) onDismiss()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServersSwitcherContent(
    servers: List<PairedServer>,
    activeServerId: String?,
    attentionByServer: Map<String, List<dev.troupe.android.attention.AttentionItem>>,
    onSelect: (String) -> Unit,
    onAddServer: () -> Unit,
) {
    val palette = TroupeTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SheetGroup {
            servers.forEachIndexed { idx, server ->
                if (idx > 0) SheetRowDivider()
                val attentionCount = attentionByServer[server.id]?.size ?: 0
                val subtitle = if (server.stayConnected) {
                    "${server.address.host} · ${stringResource(R.string.servers_stays_connected)}"
                } else {
                    server.address.host
                }
                SheetRow(
                    title = server.name,
                    subtitle = subtitle,
                    picked = server.id == activeServerId,
                    leading = {
                        Icon(
                            imageVector = TroupeIcons.Server,
                            contentDescription = null,
                            tint = if (server.id == activeServerId) palette.brandText else palette.mutedForeground,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    trailing = if (attentionCount > 0) {
                        {
                            dev.troupe.android.core.ui.CountBadge(
                                count = attentionCount,
                                tone = palette.warning,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = { onSelect(server.id) },
                )
            }
        }
        TroupeButton(
            text = stringResource(R.string.servers_pair_action),
            icon = TroupeIcons.Plus,
            variant = TroupeButtonVariant.Default,
            onClick = onAddServer,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Names a Project and picks its folder by browsing the server (`GET /fs/browse`). */
@Composable
internal fun AddProjectContent(
    server: PairedServer,
    projectApi: ProjectApi,
    tokenProvider: suspend () -> String?,
    onCreate: (name: String, path: String, color: String, onDone: (String?) -> Unit) -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val scope = rememberCoroutineScope()

    var currentBrowse by remember { mutableStateOf<BrowseDirectoryResponse?>(null) }
    var loadingDir by remember { mutableStateOf(true) }
    /** The folder last asked for, which is the one to ask again when it failed. */
    var requestedPath by remember { mutableStateOf<String?>(null) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var selectedPath by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf("blue") }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    fun loadDir(path: String?) {
        requestedPath = path
        loadingDir = true
        browseError = null
        scope.launch {
            val token = tokenProvider()
            when (val res = projectApi.browseDirectory(server.address, token, path)) {
                is ApiResult.Ok -> {
                    currentBrowse = res.value
                    if (selectedPath.isBlank() || path != null) {
                        selectedPath = res.value.currentPath
                        if (projectName.isBlank()) {
                            projectName = res.value.currentPath.trimEnd('/').substringAfterLast('/')
                        }
                    }
                    loadingDir = false
                }
                is ApiResult.ServerError -> {
                    browseError = res.message
                    loadingDir = false
                }
                else -> {
                    browseError = "Could not browse server directories."
                    loadingDir = false
                }
            }
        }
    }

    LaunchedEffect(Unit) { loadDir(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = projectName,
            onValueChange = { projectName = it },
            label = { Text(stringResource(R.string.servers_name_label)) },
            singleLine = true,
            textStyle = typo.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.ring,
                unfocusedBorderColor = palette.border,
                focusedTextColor = palette.foreground,
                unfocusedTextColor = palette.foreground,
            ),
        )

        OutlinedTextField(
            value = selectedPath,
            onValueChange = { selectedPath = it },
            label = { Text(stringResource(R.string.shell_project_path_label)) },
            singleLine = true,
            textStyle = typo.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.ring,
                unfocusedBorderColor = palette.border,
                focusedTextColor = palette.foreground,
                unfocusedTextColor = palette.foreground,
            ),
        )

        // Color swatches
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TroupeColors.projectColorNames.take(8).forEach { colorName ->
                val isPicked = colorName == selectedColor
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(TroupeColors.project(colorName))
                        .then(
                            if (isPicked) Modifier.border(2.dp, palette.foreground, CircleShape) else Modifier,
                        )
                        .clickable { selectedColor = colorName },
                )
            }
        }

        // Server folder browser
        Text(
            text = currentBrowse?.currentPath ?: stringResource(R.string.shell_browse_folders),
            style = typo.labelMedium,
            color = palette.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (loadingDir) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                TroupeSpinner(size = 20.dp)
            }
        } else if (browseError != null) {
            Text(text = browseError!!, style = typo.bodySmall, color = palette.destructive)
            TroupeButton(
                text = stringResource(R.string.agents_check_again),
                variant = TroupeButtonVariant.Secondary,
                onClick = { loadDir(requestedPath) },
            )
        } else {
            val browse = currentBrowse
            // Rendered eagerly inside the sheet's scroll: bounded, with the path field for the rest.
            val allDirs = browse?.entries.orEmpty().filter { it.isDirectory }
            val dirs = allDirs.take(MAX_BROWSE_FOLDERS)
            SheetGroup {
                if (browse?.parentPath != null) {
                    SheetRow(
                        title = "..",
                        leading = {
                            Icon(TroupeIcons.ArrowLeft, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(16.dp))
                        },
                        onClick = { loadDir(browse.parentPath) },
                    )
                    if (dirs.isNotEmpty()) SheetRowDivider()
                }
                dirs.forEachIndexed { idx, entry ->
                    if (idx > 0) SheetRowDivider()
                    SheetRow(
                        title = entry.name,
                        leading = {
                            Icon(TroupeIcons.Folder, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(16.dp))
                        },
                        trailing = {
                            Icon(TroupeIcons.ChevronRight, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(16.dp))
                        },
                        onClick = {
                            projectName = entry.name
                            loadDir(entry.path)
                        },
                    )
                }
            }
        }

        val hiddenFolders = currentBrowse?.entries.orEmpty().count { it.isDirectory } - MAX_BROWSE_FOLDERS
        if (!loadingDir && browseError == null && hiddenFolders > 0) {
            Text(
                text = stringResource(R.string.shell_browse_more_folders, MAX_BROWSE_FOLDERS, hiddenFolders),
                style = typo.bodySmall,
                color = palette.mutedForeground,
            )
        }

        if (submitError != null) {
            Text(text = submitError!!, style = typo.bodySmall, color = palette.destructive)
        }

        TroupeButton(
            text = stringResource(R.string.shell_create_project),
            onClick = {
                submitting = true
                submitError = null
                onCreate(
                    projectName.trim().ifEmpty { selectedPath.trimEnd('/').substringAfterLast('/') },
                    selectedPath.trim(),
                    selectedColor,
                ) { err ->
                    submitting = false
                    submitError = err
                }
            },
            enabled = selectedPath.isNotBlank(),
            loading = submitting,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/** Folders listed per level when choosing a Project folder. */
private const val MAX_BROWSE_FOLDERS = 200
