package dev.troupe.android.automations.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.automations.state.QueryError
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeSpinner

/** One line of text for [error], shared by the full-screen view, the banner and the controls' failure lines. */
@Composable
fun automationErrorText(error: QueryError): String = when (error) {
    QueryError.Unauthorized -> stringResource(R.string.automations_error_unauthorized)
    QueryError.Unreachable -> stringResource(R.string.automations_error_unreachable)
    QueryError.Unsupported -> stringResource(R.string.automations_unsupported)
    QueryError.NotApproved -> stringResource(R.string.automations_approval_hint)
    QueryError.Invalid -> stringResource(R.string.automations_error_invalid)
    is QueryError.Server -> stringResource(R.string.automations_error_server, error.message)
}

@Composable
fun AutomationLoadingView(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.automations_loading)
    Box(
        modifier = modifier.fillMaxSize().clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        TroupeSpinner(size = 22.dp)
    }
}

/**
 * The list could not be loaded and nothing is shown yet. Retry is offered where trying again can help:
 * not for a rejected token, an app that must be updated, or a server without Automations.
 * Scrolls so that the pull-to-refresh gesture around it still works.
 */
@Composable
fun AutomationErrorView(error: QueryError, retrying: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = automationErrorText(error),
            style = typo.bodyMedium,
            color = palette.mutedForeground,
            textAlign = TextAlign.Center,
        )
        if (error == QueryError.Unreachable || error is QueryError.Server) {
            TroupeButton(
                text = stringResource(R.string.automations_retry),
                variant = TroupeButtonVariant.Secondary,
                loading = retrying,
                minHeight = 48.dp,
                onClick = onRetry,
            )
        }
    }
}

@Composable
fun AutomationEmptyView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.automations_empty),
            style = TroupeTheme.typography.bodyMedium,
            color = TroupeTheme.palette.mutedForeground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A failed reload above a list that still shows its last good data. */
@Composable
fun AutomationErrorBanner(error: QueryError, retrying: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val palette = TroupeTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.destructive.copy(alpha = 0.12f))
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = automationErrorText(error),
            style = TroupeTheme.typography.bodySmall,
            color = palette.destructive,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        TroupeButton(
            text = stringResource(R.string.automations_retry),
            variant = TroupeButtonVariant.Ghost,
            loading = retrying,
            minHeight = 48.dp,
            onClick = onRetry,
        )
    }
}
