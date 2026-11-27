@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.cleaner

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeConnection
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerActivity
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerError
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerIntent
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState
import com.eynnzerr.telemetrycleaner.ui.components.ExpressiveButton
import com.eynnzerr.telemetrycleaner.ui.components.ExpressiveButtonSize
import com.eynnzerr.telemetrycleaner.ui.components.PackageLoadingIndicator
import com.eynnzerr.telemetrycleaner.ui.components.statusLabel

/** Captures the semantic status shown by [SummaryCard] so off-screen changes can trigger the floating pill. */
internal data class SummaryStatusKey(
    val connection: PrivilegeConnection,
    val activity: CleanerActivity,
    val installedCount: Int?,
    val hasIncompleteResults: Boolean,
    val noSpacesSelected: Boolean,
    val hasSnapshot: Boolean,
    val error: CleanerError?,
)

/**
 * Returns whether the top floating status capsule should be shown for the current [CleanerState].
 *
 * The capsule appears whenever [SummaryCard] is scrolled off-screen and the cleaner is in an active
 * or non-nominal state (scanning, changing, disconnected, error, empty scope, or incomplete scan).
 * It disappears whenever [SummaryCard] is visible, reappears if scrolled away again while still in
 * that state, and is never shown for the resting "X installed" state.
 */
internal fun CleanerState.shouldShowFloatingStatusPill(isSummaryVisible: Boolean): Boolean {
    if (isSummaryVisible) return false
    val key = summaryStatusKey()
    val isNominalInstalledSummary = activity == CleanerActivity.IDLE &&
        connection.status == PrivilegeStatus.READY &&
        error == null &&
        key.installedCount != null &&
        !key.hasIncompleteResults
    return !isNominalInstalledSummary
}

/** Derives a comparable status fingerprint for [SummaryCard] and [AnimatedFloatingStatusPill]. */
internal fun CleanerState.summaryStatusKey(): SummaryStatusKey {
    val ready = connection.status == PrivilegeStatus.READY
    val hasIncompleteResults = snapshot?.packages?.any { result ->
        selectedUserIds.any { result.hasUnresolvedStatus(it) }
    } == true
    val installedCount = if (ready && snapshot != null && selectedUserIds.isNotEmpty()) {
        snapshot.packages.count { result ->
            selectedUserIds.any { userId ->
                result.users[userId] == PackageState.INSTALLED ||
                    result.users[userId] == PackageState.DISABLED ||
                    result.users[userId] == PackageState.SUSPENDED ||
                    result.users[userId] == PackageState.HIDDEN
            }
        }
    } else {
        null
    }
    return SummaryStatusKey(
        connection = connection,
        activity = activity,
        installedCount = installedCount,
        hasIncompleteResults = hasIncompleteResults,
        noSpacesSelected = ready && snapshot != null && selectedUserIds.isEmpty(),
        hasSnapshot = snapshot != null,
        error = error,
    )
}

private enum class SummaryTone { ERROR, DISCONNECTED, ATTENTION, POSITIVE }

private data class SummaryPresentation(
    val summaryTitle: String,
    val pillText: String,
    val connectionLabel: String,
    val subtitle: Int?,
    val cardTone: SummaryTone,
    val pillTone: SummaryTone,
    val busy: Boolean,
    val ready: Boolean,
    val isRoot: Boolean,
    val installedCount: Int?,
    val iconShape: Shape,
)

@Composable
private fun summaryPresentation(state: CleanerState): SummaryPresentation {
    val key = state.summaryStatusKey()
    val (backend, status) = state.connection
    val isRoot = backend == PrivilegeBackend.ROOT
    val ready = status == PrivilegeStatus.READY
    val busy = state.activity != CleanerActivity.IDLE || status == PrivilegeStatus.CONNECTING
    val installedCount = key.installedCount
    val hasIncompleteResults = key.hasIncompleteResults
    val connectionLabel = stringResource(state.connection.statusLabel())

    val summaryTitle = when {
        state.activity == CleanerActivity.SWITCHING_BACKEND -> stringResource(R.string.switching_backend)
        state.activity == CleanerActivity.CHANGING -> stringResource(R.string.changing)
        state.activity == CleanerActivity.SCANNING -> stringResource(R.string.scanning)
        key.noSpacesSelected -> stringResource(R.string.scope_empty)
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
                state.snapshot == null && state.error == null -> R.string.scan_ready_description
                hasIncompleteResults -> R.string.summary_scan_incomplete_description
                else -> null
            }
        }
    } else {
        null
    }

    val cardTone = when {
        !busy && status == PrivilegeStatus.ERROR -> SummaryTone.ERROR
        !busy && !ready -> SummaryTone.DISCONNECTED
        !busy && installedCount != null && installedCount > 0 -> SummaryTone.ATTENTION
        else -> SummaryTone.POSITIVE
    }
    val pillTone = if (!busy && state.error != null) SummaryTone.ERROR else cardTone

    val iconShape = when {
        installedCount != null && installedCount > 0 -> MaterialShapes.SoftBurst.toShape()
        ready && installedCount == 0 -> MaterialShapes.Cookie4Sided.toShape()
        else -> MaterialShapes.Clover4Leaf.toShape()
    }

    val pillText = when {
        !busy && state.error != null -> stringResource(state.error.messageRes())
        !busy && !ready -> connectionLabel
        !busy && hasIncompleteResults -> stringResource(R.string.summary_scan_incomplete)
        else -> summaryTitle
    }

    return SummaryPresentation(
        summaryTitle = summaryTitle,
        pillText = pillText,
        connectionLabel = connectionLabel,
        subtitle = subtitle,
        cardTone = cardTone,
        pillTone = pillTone,
        busy = busy,
        ready = ready,
        isRoot = isRoot,
        installedCount = installedCount,
        iconShape = iconShape,
    )
}

/** Shows the selected privilege backend, its next action, and the scan summary with expressive color & shape hierarchy. */
@Composable
internal fun SummaryCard(state: CleanerState, onIntent: (CleanerIntent) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val presentation = summaryPresentation(state)
    val (_, status) = state.connection

    val (targetContainer, targetContent, targetBadgeBg) = when (presentation.cardTone) {
        SummaryTone.ERROR -> Triple(
            colors.errorContainer,
            colors.onErrorContainer,
            colors.error.copy(alpha = 0.14f),
        )
        SummaryTone.DISCONNECTED -> Triple(
            colors.surfaceContainerHigh,
            colors.onSurface,
            colors.surfaceContainerHighest,
        )
        SummaryTone.ATTENTION -> Triple(
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
            colors.tertiary.copy(alpha = 0.14f),
        )
        SummaryTone.POSITIVE -> Triple(
            colors.primaryContainer,
            colors.onPrimaryContainer,
            colors.primary.copy(alpha = 0.14f),
        )
    }

    val colorSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val containerColor by animateColorAsState(targetContainer, colorSpec, label = "heroContainer")
    val contentColor by animateColorAsState(targetContent, colorSpec, label = "heroContent")
    val badgeBgColor by animateColorAsState(targetBadgeBg, colorSpec, label = "heroBadgeBg")

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
                            text = presentation.connectionLabel,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                        )
                    }
                    Text(
                        text = presentation.summaryTitle,
                        style = if (presentation.installedCount != null && !presentation.busy) {
                            MaterialTheme.typography.displaySmallEmphasized
                        } else {
                            MaterialTheme.typography.headlineLargeEmphasized
                        },
                        modifier = Modifier.semantics { heading() },
                    )
                }
                if (presentation.busy) {
                    PackageLoadingIndicator(
                        contained = true,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(76.dp),
                        shape = presentation.iconShape,
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
            if (presentation.subtitle != null) {
                Text(
                    text = stringResource(presentation.subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.85f),
                )
            }
            if (!presentation.busy && !presentation.ready) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        presentation.isRoot -> ExpressiveButton(
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
                    val alternative = if (presentation.isRoot) PrivilegeBackend.SHIZUKU else PrivilegeBackend.ROOT
                    TextButton(
                        onClick = { onIntent(CleanerIntent.SelectBackend(alternative)) },
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                    ) {
                        Text(stringResource(if (presentation.isRoot) R.string.use_shizuku else R.string.use_root))
                    }
                }
            } else if (!presentation.busy && state.snapshot == null && state.error == null) {
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

/**
 * Floats an Expressive status capsule below the top bar when [SummaryCard] is scrolled out of view
 * and the cleaner status changes; tapping returns to [SummaryCard] and dismisses the capsule.
 */
@Composable
internal fun AnimatedFloatingStatusPill(
    visible: Boolean,
    state: CleanerState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(top = 10.dp, start = 16.dp, end = 16.dp),
        enter = slideInVertically(animationSpec = motion.defaultSpatialSpec()) { -it } +
            scaleIn(animationSpec = motion.defaultSpatialSpec(), initialScale = 0.88f) +
            fadeIn(animationSpec = motion.fastEffectsSpec()),
        exit = slideOutVertically(animationSpec = motion.fastSpatialSpec()) { -it } +
            scaleOut(animationSpec = motion.fastSpatialSpec(), targetScale = 0.88f) +
            fadeOut(animationSpec = motion.fastEffectsSpec()),
        label = "Top floating status capsule",
    ) {
        val lastVisibleState = remember { mutableStateOf(state) }
        if (visible) {
            SideEffect { lastVisibleState.value = state }
        }
        FloatingStatusPill(
            state = if (visible) state else lastVisibleState.value,
            onClick = onClick,
        )
    }
}

@Composable
private fun FloatingStatusPill(
    state: CleanerState,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val isDark = colors.surface.luminance() < 0.5f
    val presentation = summaryPresentation(state)

    val (targetContainer, targetContent, targetBadgeBg) = when (presentation.pillTone) {
        SummaryTone.ERROR -> Triple(
            colors.errorContainer,
            colors.onErrorContainer,
            colors.error.copy(alpha = 0.14f),
        )
        SummaryTone.DISCONNECTED -> Triple(
            if (isDark) colors.surfaceContainerHighest else colors.surfaceContainerLowest,
            colors.onSurface,
            colors.surfaceContainerHigh,
        )
        SummaryTone.ATTENTION -> Triple(
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
            colors.tertiary.copy(alpha = 0.14f),
        )
        SummaryTone.POSITIVE -> Triple(
            colors.primaryContainer,
            colors.onPrimaryContainer,
            colors.primary.copy(alpha = 0.14f),
        )
    }

    val colorSpec = motion.defaultEffectsSpec<Color>()
    val containerColor by animateColorAsState(targetContainer, colorSpec, label = "pillContainer")
    val contentColor by animateColorAsState(targetContent, colorSpec, label = "pillContent")
    val badgeBgColor by animateColorAsState(targetBadgeBg, colorSpec, label = "pillBadgeBg")

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 6.dp,
        border = BorderStroke(0.5.dp, contentColor.copy(alpha = 0.14f)),
        modifier = Modifier
            .widthIn(max = 560.dp)
            .animateContentSize(animationSpec = motion.fastSpatialSpec())
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = presentation.busy,
                transitionSpec = {
                    fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                },
                label = "pillIndicator",
            ) { isBusy ->
                if (isBusy) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = contentColor,
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(24.dp),
                        shape = presentation.iconShape,
                        color = badgeBgColor,
                        contentColor = contentColor,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = contentColor,
                            )
                        }
                    }
                }
            }
            AnimatedContent(
                targetState = presentation.pillText,
                transitionSpec = {
                    (fadeIn(motion.fastEffectsSpec()) + slideInVertically(motion.fastSpatialSpec()) { it / 3 }) togetherWith
                        fadeOut(motion.fastEffectsSpec())
                },
                label = "pillText",
            ) { labelText ->
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            text = stringResource(error.messageRes()),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@StringRes
internal fun CleanerError.messageRes(): Int = when (this) {
    CleanerError.SCAN_FAILED -> R.string.scan_failed
    CleanerError.SCAN_SPACE_QUERY_FAILED -> R.string.scan_space_query_failed
    CleanerError.SCAN_SPACE_PERMISSION_DENIED -> R.string.scan_space_permission_denied
    CleanerError.SCAN_SPACE_RESPONSE_INVALID -> R.string.scan_space_response_invalid
    CleanerError.SCAN_CURRENT_SPACE_MISSING -> R.string.scan_current_space_missing
    CleanerError.ACTION_FAILED -> R.string.action_failed
    CleanerError.BACKEND_SELECTION_FAILED -> R.string.backend_selection_failed
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_SETUP_URL = "https://shizuku.rikka.app/guide/setup/"
