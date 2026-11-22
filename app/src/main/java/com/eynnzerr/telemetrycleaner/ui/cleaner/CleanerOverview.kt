@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.cleaner

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerActivity
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerError
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerIntent
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState
import com.eynnzerr.telemetrycleaner.ui.components.ExpressiveButton
import com.eynnzerr.telemetrycleaner.ui.components.ExpressiveButtonSize
import com.eynnzerr.telemetrycleaner.ui.components.PackageLoadingIndicator
import com.eynnzerr.telemetrycleaner.ui.components.statusLabel

/** Shows the selected privilege backend, its next action, and the scan summary with expressive color & shape hierarchy. */
@Composable
internal fun SummaryCard(state: CleanerState, onIntent: (CleanerIntent) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val snapshot = state.snapshot
    val (backend, status) = state.connection
    val isRoot = backend == PrivilegeBackend.ROOT
    val ready = status == PrivilegeStatus.READY
    val busy = state.activity != CleanerActivity.IDLE || status == PrivilegeStatus.CONNECTING
    val hasIncompleteResults = snapshot?.packages?.any { result ->
        state.selectedUserIds.any { result.hasUnresolvedStatus(it) }
    } == true
    val installedCount = if (ready && snapshot != null && state.selectedUserIds.isNotEmpty()) {
        snapshot.packages.count { result ->
            state.selectedUserIds.any { userId ->
                result.users[userId] == PackageState.INSTALLED ||
                    result.users[userId] == PackageState.DISABLED
            }
        }
    } else {
        null
    }

    val summaryTitle = when {
        state.activity == CleanerActivity.SWITCHING_BACKEND -> stringResource(R.string.switching_backend)
        state.activity == CleanerActivity.CHANGING -> stringResource(R.string.changing)
        state.activity == CleanerActivity.SCANNING -> stringResource(R.string.scanning)
        ready && snapshot != null && state.selectedUserIds.isEmpty() -> {
            stringResource(R.string.scope_empty)
        }
        installedCount != null -> {
            if (installedCount == 0 && hasIncompleteResults) stringResource(R.string.summary_scan_incomplete)
            else pluralStringResource(R.plurals.installed_components, installedCount, installedCount)
        }
        ready && state.error == null -> stringResource(R.string.scan_ready)
        status == PrivilegeStatus.CONNECTING -> stringResource(R.string.scan_preparing)
        !isRoot && status == PrivilegeStatus.MISSING -> stringResource(R.string.shizuku_missing)
        !isRoot && status == PrivilegeStatus.STOPPED -> stringResource(R.string.shizuku_stopped)
        !isRoot && status == PrivilegeStatus.PERMISSION_REQUIRED -> stringResource(R.string.shizuku_permission)
        else -> stringResource(R.string.scan_unavailable)
    }

    val subtitle = if (state.activity == CleanerActivity.IDLE) {
        when (status) {
            PrivilegeStatus.MISSING -> if (isRoot) R.string.root_setup_explanation else R.string.setup_explanation
            PrivilegeStatus.STOPPED -> if (isRoot) null else R.string.start_explanation
            PrivilegeStatus.PERMISSION_REQUIRED ->
                if (isRoot) R.string.root_permission_explanation else R.string.permission_explanation
            PrivilegeStatus.CONNECTING -> if (isRoot) null else R.string.waiting_for_shizuku
            PrivilegeStatus.ERROR -> if (isRoot) R.string.root_error_explanation else null
            PrivilegeStatus.READY -> when {
                snapshot == null && state.error == null -> R.string.scan_ready_description
                hasIncompleteResults -> R.string.summary_scan_incomplete_description
                else -> null
            }
        }
    } else {
        null
    }

    val (targetContainer, targetContent, targetBadgeBg) = when {
        !busy && status == PrivilegeStatus.ERROR -> Triple(
            colors.errorContainer,
            colors.onErrorContainer,
            colors.error.copy(alpha = 0.14f),
        )
        !busy && !ready -> Triple(
            colors.surfaceContainerHigh,
            colors.onSurface,
            colors.surfaceContainerHighest,
        )
        !busy && installedCount != null && installedCount > 0 -> Triple(
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
            colors.tertiary.copy(alpha = 0.14f),
        )
        !busy && installedCount == 0 && !hasIncompleteResults -> Triple(
            colors.primaryContainer,
            colors.onPrimaryContainer,
            colors.primary.copy(alpha = 0.14f),
        )
        else -> Triple(
            colors.primaryContainer,
            colors.onPrimaryContainer,
            colors.primary.copy(alpha = 0.14f),
        )
    }

    val colorSpec = MaterialTheme.motionScheme.defaultEffectsSpec<androidx.compose.ui.graphics.Color>()
    val containerColor by animateColorAsState(targetContainer, colorSpec, label = "heroContainer")
    val contentColor by animateColorAsState(targetContent, colorSpec, label = "heroContent")
    val badgeBgColor by animateColorAsState(targetBadgeBg, colorSpec, label = "heroBadgeBg")

    val iconShape = when {
        installedCount != null && installedCount > 0 -> MaterialShapes.SoftBurst.toShape()
        ready && installedCount == 0 -> MaterialShapes.Cookie4Sided.toShape()
        else -> MaterialShapes.Clover4Leaf.toShape()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = badgeBgColor,
                        contentColor = contentColor,
                    ) {
                        Text(
                            text = stringResource(state.connection.statusLabel()),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                        )
                    }
                    Text(
                        text = summaryTitle,
                        style = if (installedCount != null && !busy) {
                            MaterialTheme.typography.displaySmallEmphasized
                        } else {
                            MaterialTheme.typography.headlineLargeEmphasized
                        },
                        modifier = Modifier.semantics { heading() },
                    )
                }
                if (busy) {
                    PackageLoadingIndicator(
                        contained = true,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(76.dp),
                        shape = iconShape,
                        color = badgeBgColor,
                        contentColor = contentColor,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = contentColor,
                            )
                        }
                    }
                }
            }
            if (subtitle != null) {
                Text(
                    text = stringResource(subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.85f),
                )
            }
            if (!busy && !ready) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        isRoot -> ExpressiveButton(
                            label = when (status) {
                                PrivilegeStatus.STOPPED -> R.string.connect_root
                                PrivilegeStatus.PERMISSION_REQUIRED -> R.string.authorize
                                else -> R.string.try_again
                            },
                            size = ExpressiveButtonSize.SMALL,
                        ) {
                            onIntent(CleanerIntent.RequestPermission)
                        }
                        status == PrivilegeStatus.MISSING -> ExpressiveButton(
                            label = R.string.get_shizuku,
                            size = ExpressiveButtonSize.SMALL,
                        ) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_SETUP_URL)))
                        }
                        status == PrivilegeStatus.STOPPED -> ExpressiveButton(
                            label = R.string.open_shizuku,
                            size = ExpressiveButtonSize.SMALL,
                        ) {
                            val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                            context.startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_SETUP_URL)))
                        }
                        status == PrivilegeStatus.PERMISSION_REQUIRED -> ExpressiveButton(
                            label = R.string.authorize,
                            size = ExpressiveButtonSize.SMALL,
                        ) {
                            onIntent(CleanerIntent.RequestPermission)
                        }
                        status == PrivilegeStatus.ERROR -> ExpressiveButton(
                            label = R.string.try_again,
                            size = ExpressiveButtonSize.SMALL,
                        ) {
                            onIntent(CleanerIntent.Refresh)
                        }
                    }
                    val alternative = if (isRoot) PrivilegeBackend.SHIZUKU else PrivilegeBackend.ROOT
                    TextButton(
                        onClick = { onIntent(CleanerIntent.SelectBackend(alternative)) },
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                    ) {
                        Text(stringResource(if (isRoot) R.string.use_shizuku else R.string.use_root))
                    }
                }
            } else if (!busy && snapshot == null && state.error == null) {
                ExpressiveButton(
                    label = R.string.scan,
                    size = ExpressiveButtonSize.SMALL,
                    leadingIcon = R.drawable.ic_refresh,
                ) {
                    onIntent(CleanerIntent.Refresh)
                }
            }
        }
    }
}

/** Shows the error reported by the current cleaner operation. */
@Composable
internal fun CleanerErrorCard(error: CleanerError) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(
            text = stringResource(
                when (error) {
                    CleanerError.SCAN_FAILED -> R.string.scan_failed
                    CleanerError.SCAN_SPACE_QUERY_FAILED -> R.string.scan_space_query_failed
                    CleanerError.SCAN_SPACE_PERMISSION_DENIED -> R.string.scan_space_permission_denied
                    CleanerError.SCAN_SPACE_RESPONSE_INVALID -> R.string.scan_space_response_invalid
                    CleanerError.SCAN_CURRENT_SPACE_MISSING -> R.string.scan_current_space_missing
                    CleanerError.ACTION_FAILED -> R.string.action_failed
                    CleanerError.BACKEND_SELECTION_FAILED -> R.string.backend_selection_failed
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_SETUP_URL = "https://shizuku.rikka.app/guide/setup/"
