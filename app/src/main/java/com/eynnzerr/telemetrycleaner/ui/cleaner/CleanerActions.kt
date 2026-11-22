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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerActivity
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerIntent
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState

/** Provides Expressive settings, refresh, and package search actions with scroll-aware tonal elevation. */
@Composable
internal fun CleanerTopBar(
    activity: CleanerActivity,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
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
            IconButton(
                onClick = onOpenSearch,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    painterResource(R.drawable.ic_search),
                    contentDescription = stringResource(R.string.package_search_title),
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
            .padding(horizontal = 16.dp, vertical = 16.dp),
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
    val count = state.selectedPackageNames.size
    val canRestore = state.targetsFor(PackageAction.RESTORE).isNotEmpty()
    val canUninstall = state.targetsFor(PackageAction.UNINSTALL).isNotEmpty()

    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = colorScheme.surfaceContainerHighest,
            toolbarContentColor = colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        leadingContent = {
            Surface(
                modifier = Modifier.padding(end = 8.dp),
                shape = RoundedCornerShape(50),
                color = colorScheme.secondaryContainer,
                contentColor = colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = pluralStringResource(R.plurals.selected_components, count, count),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    maxLines = 1,
                )
            }
        },
        trailingContent = {
            Spacer(Modifier.width(2.dp))
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canRestore) {
                FilledTonalButton(
                    onClick = { onIntent(CleanerIntent.RequestAction(PackageAction.RESTORE)) },
                    enabled = enabled,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_restore),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.restore), maxLines = 1)
                }
            }
            if (canUninstall) {
                Button(
                    onClick = { onIntent(CleanerIntent.RequestAction(PackageAction.UNINSTALL)) },
                    enabled = enabled,
                    shapes = ButtonDefaults.shapes(),
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
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.uninstall), maxLines = 1)
                }
            }
        }
    }
}
