@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.cleaner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerActivity
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerIntent
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState

/** Provides Expressive settings and refresh actions with scroll-aware tonal elevation. */
@Composable
internal fun CleanerTopBar(
    activity: CleanerActivity,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLargeEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onOpenSettings,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.settings),
                )
            }
        },
        actions = {
            IconButton(
                onClick = onRefresh,
                enabled = activity == CleanerActivity.IDLE,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.refresh),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/**
 * Floats an Expressive [HorizontalFloatingToolbar] above the navigation bar while batch selections
 * are active, retaining its last visible content during the exit spring animation.
 */
@Composable
internal fun AnimatedSelectionBar(
    state: CleanerState,
    onIntent: (CleanerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = PackageAction.entries.any { state.targetsFor(it).isNotEmpty() }
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        enter = slideInVertically(animationSpec = motion.defaultSpatialSpec()) { it / 2 } +
            scaleIn(animationSpec = motion.defaultSpatialSpec(), initialScale = 0.92f) +
            fadeIn(animationSpec = motion.fastEffectsSpec()),
        exit = slideOutVertically(animationSpec = motion.fastSpatialSpec()) { it / 2 } +
            scaleOut(animationSpec = motion.fastSpatialSpec(), targetScale = 0.92f) +
            fadeOut(animationSpec = motion.fastEffectsSpec()),
        label = "Floating batch selection toolbar",
    ) {
        val lastVisibleState = remember { mutableStateOf(state) }
        if (visible) {
            SideEffect { lastVisibleState.value = state }
        }
        FloatingSelectionToolbar(
            state = if (visible) state else lastVisibleState.value,
            onIntent = onIntent,
            enabled = visible,
        )
    }
}

@Composable
private fun FloatingSelectionToolbar(
    state: CleanerState,
    onIntent: (CleanerIntent) -> Unit,
    enabled: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val count = state.selectedPackageNames.size
    val canRestore = state.targetsFor(PackageAction.RESTORE).isNotEmpty()
    val canUninstall = state.targetsFor(PackageAction.UNINSTALL).isNotEmpty()
    val selectionA11yLabel = pluralStringResource(R.plurals.selected_components, count, count)
    val buttonContentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

    BoxWithConstraints(contentAlignment = Alignment.Center) {
        // When both actions are shown on narrow split-screen or enlarged font scales, switch to
        // tooltip-backed icon buttons so action labels are never clipped off-screen.
        val useIconOnlyActions = canRestore && canUninstall && maxWidth < (340.dp * fontScale)

        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = colorScheme.surfaceContainerHigh,
                toolbarContentColor = colorScheme.onSurface,
            ),
            expandedShadowElevation = 3.dp,
            collapsedShadowElevation = 3.dp,
            contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
            leadingContent = {
                Text(
                    text = pluralStringResource(R.plurals.selected_components_short, count, count),
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .semantics { contentDescription = selectionA11yLabel },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {},
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canRestore) {
                    val restoreLabel = stringResource(R.string.restore)
                    if (useIconOnlyActions) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                            tooltip = { PlainTooltip { Text(restoreLabel) } },
                            state = rememberTooltipState(),
                        ) {
                            FilledIconButton(
                                onClick = { onIntent(CleanerIntent.RequestAction(PackageAction.RESTORE)) },
                                enabled = enabled,
                                shapes = IconButtonDefaults.shapes(),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = colorScheme.primary,
                                    contentColor = colorScheme.onPrimary,
                                ),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_restore),
                                    contentDescription = restoreLabel,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = { onIntent(CleanerIntent.RequestAction(PackageAction.RESTORE)) },
                            enabled = enabled,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = buttonContentPadding,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colorScheme.primary,
                                contentColor = colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_restore),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = restoreLabel,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                if (canUninstall) {
                    val uninstallLabel = stringResource(R.string.uninstall)
                    if (useIconOnlyActions) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                            tooltip = { PlainTooltip { Text(uninstallLabel) } },
                            state = rememberTooltipState(),
                        ) {
                            FilledIconButton(
                                onClick = { onIntent(CleanerIntent.RequestAction(PackageAction.UNINSTALL)) },
                                enabled = enabled,
                                shapes = IconButtonDefaults.shapes(),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = colorScheme.error,
                                    contentColor = colorScheme.onError,
                                ),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = uninstallLabel,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = { onIntent(CleanerIntent.RequestAction(PackageAction.UNINSTALL)) },
                            enabled = enabled,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = buttonContentPadding,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colorScheme.error,
                                contentColor = colorScheme.onError,
                            ),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = uninstallLabel,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
