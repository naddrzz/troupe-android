package dev.troupe.android.projects

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.attention.AttentionSection
import dev.troupe.android.core.protocol.Project
import dev.troupe.android.core.theme.LocalTroupePalette
import dev.troupe.android.core.theme.TroupeColors
import dev.troupe.android.core.ui.TroupeTopBar

@Composable
fun ProjectsScreen(
    onBack: () -> Unit,
    onOpenProject: (Project) -> Unit,
    onOpenChat: (sessionId: String, title: String?) -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val attention by viewModel.attention.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val viewOnly by viewModel.viewOnly.collectAsStateWithLifecycle()
    val title = (state as? ProjectsUi.Loaded)?.serverName ?: stringResource(R.string.projects_title)
    Scaffold(topBar = {
        TroupeTopBar(title, onBack = onBack, connection = connection, onRetry = viewModel::retryConnection, viewOnly = viewOnly)
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            AttentionSection(attention, onOpen = { onOpenChat(it.sessionId, it.sessionTitle) })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val s = state) {
                    ProjectsUi.Loading -> Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.projects_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                    is ProjectsUi.Error -> Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(s.message, s.arg.orEmpty()),
                            color = LocalTroupePalette.current.error,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.projects_retry))
                        }
                    }
                    is ProjectsUi.Loaded ->
                        if (s.projects.isEmpty()) {
                            Text(
                                stringResource(R.string.projects_empty),
                                color = LocalTroupePalette.current.muted,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            )
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(s.projects, key = { it.id }) { project ->
                                    ProjectRow(project, onClick = { onOpenProject(project) })
                                    HorizontalDivider(color = LocalTroupePalette.current.border)
                                }
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(project: Project, onClick: () -> Unit) {
    val palette = LocalTroupePalette.current
    val colorName = project.color ?: "gray"
    val colorDescription = stringResource(R.string.projects_color_description, colorName)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(TroupeColors.project(project.color))
                .semantics { contentDescription = colorDescription },
        )
        Column(Modifier.weight(1f)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            project.path?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (project.isDefault) {
            Text(
                stringResource(R.string.projects_default_badge),
                style = MaterialTheme.typography.labelMedium,
                color = palette.brandText,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.elevated)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
