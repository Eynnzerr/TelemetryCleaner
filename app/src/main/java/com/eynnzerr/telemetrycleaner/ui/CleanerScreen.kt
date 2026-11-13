@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui

import android.content.ClipData
import android.content.Intent
import android.icu.text.ListFormatter
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.Carrier
import com.eynnzerr.telemetrycleaner.data.ChangeTarget
import com.eynnzerr.telemetrycleaner.data.DetectedPackage
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.data.PackageScanIssue
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.data.ShizukuStatus
import com.eynnzerr.telemetrycleaner.data.permits
import com.eynnzerr.telemetrycleaner.feature.CleanerActivity
import com.eynnzerr.telemetrycleaner.feature.CleanerError
import com.eynnzerr.telemetrycleaner.feature.CleanerIntent
import com.eynnzerr.telemetrycleaner.feature.CleanerPanel
import com.eynnzerr.telemetrycleaner.feature.CleanerState
import kotlinx.coroutines.launch

/** Displays the cleaner's immutable state and sends interactions back to the store. */
@Composable
fun CleanerScreen(
    state: CleanerState,
    onIntent: (CleanerIntent) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CleanerTopBar(
                state.activity,
                onRefresh = { onIntent(CleanerIntent.Refresh) },
                onOpenSettings = onOpenSettings,
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
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.large,
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
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                }
                state.snapshot?.let { snapshot ->
                    item(key = "carrier_heading") {
                        CarrierSectionHeading(state, onIntent)
                    }
                    items(Carrier.entries.size, key = { Carrier.entries[it].name }) { index ->
                        CarrierGroup(Carrier.entries[index], snapshot, state, onIntent)
                    }
                    val (candidates, unreadCandidates) = snapshot.packages
                        .filter { it.entry.carriers.isEmpty() }
                        .partition { it.hasVisibleRecord() }
                    val candidateIssues = unreadCandidates.filter { result ->
                        state.selectedUserIds.any { result.hasUnresolvedStatus(it) }
                    }
                    if (candidates.isNotEmpty() || candidateIssues.isNotEmpty()) {
                        item(key = "unverified_components") {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Column {
                                    Text(
                                        stringResource(R.string.section_candidate_components),
                                        style = MaterialTheme.typography.titleMediumEmphasized,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                            .semantics { heading() },
                                    )
                                    PackageScanIssues(candidateIssues, state.selectedUserIds)
                                    candidates.forEach { ComponentRow(it, snapshot, state, onIntent) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    state.panel?.let { panel ->
        CleanerSheet(panel, state, onIntent)
    }
}

/** Displays the pinned app bar with settings and a refresh action disabled during work. */
@Composable
internal fun CleanerTopBar(
    activity: CleanerActivity,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
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
        },
    )
}

@Composable
private fun SummaryCard(state: CleanerState, onIntent: (CleanerIntent) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val snapshot = state.snapshot
    val busy = state.activity != CleanerActivity.IDLE || state.connection == ShizukuStatus.CONNECTING
    val hasIncompleteResults = snapshot?.packages?.any { result ->
        state.selectedUserIds.any { result.hasUnresolvedStatus(it) }
    } == true
    val summaryTitle = when {
        state.activity == CleanerActivity.CHANGING -> stringResource(R.string.changing)
        state.activity == CleanerActivity.SCANNING -> stringResource(R.string.scanning)
        state.connection == ShizukuStatus.READY && snapshot != null && state.selectedUserIds.isEmpty() -> {
            stringResource(R.string.scope_empty)
        }
        state.connection == ShizukuStatus.READY && snapshot != null -> {
            val count = snapshot.packages.count { result ->
                result.entry.carriers.isNotEmpty() && state.selectedUserIds.any { userId ->
                    result.users[userId] == PackageState.INSTALLED ||
                        result.users[userId] == PackageState.DISABLED
                }
            }
            if (count == 0 && hasIncompleteResults) stringResource(R.string.summary_scan_incomplete)
            else pluralStringResource(R.plurals.installed_components, count, count)
        }
        state.connection == ShizukuStatus.READY && state.error != null ->
            stringResource(R.string.scan_unavailable)
        else -> stringResource(
            when (state.connection) {
                ShizukuStatus.MISSING -> R.string.shizuku_missing
                ShizukuStatus.STOPPED -> R.string.shizuku_stopped
                ShizukuStatus.PERMISSION_REQUIRED -> R.string.shizuku_permission
                ShizukuStatus.CONNECTING -> R.string.scan_preparing
                ShizukuStatus.ERROR -> R.string.scan_unavailable
                ShizukuStatus.READY -> R.string.scan_ready
            },
        )
    }
    val subtitle = when {
        state.activity != CleanerActivity.IDLE -> null
        state.connection == ShizukuStatus.MISSING -> R.string.setup_explanation
        state.connection == ShizukuStatus.STOPPED -> R.string.start_explanation
        state.connection == ShizukuStatus.PERMISSION_REQUIRED -> R.string.permission_explanation
        state.connection == ShizukuStatus.CONNECTING -> R.string.waiting_for_shizuku
        snapshot == null && state.connection == ShizukuStatus.READY && state.error == null ->
            R.string.scan_ready_description
        state.connection == ShizukuStatus.READY && hasIncompleteResults -> R.string.summary_scan_incomplete_description
        else -> null
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = colors.primaryContainer,
    ) {
        Column(
            Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            when (state.connection) {
                                ShizukuStatus.MISSING -> R.string.shizuku_not_installed
                                ShizukuStatus.STOPPED -> R.string.shizuku_disconnected
                                ShizukuStatus.PERMISSION_REQUIRED -> R.string.shizuku_unauthorized
                                ShizukuStatus.CONNECTING -> R.string.shizuku_connecting
                                ShizukuStatus.READY -> R.string.shizuku_connected
                                ShizukuStatus.ERROR -> R.string.shizuku_error
                            },
                        ),
                        style = MaterialTheme.typography.labelLargeEmphasized,
                    )
                    Text(
                        summaryTitle,
                        style = MaterialTheme.typography.headlineLargeEmphasized,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                if (busy) {
                    LoadingIndicator(
                        modifier = Modifier.size(48.dp).align(Alignment.CenterVertically),
                        color = colors.onPrimaryContainer,
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp).align(Alignment.CenterVertically),
                        tint = colors.onPrimaryContainer,
                    )
                }
            }
            if (subtitle != null) {
                Text(stringResource(subtitle), style = MaterialTheme.typography.bodyMedium)
            }
            if (!busy) {
                when (state.connection) {
                    ShizukuStatus.MISSING -> ExpressiveButton(R.string.get_shizuku) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_SETUP_URL)))
                    }
                    ShizukuStatus.STOPPED -> ExpressiveButton(R.string.open_shizuku) {
                        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                        context.startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_SETUP_URL)))
                    }
                    ShizukuStatus.PERMISSION_REQUIRED -> ExpressiveButton(R.string.authorize) {
                        onIntent(CleanerIntent.RequestPermission)
                    }
                    ShizukuStatus.ERROR -> ExpressiveButton(R.string.try_again) {
                        onIntent(CleanerIntent.Refresh)
                    }
                    ShizukuStatus.READY -> if (snapshot == null && state.error == null) {
                        ExpressiveButton(R.string.scan) { onIntent(CleanerIntent.Refresh) }
                    }
                    ShizukuStatus.CONNECTING -> Unit
                }
            }
        }
    }
}

/** Places the selection toggle opposite the carrier section title. */
@Composable
private fun CarrierSectionHeading(state: CleanerState, onIntent: (CleanerIntent) -> Unit) {
    val eligible = state.selectablePackageNames()
    val allSelected = eligible.isNotEmpty() && state.selectedPackageNames.containsAll(eligible)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (state.connection == ShizukuStatus.READY && state.error == null) {
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

@Composable
private fun CarrierGroup(
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
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainerLow,
    ) {
        Box {
            Box(Modifier.matchParentSize()) {
                CarrierWatermark(carrier, Modifier.align(Alignment.TopEnd))
            }
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(carrier.label()),
                        modifier = Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    if (headerLabel != null) {
                        Text(
                            stringResource(headerLabel),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                PackageScanIssues(hidden, state.selectedUserIds)
                visible.forEach { result ->
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
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
            Text(
                packageNames.distinct().joinToString(separator = " · "),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        status == PackageState.UNKNOWN ||
            (userId !in state.selectedUserIds && status.permits(PackageAction.UNINSTALL))
    }
    val statusLines = selectedSpaces.map { space ->
        val status = stringResource(result.installationStatusLabel(space.id))
        val spaceStatus = if (selectedSpaces.size == 1) status else {
            stringResource(R.string.status_in_space, space.displayName(), status)
        }
        if (losesApkOnUninstall && result.users[space.id].permits(PackageAction.UNINSTALL)) {
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
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            if (state.targetsFor(PackageAction.RESTORE, packageName).isNotEmpty()) {
                FilledTonalButton(
                    onClick = { onIntent(CleanerIntent.RequestAction(PackageAction.RESTORE, packageName)) },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.restore))
                }
            }
            if (state.targetsFor(PackageAction.UNINSTALL, packageName).isNotEmpty()) {
                FilledTonalButton(
                    onClick = { onIntent(CleanerIntent.RequestAction(PackageAction.UNINSTALL, packageName)) },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.uninstall))
                }
            }
        }
    }
}

/** Animates the batch bar and retains its last visible content through the exit. */
@Composable
private fun AnimatedSelectionBar(state: CleanerState, onIntent: (CleanerIntent) -> Unit) {
    val visible = PackageAction.entries.any { state.targetsFor(it).isNotEmpty() }
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = motion.defaultSpatialSpec(), expandFrom = Alignment.Bottom) +
            fadeIn(animationSpec = motion.fastEffectsSpec()),
        exit = shrinkVertically(animationSpec = motion.fastSpatialSpec(), shrinkTowards = Alignment.Bottom) +
            fadeOut(animationSpec = motion.fastEffectsSpec()),
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

@Composable
private fun CleanerSheet(
    panel: CleanerPanel,
    state: CleanerState,
    onIntent: (CleanerIntent) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { onIntent(CleanerIntent.DismissPanel) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        CleanerSheetContent(panel, state, onIntent)
    }
}

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
    val contentSize = if (panel is CleanerPanel.Details) {
        Modifier.fillMaxHeight(0.82f)
    } else {
        Modifier.verticalScroll(rememberScrollState())
    }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .then(contentSize)
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (panel) {
            is CleanerPanel.Details -> {
                SheetTitle(R.string.details_title)
                snapshot?.let { scan ->
                    scan.packages.firstOrNull { it.entry.packageName == panel.packageName }?.let { result ->
                        key(panel.packageName) {
                            DetailPages(result, scan, showPermissionsInitially, Modifier.weight(1f))
                        }
                    }
                }
                ExpressiveButton(R.string.done, Modifier.fillMaxWidth(), onClick = dismiss)
            }
            is CleanerPanel.Confirmation -> {
                val uninstall = panel.action == PackageAction.UNINSTALL
                SheetTitle(if (uninstall) R.string.confirm_uninstall else R.string.confirm_restore)
                Text(
                    stringResource(
                        if (uninstall) R.string.uninstall_description else R.string.restore_description,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (uninstall) UninstallWarning(panel.targets)
                panel.targets.forEach { target -> TargetRow(target, snapshot) }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
                    ExpressiveButton(
                        if (uninstall) R.string.uninstall else R.string.restore,
                        enabled = state.readyForActions,
                        destructive = uninstall,
                    ) {
                        onIntent(CleanerIntent.ConfirmAction)
                    }
                }
            }
            is CleanerPanel.Results -> {
                SheetTitle(R.string.operation_results)
                val denialLogs = panel.outcomes.mapNotNull { it.denialLog }
                val canCopyDenialLogs = panel.action == PackageAction.UNINSTALL && denialLogs.isNotEmpty()
                if (canCopyDenialLogs) {
                    Text(
                        stringResource(R.string.uninstall_denial_feedback_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                panel.outcomes.forEach { outcome ->
                    TargetRow(
                        outcome.target,
                        snapshot,
                        status = when {
                            outcome.success && panel.action == PackageAction.UNINSTALL -> {
                                stringResource(R.string.uninstalled_result)
                            }
                            outcome.success -> stringResource(R.string.restored_result)
                            else -> outcome.message ?: stringResource(R.string.unverified_result)
                        },
                    )
                }
                if (canCopyDenialLogs) {
                    UninstallResultActions(denialLogs.joinToString("\n\n"), dismiss)
                } else {
                    ExpressiveButton(R.string.done, Modifier.fillMaxWidth(), onClick = dismiss)
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

/** Warns about the manufacturer groups represented by manual-only uninstall targets. */
@Composable
private fun UninstallWarning(targets: List<ChangeTarget>) {
    val packageNames = targets.mapTo(mutableSetOf(), ChangeTarget::packageName) - PackageDirectory.autoCleanupNames
    val manufacturers = PackageDirectory.groups
        .filterValues { entries -> entries.any { it.packageName in packageNames } }
        .keys
        .map { stringResource(it.label()) }
    if (manufacturers.isEmpty()) return

    val names = ListFormatter.getInstance(LocalConfiguration.current.locales[0]).format(manufacturers)
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = stringResource(R.string.uninstall_untested_warning, names),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

/** Keeps the details tabs and dismissal action visible while their pages scroll. */
@Composable
private fun DetailPages(
    result: DetectedPackage,
    snapshot: ScanSnapshot,
    showPermissionsInitially: Boolean,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(initialPage = if (showPermissionsInitially) 1 else 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    Column(modifier) {
        SecondaryTabRow(selectedTabIndex = pager.currentPage, containerColor = Color.Transparent) {
            listOf(R.string.details_overview_tab, R.string.details_permissions_tab).forEachIndexed { index, label ->
                Tab(
                    selected = pager.currentPage == index,
                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    text = { Text(stringResource(label)) },
                )
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            if (page == 0) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DetailFields(result, snapshot.users)
                }
            } else {
                PackagePermissionsPage(result, snapshot.users, snapshot.currentUserId)
            }
        }
    }
}

/** Localizes ColorOS's built-in clone label while preserving custom user names. */
@Composable
internal fun AndroidUser.displayName(): String =
    when {
        name == "system_clone" -> stringResource(R.string.system_clone)
        id == 0 && (name == "机主" || name == "Owner") -> stringResource(R.string.main_space)
        else -> name
    }

@Composable
private fun DetailFields(result: DetectedPackage, users: List<AndroidUser>) {
    Text(result.entry.displayName, style = MaterialTheme.typography.titleLargeEmphasized)
    InfoField(R.string.details_package, result.entry.packageName)
    result.versionName?.let { InfoField(R.string.details_version, it) }
    result.systemPath?.let { InfoField(R.string.details_system_path, it) }
    result.entry.notes?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
    val names = result.entry.carriers.map { stringResource(it.label()) }
    InfoField(R.string.details_carriers, names.joinToString().ifEmpty { stringResource(R.string.status_unknown) })
    Text(stringResource(R.string.details_installation), style = MaterialTheme.typography.titleSmall)
    users.forEach { user ->
        val status = stringResource(result.installationStatusLabel(user.id))
        Text(stringResource(R.string.status_in_space, user.displayName(), status), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoField(@StringRes label: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TargetRow(target: ChangeTarget, snapshot: ScanSnapshot?, status: String? = null) {
    val result = snapshot?.packages?.firstOrNull { it.entry.packageName == target.packageName }
    val space = snapshot?.users?.firstOrNull { it.id == target.userId }?.displayName()
        ?: stringResource(R.string.space_id, target.userId)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(result?.entry?.displayName ?: target.packageName, style = MaterialTheme.typography.titleSmall)
            if (result != null) {
                Text(target.packageName, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (status == null) space else stringResource(R.string.status_in_space, space, status),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SheetTitle(@StringRes label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.headlineSmallEmphasized,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun ExpressiveButton(
    @StringRes label: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier.heightIn(min = ButtonDefaults.MediumContainerHeight),
        enabled = enabled,
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = colorScheme.error,
                contentColor = colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLargeEmphasized)
    }
}

/** Returns the localized carrier name shared by scan results and the package directory. */
@StringRes
internal fun Carrier.label(): Int = when (this) {
    Carrier.UNICOM -> R.string.carrier_unicom
    Carrier.TELECOM -> R.string.carrier_telecom
    Carrier.MOBILE -> R.string.carrier_mobile
}

/** Identifies components with enough package data to display an interactive row. */
private fun DetectedPackage.hasVisibleRecord(): Boolean = systemPath != null || users.values.any {
    it == PackageState.INSTALLED || it == PackageState.DISABLED || it == PackageState.RESTORABLE
}

/** Indicates that the package state could not be confirmed in the given space. */
private fun DetectedPackage.hasUnresolvedStatus(userId: Int): Boolean {
    val state = users[userId]
    return state == null || state == PackageState.UNKNOWN
}

/** Explains an unresolved installation state using the reason reported for that space. */
@StringRes
private fun DetectedPackage.installationStatusLabel(userId: Int): Int = when (users[userId]) {
    PackageState.INSTALLED -> R.string.status_installed
    PackageState.DISABLED -> R.string.status_disabled
    PackageState.RESTORABLE -> R.string.status_restorable
    PackageState.NOT_FOUND -> R.string.status_not_found
    PackageState.UNKNOWN, null -> when (scanIssues[userId]) {
        PackageScanIssue.QUERY_FAILED -> R.string.scan_package_query_failed
        PackageScanIssue.PERMISSION_DENIED -> R.string.scan_package_permission_denied
        PackageScanIssue.UNRECOGNIZED_RESPONSE -> R.string.scan_package_response_invalid
        PackageScanIssue.USER_STATE_MISSING -> R.string.scan_package_space_state_missing
        null -> R.string.status_installation_recheck
    }
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_SETUP_URL = "https://shizuku.rikka.app/guide/setup/"
