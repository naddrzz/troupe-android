package dev.troupe.android.automations.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant

/**
 * The decision a run waits for: the approval step's message and Approve and Reject. While [pending] a decision is on
 * its way and both buttons are off, so one run takes one decision at a time.
 */
@Composable
fun ApprovalCard(
    message: String,
    pending: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Column(modifier.automationCard().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.automations_approval_card_title),
            style = typo.titleSmall,
            color = palette.warning,
            modifier = Modifier.semantics { heading() },
        )
        if (message.isNotBlank()) {
            Text(message, style = typo.bodyMedium, color = palette.foreground)
        }
        Text(stringResource(R.string.automations_approval_card_explainer), style = typo.bodySmall, color = palette.mutedForeground)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TroupeButton(
                text = stringResource(R.string.automations_reject),
                variant = TroupeButtonVariant.Secondary,
                enabled = !pending,
                minHeight = 48.dp,
                onClick = onReject,
                modifier = Modifier.weight(1f),
            )
            TroupeButton(
                text = stringResource(R.string.automations_approve),
                enabled = !pending,
                minHeight = 48.dp,
                onClick = onApprove,
                modifier = Modifier.weight(1f),
            )
        }
        if (pending) {
            Text(
                text = stringResource(R.string.automations_decision_sending),
                style = typo.bodySmall,
                color = palette.mutedForeground,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
