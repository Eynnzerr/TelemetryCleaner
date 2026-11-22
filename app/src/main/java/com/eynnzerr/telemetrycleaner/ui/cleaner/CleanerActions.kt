@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.cleaner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerActivity
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerIntent
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState
import com.eynnzerr.telemetrycleaner.ui.components.ExpressiveButton

/** Provides settings, refresh and package search from the cleaner. */
@Composable
internal fun CleanerTopBar(
    activity: CleanerActivity,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.settings),
                )
            }
        },
        actions = {
            IconButton(onClick = onRefresh, enabled = activity == CleanerActivity.IDLE) {
                Icon(
                    painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.refresh),
                )
            }
            IconButton(onClick = onOpenSearch) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.package_search_title))
            }
        },
    )
}

/** Slides the batch bar in from the bottom and retains its last visible content through the exit. */
@Composable
internal fun AnimatedSelectionBar(state: CleanerState, onIntent: (CleanerIntent) -> Unit) {
    val visible = PackageAction.entries.any { state.targetsFor(it).isNotEmpty() }
    val motion = MaterialTheme.motionScheme
    // Scaffold bottom-aligns the animated bounds. The full bar moves with their top edge.
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = motion.defaultSpatialSpec(),
            expandFrom = Alignment.Top,
            clip = false,
        ),
        exit = shrinkVertically(
            animationSpec = motion.fastEffectsSpec(),
            shrinkTowards = Alignment.Top,
            clip = false,
        ),
        label = "Batch selection bar",
    ) {
        val lastVisibleState = remember { mutableStateOf(state) }
        if (visible) {
            SideEffect { lastVisibleState.value = state }
        }
        SelectionBar(
            state = if (visible) state else lastVisibleState.value,
            onIntent = onIntent,
            enabled = visible,
        )
    }
}

@Composable
private fun SelectionBar(
    state: CleanerState,
    onIntent: (CleanerIntent) -> Unit,
    enabled: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Box(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 580.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val count = state.selectedPackageNames.size
                Text(
                    pluralStringResource(R.plurals.selected_components, count, count),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.targetsFor(PackageAction.RESTORE).isNotEmpty()) {
                        ExpressiveButton(R.string.batch_restore, Modifier.weight(1f), enabled = enabled) {
                            onIntent(CleanerIntent.RequestAction(PackageAction.RESTORE))
                        }
                    }
                    if (state.targetsFor(PackageAction.UNINSTALL).isNotEmpty()) {
                        ExpressiveButton(R.string.batch_uninstall, Modifier.weight(1f), enabled = enabled) {
                            onIntent(CleanerIntent.RequestAction(PackageAction.UNINSTALL))
                        }
                    }
                }
            }
        }
    }
}
