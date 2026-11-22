@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.cleaner

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TonalToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.ChangeTarget
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerIntent
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerPanel
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState
import com.eynnzerr.telemetrycleaner.ui.components.CleanerSheetLayout
import com.eynnzerr.telemetrycleaner.ui.components.ExpressiveButton
import com.eynnzerr.telemetrycleaner.ui.components.ExpressiveButtonSize
import com.eynnzerr.telemetrycleaner.ui.components.SegmentedListDefaults
import com.eynnzerr.telemetrycleaner.ui.components.SheetTitle
import com.eynnzerr.telemetrycleaner.ui.components.StatusBadge
import com.eynnzerr.telemetrycleaner.ui.components.StatusTone
import com.eynnzerr.telemetrycleaner.ui.components.displayMessage
import com.eynnzerr.telemetrycleaner.ui.components.displayName
import com.eynnzerr.telemetrycleaner.ui.components.segmentedItemShape
import com.eynnzerr.telemetrycleaner.ui.packages.PackageDetailsSheetContent
import kotlinx.coroutines.launch

/** Renders sheet content shared by the modal and static Compose previews. */
@Composable
internal fun CleanerSheetContent(
    panel: CleanerPanel,
    state: CleanerState,
    onIntent: (CleanerIntent) -> Unit,
    showPermissionsInitially: Boolean = false,
) {
    val snapshot = state.snapshot
    val dismiss = { onIntent(CleanerIntent.DismissPanel) }
    val scrollState = rememberScrollState()
    when (panel) {
        is CleanerPanel.Details -> PackageDetailsSheetContent(
            packageName = panel.packageName,
            snapshot = snapshot,
            onDismiss = dismiss,
            showPermissionsInitially = showPermissionsInitially,
        )
        is CleanerPanel.Confirmation -> CleanerSheetLayout(Modifier.verticalScroll(scrollState)) {
            val uninstall = panel.action == PackageAction.UNINSTALL
            val (title, description, button) = when (panel.action) {
                PackageAction.UNINSTALL -> Triple(R.string.confirm_uninstall, R.string.uninstall_description, R.string.uninstall)
                PackageAction.DISABLE -> Triple(R.string.freeze_title, R.string.freeze_description, R.string.freeze_action)
                PackageAction.RESTORE -> Triple(R.string.confirm_restore, R.string.restore_description, R.string.restore)
            }
            val buttonIcon = when (panel.action) {
                PackageAction.UNINSTALL -> R.drawable.ic_delete
                PackageAction.DISABLE -> R.drawable.ic_freeze
                PackageAction.RESTORE -> R.drawable.ic_restore
            }
            SheetTitle(title)
            if (panel.action != PackageAction.RESTORE) {
                val freezeOnly = panel.action == PackageAction.DISABLE
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    TonalToggleButton(
                        checked = !freezeOnly,
                        onCheckedChange = { if (it) onIntent(CleanerIntent.SetFreezeOnly(false)) },
                        enabled = state.readyForActions,
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.uninstall), maxLines = 1)
                    }
                    TonalToggleButton(
                        checked = freezeOnly,
                        onCheckedChange = { if (it) onIntent(CleanerIntent.SetFreezeOnly(true)) },
                        enabled = state.readyForActions,
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_freeze),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.freeze_only), maxLines = 1)
                    }
                }
            }
            Text(
                text = stringResource(description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(SegmentedListDefaults.Gap)) {
                panel.targets.forEachIndexed { index, target ->
                    TargetRow(
                        target = target,
                        snapshot = snapshot,
                        shape = segmentedItemShape(index, panel.targets.size),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = dismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.cancel))
                }
                ExpressiveButton(
                    label = button,
                    size = ExpressiveButtonSize.SMALL,
                    leadingIcon = buttonIcon,
                    enabled = state.readyForActions,
                    destructive = uninstall,
                ) {
                    onIntent(CleanerIntent.ConfirmAction)
                }
            }
        }
        is CleanerPanel.Results -> CleanerSheetLayout(Modifier.verticalScroll(scrollState)) {
            SheetTitle(R.string.operation_results)
            val denialLogs = panel.outcomes.filterNot { it.success || it.disabledInstead }
                .mapNotNull { it.denialLog }
            val canCopyDenialLogs = panel.action == PackageAction.UNINSTALL && denialLogs.isNotEmpty()
            if (canCopyDenialLogs) {
                Text(
                    stringResource(R.string.uninstall_denial_feedback_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(SegmentedListDefaults.Gap)) {
                panel.outcomes.forEachIndexed { index, outcome ->
                    val (statusText, tone) = when {
                        outcome.disabledInstead -> stringResource(R.string.disabled_instead_result) to StatusTone.SECONDARY
                        outcome.success && panel.action == PackageAction.UNINSTALL -> {
                            stringResource(R.string.uninstalled_result) to StatusTone.PRIMARY
                        }
                        outcome.success && panel.action == PackageAction.DISABLE -> {
                            stringResource(R.string.status_disabled) to StatusTone.SECONDARY
                        }
                        outcome.success -> stringResource(R.string.restored_result) to StatusTone.PRIMARY
                        else -> (outcome.failure?.displayMessage() ?: stringResource(R.string.unverified_result)) to StatusTone.ERROR
                    }
                    TargetRow(
                        target = outcome.target,
                        snapshot = snapshot,
                        shape = segmentedItemShape(index, panel.outcomes.size),
                        statusBadge = statusText to tone,
                    )
                }
            }
            if (canCopyDenialLogs) {
                UninstallResultActions(denialLogs.joinToString("\n\n"), dismiss)
            } else {
                ExpressiveButton(
                    label = R.string.done,
                    modifier = Modifier.fillMaxWidth(),
                    size = ExpressiveButtonSize.MEDIUM,
                    onClick = dismiss,
                )
            }
        }
    }
}

/** Lets the user dismiss the result sheet or copy the batch's uninstall denial logs. */
@Composable
private fun UninstallResultActions(denialLogs: String, onDone: () -> Unit) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(R.string.copy_uninstall_denial_logs)
    val buttonHeight = SplitButtonDefaults.MediumContainerHeight
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val leadingWidth = maxWidth - buttonHeight - SplitButtonDefaults.Spacing
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = onDone,
                    modifier = Modifier
                        .width(leadingWidth)
                        .heightIn(min = buttonHeight),
                    shapes = SplitButtonDefaults.leadingButtonShapesFor(buttonHeight),
                    contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(buttonHeight),
                ) {
                    Text(
                        stringResource(android.R.string.ok),
                        style = MaterialTheme.typography.labelLargeEmphasized,
                    )
                }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copyLabel, denialLogs)))
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, R.string.uninstall_denial_logs_copied, Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    },
                    modifier = Modifier.size(buttonHeight),
                    shapes = SplitButtonDefaults.trailingButtonShapesFor(buttonHeight),
                    contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(buttonHeight),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_copy),
                        contentDescription = copyLabel,
                        modifier = Modifier.size(SplitButtonDefaults.MediumTrailingButtonIconSize),
                    )
                }
            },
        )
    }
}

@Composable
private fun TargetRow(
    target: ChangeTarget,
    snapshot: ScanSnapshot?,
    shape: Shape,
    statusBadge: Pair<String, StatusTone>? = null,
) {
    val result = snapshot?.packages?.firstOrNull { it.entry.packageName == target.packageName }
    val space = snapshot?.users?.firstOrNull { it.id == target.userId }?.displayName()
        ?: stringResource(R.string.space_id, target.userId)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = result?.entry?.displayName ?: target.packageName,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(text = space, tone = StatusTone.NEUTRAL)
            }
            if (result != null && result.entry.displayName != target.packageName) {
                Text(
                    text = target.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (statusBadge != null) {
                StatusBadge(
                    text = statusBadge.first,
                    tone = statusBadge.second,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
