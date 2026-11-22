@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.cleaner

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.Carrier
import com.eynnzerr.telemetrycleaner.data.DetectedPackage
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerIntent
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState
import com.eynnzerr.telemetrycleaner.ui.components.PackageNameText
import com.eynnzerr.telemetrycleaner.ui.components.displayName
import com.eynnzerr.telemetrycleaner.ui.components.installationStatusLabel
import com.eynnzerr.telemetrycleaner.ui.components.label

/** Places the selection toggle opposite the carrier section title. */
@Composable
internal fun CarrierSectionHeading(state: CleanerState, onIntent: (CleanerIntent) -> Unit) {
    val eligible = state.selectablePackageNames()
    val allSelected = eligible.isNotEmpty() && state.selectedPackageNames.containsAll(eligible)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (state.connection.status == PrivilegeStatus.READY && state.error == null) {
                    R.string.section_components
                } else {
                    R.string.section_previous_results
                },
            ),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        TextButton(
            onClick = { onIntent(CleanerIntent.ToggleAllPackages) },
            enabled = eligible.isNotEmpty() && state.panel == null,
        ) {
            Text(stringResource(if (allSelected) R.string.deselect_all else R.string.select_all))
        }
    }
}

/** Supplies carrier-specific status and artwork to the shared package card. */
@Composable
internal fun CarrierGroup(
    carrier: Carrier,
    snapshot: ScanSnapshot,
    state: CleanerState,
    onIntent: (CleanerIntent) -> Unit,
) {
    val entries = snapshot.packages.filter { carrier in it.entry.carriers }
    val (visible, hidden) = entries.partition { it.hasVisibleRecord() }
    val headerLabel = when {
        state.selectedUserIds.isEmpty() && visible.isEmpty() -> R.string.scope_empty
        state.selectedUserIds.isNotEmpty() && entries.isNotEmpty() && entries.all { result ->
            state.selectedUserIds.all { result.users[it] == PackageState.NOT_FOUND }
        } -> R.string.status_known_components_not_found
        else -> null
    }
    PackageCard(
        title = carrier.label(),
        packages = visible,
        snapshot = snapshot,
        state = state,
        onIntent = onIntent,
        statusLabel = headerLabel,
        issues = hidden,
        watermark = carrier,
    )
}

/** Renders the shared heading, scan issues and separated package rows on the home screen. */
@Composable
internal fun PackageCard(
    @StringRes title: Int?,
    packages: List<DetectedPackage>,
    snapshot: ScanSnapshot,
    state: CleanerState,
    onIntent: (CleanerIntent) -> Unit,
    contentModifier: Modifier = Modifier,
    @StringRes statusLabel: Int? = null,
    issues: List<DetectedPackage> = emptyList(),
    watermark: Carrier? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box {
            if (watermark != null) {
                Box(Modifier.matchParentSize()) {
                    CarrierWatermark(watermark, Modifier.align(Alignment.TopEnd))
                }
            }
            Column(contentModifier.fillMaxWidth()) {
                if (title != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(title),
                            modifier = Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                        if (statusLabel != null) {
                            Text(stringResource(statusLabel), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                PackageScanIssues(issues, state.selectedUserIds)
                packages.forEachIndexed { index, result ->
                    if (title != null || index > 0) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                    ComponentRow(result, snapshot, state, onIntent)
                }
            }
        }
    }
}

/** Explains why packages without a visible record could not be checked. */
@Composable
private fun PackageScanIssues(packages: List<DetectedPackage>, userIds: Set<Int>) {
    val packagesByIssue = packages.flatMap { result ->
        userIds.filter { result.hasUnresolvedStatus(it) }
            .map { result.installationStatusLabel(it) to result.entry.packageName }
    }.groupBy({ it.first }, { it.second })
    packagesByIssue.forEach { (label, packageNames) ->
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            PackageNameText(packageNames.distinct().joinToString(separator = " · "))
        }
    }
}

/** Places the official carrier mark in the card background with a soft vertical fade. */
@Composable
private fun CarrierWatermark(carrier: Carrier, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(carrier.logo()),
        contentDescription = null,
        modifier = modifier
            .padding(top = 6.dp, end = 16.dp)
            .size(width = 96.dp, height = 72.dp)
            .graphicsLayer {
                alpha = 0.18f
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent)),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

@DrawableRes
private fun Carrier.logo(): Int = when (this) {
    Carrier.UNICOM -> R.drawable.carrier_logo_unicom
    Carrier.TELECOM -> R.drawable.carrier_logo_telecom
    Carrier.MOBILE -> R.drawable.carrier_logo_mobile
}

/** Adds the details and long-press removal actions to a user-added package card. */
@Composable
internal fun CustomPackageCard(
    result: DetectedPackage,
    snapshot: ScanSnapshot,
    state: CleanerState,
    onIntent: (CleanerIntent) -> Unit,
    onRemove: (String) -> Unit,
) {
    val name = result.entry.packageName
    val typeLabel = when (result.hasSystemCopy) {
        true -> R.string.package_type_system
        false -> R.string.package_type_user
        null -> null
    }
    val menuOpen = remember(name) { mutableStateOf(false) }
    val removeLabel = stringResource(R.string.remove_from_directory)
    Box {
        PackageCard(
            title = typeLabel,
            packages = listOf(result),
            snapshot = snapshot,
            state = state,
            onIntent = onIntent,
            contentModifier = Modifier.combinedClickable(
                onClick = { onIntent(CleanerIntent.OpenDetails(name)) },
                onLongClickLabel = removeLabel,
                onLongClick = { menuOpen.value = true },
            ),
        )
        DropdownMenu(expanded = menuOpen.value, onDismissRequest = { menuOpen.value = false }) {
            DropdownMenuItem(text = { Text(removeLabel) }, onClick = {
                menuOpen.value = false
                onRemove(name)
            })
        }
    }
}

@Composable
private fun ComponentRow(
    result: DetectedPackage,
    snapshot: ScanSnapshot,
    state: CleanerState,
    onIntent: (CleanerIntent) -> Unit,
) {
    val packageName = result.entry.packageName
    val selectedSpaces = snapshot.users.filter { it.id in state.selectedUserIds }
    val losesApkOnUninstall = result.hasSystemCopy == false && result.users.none { (userId, status) ->
        status == PackageState.UNKNOWN || status == PackageState.DISABLED ||
            (userId !in state.selectedUserIds && status == PackageState.INSTALLED)
    }
    val statusLines = selectedSpaces.map { space ->
        val status = stringResource(result.installationStatusLabel(space.id))
        val spaceStatus = if (selectedSpaces.size == 1) status else {
            stringResource(R.string.status_in_space, space.displayName(), status)
        }
        if (losesApkOnUninstall && result.users[space.id] == PackageState.INSTALLED) {
            stringResource(R.string.status_uninstall_not_restorable, spaceStatus)
        } else {
            spaceStatus
        }
    }
    val selectionLabel = stringResource(R.string.select_component, result.entry.displayName)
    Column(
        Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(
                checked = packageName in state.selectedPackageNames,
                enabled = state.canSelectPackage(packageName),
                onCheckedChange = { onIntent(CleanerIntent.TogglePackage(packageName)) },
                modifier = Modifier.semantics { contentDescription = selectionLabel },
            )
            Column(
                Modifier.weight(1f).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(result.entry.displayName, style = MaterialTheme.typography.titleMediumEmphasized)
                if (result.entry.displayName != packageName) {
                    PackageNameText(packageName)
                }
                statusLines.forEach { label ->
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onIntent(CleanerIntent.OpenDetails(packageName)) }) {
                Text(stringResource(R.string.view_details))
            }
            listOf(PackageAction.RESTORE to R.string.restore, PackageAction.UNINSTALL to R.string.uninstall)
                .forEach { (action, label) ->
                    key(action) {
                        if (state.targetsFor(action, packageName).isNotEmpty()) {
                            FilledTonalButton(
                                onClick = { onIntent(CleanerIntent.RequestAction(action, packageName)) },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(label))
                            }
                        }
                    }
                }
        }
    }
}

/** Indicates that the package state could not be confirmed in the given space. */
internal fun DetectedPackage.hasUnresolvedStatus(userId: Int): Boolean {
    val state = users[userId]
    return state == null || state == PackageState.UNKNOWN
}
