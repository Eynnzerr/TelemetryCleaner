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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.eynnzerr.telemetrycleaner.data.DhizukuStatus
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
import com.eynnzerr.telemetrycleaner.ui.components.actionLabel
import com.eynnzerr.telemetrycleaner.ui.components.displayMessage
import com.eynnzerr.telemetrycleaner.ui.components.displayName
import com.eynnzerr.telemetrycleaner.ui.components.performDhizukuStatusAction
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
    dhizukuFallbackEnabled: Boolean = false,
    dhizukuStatus: DhizukuStatus = DhizukuStatus.MISSING,
    onRequestDhizukuPermission: () -> Unit = {},
    onRefreshDhizukuStatus: () -> Unit = {},
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
            val targetedUsersByPackage = panel.targets.groupBy(ChangeTarget::packageName) { it.userId }
                .mapValues { (_, ids) -> ids.toSet() }
            Column(verticalArrangement = Arrangement.spacedBy(SegmentedListDefaults.Gap)) {
                panel.targets.forEachIndexed { index, target ->
                    val detected = snapshot?.packages?.firstOrNull { it.entry.packageName == target.packageName }
                    val losesLastApk = uninstall &&
                        detected?.losesApkOnUninstall(targetedUsersByPackage[target.packageName].orEmpty()) == true
                    TargetRow(
                        target = target,
                        snapshot = snapshot,
                        shape = segmentedItemShape(index, panel.targets.size),
                        warningText = if (losesLastApk) stringResource(R.string.confirm_uninstall_last_apk_warning) else null,
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
            val denialLogs = panel.outcomes.filterNot { it.success }.mapNotNull { it.denialLog }
            val canCopyDenialLogs = panel.action == PackageAction.UNINSTALL && denialLogs.isNotEmpty()
            val dhizukuNoticeEligible = panel.needsDhizukuNotice(
                backend = state.connection.backend,
                currentUserId = snapshot?.currentUserId,
                dhizukuFallbackEnabled = dhizukuFallbackEnabled,
            )
            var sawUnreadyDhizuku by remember(panel) {
                mutableStateOf(dhizukuNoticeEligible && dhizukuStatus != DhizukuStatus.READY)
            }
            LaunchedEffect(panel, dhizukuNoticeEligible) {
                if (dhizukuNoticeEligible) {
                    onRefreshDhizukuStatus()
                }
            }
            LaunchedEffect(dhizukuNoticeEligible, dhizukuStatus) {
                if (dhizukuNoticeEligible && dhizukuStatus != DhizukuStatus.READY) {
                    sawUnreadyDhizuku = true
                }
            }
            val showDhizukuNotice = dhizukuNoticeEligible &&
                (dhizukuStatus != DhizukuStatus.READY || sawUnreadyDhizuku)
            if (showDhizukuNotice) {
                DhizukuResultsNoticeCard(
                    status = dhizukuStatus,
                    onRequestPermission = onRequestDhizukuPermission,
                    onRefreshStatus = onRefreshDhizukuStatus,
                )
            }
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
                        outcome.success && panel.action == PackageAction.UNINSTALL -> {
                            stringResource(R.string.uninstalled_result) to StatusTone.PRIMARY
                        }
                        outcome.success && panel.action == PackageAction.DISABLE -> {
                            val res = when {
                                outcome.suspendedInstead -> R.string.status_suspended
                                outcome.hiddenInstead -> R.string.status_hidden
                                else -> R.string.status_disabled
                            }
                            stringResource(res) to StatusTone.SECONDARY
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

/** Explains why Dhizuku enhancement did not run and offers a direct setup or authorization action. */
@Composable
private fun DhizukuResultsNoticeCard(
    status: DhizukuStatus,
    onRequestPermission: () -> Unit,
    onRefreshStatus: () -> Unit,
) {
    val context = LocalContext.current
    val (containerColor, contentColor) = when (status) {
        DhizukuStatus.READY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        DhizukuStatus.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    val titleRes = if (status == DhizukuStatus.READY) {
        R.string.dhizuku_results_ready_title
    } else {
        R.string.dhizuku_results_inactive_title
    }
    val messageRes = when (status) {
        DhizukuStatus.MISSING -> R.string.dhizuku_results_missing_message
        DhizukuStatus.INACTIVE -> R.string.dhizuku_results_inactive_message
        DhizukuStatus.PERMISSION_REQUIRED -> R.string.dhizuku_results_permission_message
        DhizukuStatus.ERROR -> R.string.dhizuku_results_error_message
        DhizukuStatus.READY -> R.string.dhizuku_results_ready_message
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SegmentedListDefaults.OuterCorner),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodySmall,
            )
            if (status != DhizukuStatus.READY) {
                ExpressiveButton(
                    label = status.actionLabel(),
                    size = ExpressiveButtonSize.SMALL,
                ) {
                    performDhizukuStatusAction(
                        context = context,
                        status = status,
                        onRequestPermission = onRequestPermission,
                        onRefreshStatus = onRefreshStatus,
                    )
                }
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
    warningText: String? = null,
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
            if (warningText != null) {
                Text(
                    text = warningText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
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
