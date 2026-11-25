package dev.troupe.android.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.troupe.android.R
import dev.troupe.android.chat.markdown.MarkdownText
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.theme.MonoFamily
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.SheetGroup
import dev.troupe.android.core.ui.SheetRow
import dev.troupe.android.core.ui.SheetRowDivider
import dev.troupe.android.core.ui.SheetSearchField
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIconButton
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMobileSheet
import dev.troupe.android.core.ui.TroupeSpinner
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.servers.PairedServer
import dev.troupe.android.workspace.DirectoryEntry
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class FileReadResponse(
    val content: String = "",
    val encoding: String = "utf-8",
    val size: Long = 0,
    val sha256: String? = null,
)

@Singleton
class FilesApi @Inject constructor(
    private val api: ServerApi,
) {
    suspend fun ls(address: ServerAddress, token: String?, path: String): ApiResult<List<DirectoryEntry>> {
        val q = "fs/ls?path=${URLEncoder.encode(path, "UTF-8")}"
        return api.getEnveloped(address, q, token, ListSerializer(DirectoryEntry.serializer()))
    }

    suspend fun read(address: ServerAddress, token: String?, path: String): ApiResult<FileReadResponse> {
        val q = "fs/read?path=${URLEncoder.encode(path, "UTF-8")}"
        return api.getEnveloped(address, q, token, FileReadResponse.serializer())
    }

    suspend fun write(address: ServerAddress, token: String?, path: String, content: String): ApiResult<Unit> {
        val body = buildJsonObject {
            put("path", path)
            put("content", content)
        }.toString()
        return api.postUnit(address, "fs/write", token, body)
    }

    suspend fun mkdir(address: ServerAddress, token: String?, path: String): ApiResult<Unit> {
        val body = buildJsonObject { put("path", path) }.toString()
        return api.postUnit(address, "fs/mkdir", token, body)
    }

    /** Deletes [path]; the server takes a folder only with [recursive]. */
    suspend fun delete(address: ServerAddress, token: String?, path: String, recursive: Boolean = false): ApiResult<Unit> {
        val body = buildJsonObject {
            put("path", path)
            if (recursive) put("recursive", true)
        }.toString()
        return api.postUnit(address, "fs/delete", token, body)
    }

    /** Duplicates a file (`fs/copy`). */
    suspend fun copy(address: ServerAddress, token: String?, from: String, to: String): ApiResult<Unit> {
        val body = buildJsonObject {
            put("from", from)
            put("to", to)
        }.toString()
        return api.postUnit(address, "fs/copy", token, body)
    }

    suspend fun rename(address: ServerAddress, token: String?, oldPath: String, newPath: String): ApiResult<Unit> {
        val body = buildJsonObject {
            put("from", oldPath)
            put("to", newPath)
        }.toString()
        return api.postUnit(address, "fs/rename", token, body)
    }
}

/**
 * Touch-first Mobile File Explorer bottom sheet matching `MobileFileExplorer.tsx`:
 * Breadcrumb bar, single-folder drill-down, search filter, new file/folder creation,
 * and tap-to-open directly into `CodeEditorPane`.
 */
@Composable
fun MobileFileExplorerSheet(
    server: PairedServer,
    rootPath: String,
    viewOnly: Boolean,
    filesApi: FilesApi,
    tokenProvider: suspend () -> String?,
    onDismiss: () -> Unit,
    onOpenFile: (filePath: String) -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val scope = rememberCoroutineScope()

    var currentPath by remember(rootPath) { mutableStateOf(rootPath) }
    var entries by remember { mutableStateOf<List<DirectoryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var creatingKind by remember { mutableStateOf<String?>(null) } // "file" or "dir"
    var newName by remember { mutableStateOf("") }
    var createError by remember(currentPath) { mutableStateOf<String?>(null) }
    // Row actions (Troupe's "Actions for X" sheet): the entry acted on, and what is being asked.
    var actionEntry by remember(currentPath) { mutableStateOf<DirectoryEntry?>(null) }
    var actionMode by remember(currentPath) { mutableStateOf(FileAction.Menu) }
    var renameText by remember(currentPath) { mutableStateOf("") }
    var actionError by remember(currentPath) { mutableStateOf<String?>(null) }
    var actionBusy by remember(currentPath) { mutableStateOf(false) }

    fun load(path: String) {
        loading = true
        error = null
        scope.launch {
            val token = tokenProvider()
            val res = filesApi.ls(server.address, token, path)
            // The user has moved on: a late listing must not replace the folder now shown.
            if (path != currentPath) return@launch
            when (res) {
                is ApiResult.Ok -> {
                    // Troupe's order: folders, then what git does not ignore, then A to Z.
                    entries = res.value.sortedWith(
                        compareBy<DirectoryEntry> { !it.isDirectory }.thenBy { it.ignored == true }.thenBy { it.name.lowercase() },
                    )
                    loading = false
                }
                is ApiResult.ServerError -> {
                    error = res.message
                    loading = false
                }
                else -> {
                    error = "Could not load folder."
                    loading = false
                }
            }
        }
    }

    LaunchedEffect(currentPath) { load(currentPath) }

    fun runAction(failure: String, call: suspend (token: String?) -> ApiResult<Unit>) {
        if (actionBusy) return
        actionBusy = true
        scope.launch {
            val res = call(tokenProvider())
            actionBusy = false
            when (res) {
                is ApiResult.Ok -> {
                    actionEntry = null
                    actionError = null
                    load(currentPath)
                }
                is ApiResult.ServerError -> actionError = "$failure: ${res.message}"
                ApiResult.Unauthorized -> actionError = "Authentication required. Re-enter your server token."
                is ApiResult.Unreachable -> actionError = "$failure: the server did not answer."
            }
        }
    }

    val canGoUp = currentPath.trimEnd('/') != rootPath.trimEnd('/') && currentPath.startsWith(rootPath.trimEnd('/'))
    val relative = currentPath.removePrefix(rootPath).trimStart('/').ifEmpty { stringResource(R.string.files_project_files) }

    TroupeMobileSheet(
        title = stringResource(R.string.shell_files),
        subtitle = relative,
        canPop = canGoUp,
        onPop = {
            if (canGoUp) {
                currentPath = currentPath.trimEnd('/').substringBeforeLast('/').ifEmpty { rootPath }
            }
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.files_filter_placeholder),
                    modifier = Modifier.weight(1f),
                )
                if (!viewOnly) {
                    TroupeIconButton(
                        icon = TroupeIcons.Plus,
                        contentDescription = stringResource(R.string.files_new_file),
                        onClick = {
                            creatingKind = if (creatingKind == null) "file" else null
                            createError = null
                        },
                    )
                }
            }

            if (creatingKind != null && !viewOnly) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.elevated)
                        .border(0.5.dp, palette.border, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroupeButton(
                            text = stringResource(R.string.files_new_file),
                            variant = if (creatingKind == "file") TroupeButtonVariant.Secondary else TroupeButtonVariant.Outline,
                            onClick = {
                                creatingKind = "file"
                                createError = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TroupeButton(
                            text = stringResource(R.string.files_new_folder),
                            variant = if (creatingKind == "dir") TroupeButtonVariant.Secondary else TroupeButtonVariant.Outline,
                            onClick = {
                                creatingKind = "dir"
                                createError = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = newName,
                        onValueChange = {
                            newName = it
                            createError = null
                        },
                        placeholder = { Text(if (creatingKind == "file") "index.ts" else "components") },
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
                    if (createError != null) {
                        Text(createError!!, style = typo.bodySmall, color = palette.destructive)
                    }
                    TroupeButton(
                        text = stringResource(R.string.files_create),
                        enabled = newName.isNotBlank(),
                        onClick = {
                            val cleanName = newName.trim()
                            if (entries.any { it.name == cleanName }) {
                                createError = "An entry named '$cleanName' already exists in this folder."
                                return@TroupeButton
                            }
                            createError = null
                            val target = "${currentPath.trimEnd('/')}/$cleanName"
                            val kind = creatingKind
                            scope.launch {
                                val token = tokenProvider()
                                val res = if (kind == "dir") {
                                    filesApi.mkdir(server.address, token, target)
                                } else {
                                    filesApi.write(server.address, token, target, "")
                                }
                                if (res is ApiResult.Ok) {
                                    creatingKind = null
                                    newName = ""
                                    createError = null
                                    if (kind == "file") {
                                        onDismiss()
                                        onOpenFile(target)
                                    } else {
                                        load(currentPath)
                                    }
                                } else if (res is ApiResult.ServerError) {
                                    createError = res.message
                                } else if (res is ApiResult.Unauthorized) {
                                    createError = "Authentication required. Re-enter your server token."
                                } else {
                                    createError = "Could not create $cleanName."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            actionEntry?.takeIf { !viewOnly }?.let { entry ->
                FileActionCard(
                    entry = entry,
                    mode = actionMode,
                    renameText = renameText,
                    error = actionError,
                    busy = actionBusy,
                    onRenameText = { renameText = it },
                    onMode = { actionMode = it },
                    onCancel = { actionEntry = null },
                    onRename = {
                        val newName = renameText.trim()
                        if (entries.any { it.name == newName }) {
                            actionError = "An entry named '$newName' already exists in this folder."
                        } else {
                            val target = "${currentPath.trimEnd('/')}/$newName"
                            runAction("Failed to rename") { token -> filesApi.rename(server.address, token, entry.path, target) }
                        }
                    },
                    onDuplicate = {
                        // The dot of a dotfile (".env") opens its name, it does not start an extension.
                        val dot = entry.name.lastIndexOf('.')
                        val stem = if (dot > 0) entry.name.substring(0, dot) else entry.name
                        val ext = if (dot > 0) entry.name.substring(dot) else ""
                        // "name copy.ext", then "name copy 2.ext" and so on: never onto an existing entry.
                        val taken = entries.map { it.name }.toSet()
                        val copyName = generateSequence(1) { it + 1 }
                            .map { n -> if (n == 1) "$stem copy$ext" else "$stem copy $n$ext" }
                            .first { it !in taken }
                        val target = "${currentPath.trimEnd('/')}/$copyName"
                        runAction("Failed to copy") { token -> filesApi.copy(server.address, token, entry.path, target) }
                    },
                    onDelete = {
                        runAction("Failed to delete") { token ->
                            filesApi.delete(server.address, token, entry.path, recursive = entry.isDirectory)
                        }
                    },
                )
            }

            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    TroupeSpinner(size = 20.dp)
                }
                error != null -> Text(error!!, style = typo.bodySmall, color = palette.destructive)
                else -> {
                    val visible = entries.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                    if (visible.isEmpty()) {
                        Text(
                            text = stringResource(R.string.files_empty_folder),
                            style = typo.bodyMedium,
                            color = palette.mutedForeground,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        SheetGroup {
                            visible.forEachIndexed { idx, entry ->
                                if (idx > 0) SheetRowDivider()
                                SheetRow(
                                    title = entry.name,
                                    leading = {
                                        Icon(
                                            imageVector = if (entry.isDirectory) TroupeIcons.Folder else TroupeIcons.FileCode,
                                            contentDescription = null,
                                            tint = if (entry.isDirectory) palette.brandText else palette.mutedForeground,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                    trailing = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (!viewOnly) {
                                                TroupeIconButton(
                                                    icon = TroupeIcons.MoreHorizontal,
                                                    contentDescription = stringResource(R.string.files_actions_for, entry.name),
                                                    onClick = {
                                                        actionEntry = entry
                                                        actionMode = FileAction.Menu
                                                        renameText = entry.name
                                                        actionError = null
                                                    },
                                                    iconSize = 16.dp,
                                                    contentColor = palette.mutedForeground,
                                                )
                                            }
                                            if (entry.isDirectory) {
                                                Icon(
                                                    imageVector = TroupeIcons.ChevronRight,
                                                    contentDescription = null,
                                                    tint = palette.mutedForeground,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        if (entry.isDirectory) {
                                            query = ""
                                            currentPath = entry.path
                                        } else {
                                            onDismiss()
                                            onOpenFile(entry.path)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class FileAction { Menu, Rename, Delete }

/** Rename, Duplicate and Delete for one entry, with Troupe's confirmation before a delete. */
@Composable
private fun FileActionCard(
    entry: DirectoryEntry,
    mode: FileAction,
    renameText: String,
    error: String?,
    busy: Boolean,
    onRenameText: (String) -> Unit,
    onMode: (FileAction) -> Unit,
    onCancel: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.elevated)
            .border(0.5.dp, palette.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (mode == FileAction.Delete) stringResource(R.string.files_delete_title, entry.name) else entry.name,
            style = typo.titleSmall,
            color = palette.foreground,
        )
        when (mode) {
            FileAction.Menu -> {
                TroupeButton(
                    text = stringResource(R.string.files_rename),
                    variant = TroupeButtonVariant.Secondary,
                    enabled = !busy,
                    onClick = { onMode(FileAction.Rename) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!entry.isDirectory) {
                    TroupeButton(
                        text = stringResource(R.string.files_duplicate),
                        variant = TroupeButtonVariant.Secondary,
                        enabled = !busy,
                        loading = busy,
                        onClick = onDuplicate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TroupeButton(
                    text = stringResource(R.string.files_delete),
                    variant = TroupeButtonVariant.Destructive,
                    enabled = !busy,
                    onClick = { onMode(FileAction.Delete) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FileAction.Rename -> {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = onRenameText,
                    label = { Text(stringResource(R.string.files_rename)) },
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
                TroupeButton(
                    text = stringResource(R.string.files_rename),
                    enabled = !busy && renameText.isNotBlank() && renameText.trim() != entry.name && '/' !in renameText,
                    loading = busy,
                    onClick = onRename,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FileAction.Delete -> {
                Text(
                    text = stringResource(if (entry.isDirectory) R.string.files_delete_folder_body else R.string.files_delete_file_body),
                    style = typo.bodySmall,
                    color = palette.mutedForeground,
                )
                TroupeButton(
                    text = stringResource(R.string.files_delete),
                    variant = TroupeButtonVariant.Destructive,
                    enabled = !busy,
                    loading = busy,
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (error != null) Text(error, style = typo.bodySmall, color = palette.destructive)
        TroupeButton(
            text = stringResource(R.string.files_cancel),
            variant = TroupeButtonVariant.Ghost,
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Native Jetpack Compose code & Markdown editor pane for `WorkspaceTab.Editor`.
 * Supports read/write via `/fs/read` + `/fs/write`, dirty-indicator save button,
 * and live Markdown preview toggle for `.md` files.
 */
@Composable
fun CodeEditorPane(
    server: PairedServer,
    filePath: String,
    viewOnly: Boolean,
    filesApi: FilesApi,
    tokenProvider: suspend () -> String?,
    modifier: Modifier = Modifier,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val scope = rememberCoroutineScope()

    var original by remember(filePath) { mutableStateOf("") }
    var content by remember(filePath) { mutableStateOf("") }
    var loading by remember(filePath) { mutableStateOf(true) }
    var readSucceeded by remember(filePath) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember(filePath) { mutableStateOf<String?>(null) }
    var previewMarkdown by remember(filePath) { mutableStateOf(false) }

    val isMarkdown = filePath.endsWith(".md", ignoreCase = true) || filePath.endsWith(".mdx", ignoreCase = true)
    val dirty = readSucceeded && content != original

    LaunchedEffect(server.id, filePath) {
        loading = true
        readSucceeded = false
        error = null
        val token = tokenProvider()
        when (val res = filesApi.read(server.address, token, filePath)) {
            is ApiResult.Ok -> {
                original = res.value.content
                content = res.value.content
                readSucceeded = true
                loading = false
            }
            is ApiResult.ServerError -> {
                error = res.message
                loading = false
            }
            else -> {
                error = "Could not open file."
                loading = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .imePadding(),
    ) {
        // Compact editor toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.card)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = filePath.substringAfterLast('/'),
                style = typo.labelLarge.copy(fontFamily = MonoFamily),
                color = palette.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isMarkdown && readSucceeded) {
                Text(
                    text = stringResource(if (previewMarkdown) R.string.editor_source else R.string.editor_preview),
                    style = typo.labelMedium,
                    color = palette.brandText,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(palette.secondarySurface)
                        .clickable { previewMarkdown = !previewMarkdown }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (dirty && !viewOnly && readSucceeded) {
                TroupeButton(
                    text = stringResource(R.string.servers_save),
                    loading = saving,
                    minHeight = 34.dp,
                    onClick = {
                        val savedContent = content
                        saving = true
                        error = null
                        scope.launch {
                            val token = tokenProvider()
                            when (val res = filesApi.write(server.address, token, filePath, savedContent)) {
                                is ApiResult.Ok -> {
                                    original = savedContent
                                    saving = false
                                }
                                is ApiResult.ServerError -> {
                                    error = res.message
                                    saving = false
                                }
                                else -> {
                                    error = "Save failed."
                                    saving = false
                                }
                            }
                        }
                    },
                )
            }
        }
        HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)

        if (error != null) {
            Text(
                text = error!!,
                style = typo.bodySmall,
                color = palette.destructive,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TroupeSpinner(size = 22.dp)
            }
            !readSucceeded -> Box(Modifier.fillMaxSize())
            isMarkdown && previewMarkdown -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                MarkdownText(markdown = content)
            }
            else -> androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                // The field is at least as large as the pane, so a tap anywhere below or beside a
                // short file still focuses it.
                val fieldMinWidth = maxWidth - 24.dp
                val fieldMinHeight = maxHeight - 24.dp
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                BasicTextField(
                    value = content,
                    onValueChange = { if (!viewOnly) content = it },
                    readOnly = viewOnly,
                    textStyle = typo.bodySmall.copy(
                        fontFamily = MonoFamily,
                        color = palette.foreground,
                        lineHeight = 19.sp,
                    ),
                    cursorBrush = SolidColor(palette.brand),
                    modifier = Modifier.widthIn(min = fieldMinWidth).heightIn(min = fieldMinHeight),
                )
                }
            }
        }
    }
}
