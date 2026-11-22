@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.cleaner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.Carrier
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.data.PackageFamily
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerIntent
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState
import com.eynnzerr.telemetrycleaner.ui.components.CleanerModalSheet
import com.eynnzerr.telemetrycleaner.ui.components.SegmentedListDefaults
import com.eynnzerr.telemetrycleaner.ui.components.label

/** Displays the cleaner's immutable state with Material 3 Expressive cards, segmented lists, and a floating toolbar. */
@Composable
fun CleanerScreen(
    state: CleanerState,
    onIntent: (CleanerIntent) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    customPackageNames: Set<String>,
    onRemoveCustomPackage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val hasSelectionActions = PackageAction.entries.any { state.targetsFor(it).isNotEmpty() }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CleanerTopBar(
                activity = state.activity,
                onRefresh = { onIntent(CleanerIntent.Refresh) },
                onOpenSettings = onOpenSettings,
                onOpenSearch = onOpenSearch,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { insets ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = insets.calculateTopPadding()),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = (if (hasSelectionActions) 112.dp else 24.dp) + insets.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "summary") { SummaryCard(state, onIntent) }
                state.error?.let { error ->
                    item(key = "error") {
                        CleanerErrorCard(error)
                    }
                }
                state.snapshot?.let { snapshot ->
                    item(key = "carrier_heading") {
                        CarrierSectionHeading(state, onIntent)
                    }

                    val carrierData = Carrier.entries.map { carrier ->
                        val entries = snapshot.packages.filter { carrier in it.entry.carriers }
                        val (visible, hidden) = entries.partition { it.hasVisibleRecord() }
                        val unresolved = hidden.filter { result ->
                            state.selectedUserIds.any { result.hasUnresolvedStatus(it) }
                        }
                        Triple(carrier, visible, entries to unresolved)
                    }
                    val (activeCarriers, emptyCarriers) = carrierData.partition { (_, visible, pair) ->
                        visible.isNotEmpty() || pair.second.isNotEmpty()
                    }

                    activeCarriers.forEach { (carrier, visible, pair) ->
                        item(key = "carrier_${carrier.name}") {
                            PackageCard(
                                title = carrier.label(),
                                packages = visible,
                                snapshot = snapshot,
                                state = state,
                                onIntent = onIntent,
                                issues = pair.second,
                                watermark = carrier,
                            )
                        }
                    }

                    if (emptyCarriers.isNotEmpty()) {
                        item(key = "carrier_empty_group") {
                            Column(verticalArrangement = Arrangement.spacedBy(SegmentedListDefaults.Gap)) {
                                emptyCarriers.forEachIndexed { index, (carrier, _, pair) ->
                                    val entries = pair.first
                                    val statusLabel = when {
                                        state.selectedUserIds.isEmpty() -> R.string.scope_empty
                                        entries.isNotEmpty() && entries.all { result ->
                                            state.selectedUserIds.all { result.users[it] == PackageState.NOT_FOUND }
                                        } -> R.string.status_known_components_not_found
                                        else -> R.string.status_known_components_not_found
                                    }
                                    EmptyCarrierSegmentedRow(
                                        carrier = carrier,
                                        statusLabel = statusLabel,
                                        index = index,
                                        count = emptyCarriers.size,
                                    )
                                }
                            }
                        }
                    }

                    val manufacturerPackages = snapshot.packages
                        .filter { it.entry.carriers.isEmpty() && it.entry.packageName !in customPackageNames }
                        .associateBy { it.entry.packageName }
                    PackageDirectory.groups.forEach { (family, entries) ->
                        val (visible, hidden) = entries.mapNotNull { manufacturerPackages[it.packageName] }
                            .partition { it.hasVisibleRecord() }
                        val issues = hidden.filter { result ->
                            state.selectedUserIds.any { result.hasUnresolvedStatus(it) }
                        }
                        if (visible.isEmpty() && issues.isEmpty()) return@forEach

                        item(key = "manufacturer_${family.name}") {
                            PackageCard(
                                title = when (family) {
                                    PackageFamily.COLOR_OS -> R.string.manufacturer_oppo
                                    PackageFamily.XIAOMI -> R.string.manufacturer_xiaomi
                                    else -> family.label()
                                },
                                packages = visible,
                                snapshot = snapshot,
                                state = state,
                                onIntent = onIntent,
                                issues = issues,
                            )
                        }
                    }

                    val custom = snapshot.packages.filter { it.entry.packageName in customPackageNames }
                    if (custom.isNotEmpty()) {
                        item(key = "custom_heading") {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.section_custom_packages),
                                modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                                style = MaterialTheme.typography.titleLargeEmphasized,
                            )
                        }
                        item(key = "custom_group") {
                            Column(verticalArrangement = Arrangement.spacedBy(SegmentedListDefaults.Gap)) {
                                custom.forEachIndexed { index, result ->
                                    CustomPackageSegmentedCard(
                                        result = result,
                                        snapshot = snapshot,
                                        state = state,
                                        index = index,
                                        count = custom.size,
                                        onIntent = onIntent,
                                        onRemove = onRemoveCustomPackage,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AnimatedSelectionBar(
                state = state,
                onIntent = onIntent,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    state.panel?.let { panel ->
        CleanerModalSheet(onDismiss = { onIntent(CleanerIntent.DismissPanel) }) {
            CleanerSheetContent(panel, state, onIntent)
        }
    }
}
