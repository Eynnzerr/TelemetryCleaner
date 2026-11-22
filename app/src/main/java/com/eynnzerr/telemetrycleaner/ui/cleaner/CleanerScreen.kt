@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.cleaner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.Carrier
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.data.PackageFamily
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerIntent
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState
import com.eynnzerr.telemetrycleaner.ui.components.CleanerModalSheet
import com.eynnzerr.telemetrycleaner.ui.components.label

/** Displays the cleaner's immutable state and sends interactions back to the store. */
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
    Scaffold(
        modifier = modifier,
        topBar = {
            CleanerTopBar(
                state.activity,
                onRefresh = { onIntent(CleanerIntent.Refresh) },
                onOpenSettings = onOpenSettings,
                onOpenSearch = onOpenSearch,
            )
        },
        bottomBar = {
            AnimatedSelectionBar(state, onIntent)
        },
    ) { insets ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(insets),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
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
                    items(Carrier.entries.size, key = { Carrier.entries[it].name }) { index ->
                        CarrierGroup(Carrier.entries[index], snapshot, state, onIntent)
                    }
                    val manufacturerPackages = snapshot.packages
                        .filter { it.entry.carriers.isEmpty() }
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
                            Text(
                                stringResource(R.string.section_custom_packages),
                                modifier = Modifier.padding(start = 4.dp).semantics { heading() },
                                style = MaterialTheme.typography.titleLargeEmphasized,
                            )
                        }
                        items(custom, key = { it.entry.packageName }) { result ->
                            CustomPackageCard(
                                result = result,
                                snapshot = snapshot,
                                state = state,
                                onIntent = onIntent,
                                onRemove = onRemoveCustomPackage,
                            )
                        }
                    }
                }
            }
        }
    }

    state.panel?.let { panel ->
        CleanerModalSheet(onDismiss = { onIntent(CleanerIntent.DismissPanel) }) {
            CleanerSheetContent(panel, state, onIntent)
        }
    }
}
