@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.eynnzerr.telemetrycleaner.ui.packages

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TonalToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.DetectedPackage
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.ui.cleaner.statusTone
import com.eynnzerr.telemetrycleaner.ui.components.CleanerSheetLayout
import com.eynnzerr.telemetrycleaner.ui.components.PackageNameText
import com.eynnzerr.telemetrycleaner.ui.components.SegmentedListDefaults
import com.eynnzerr.telemetrycleaner.ui.components.SheetHeader
import com.eynnzerr.telemetrycleaner.ui.components.StatusBadge
import com.eynnzerr.telemetrycleaner.ui.components.StatusTone
import com.eynnzerr.telemetrycleaner.ui.components.displayName
import com.eynnzerr.telemetrycleaner.ui.components.installationStatusLabel
import com.eynnzerr.telemetrycleaner.ui.components.label
import com.eynnzerr.telemetrycleaner.ui.components.segmentedItemShape
import kotlinx.coroutines.launch

/**
 * Shows the package overview and permissions in a full-viewport sheet with a lightweight top close button
 * instead of a redundant bottom primary button.
 */
@Composable
internal fun PackageDetailsSheetContent(
    packageName: String,
    snapshot: ScanSnapshot?,
    onDismiss: () -> Unit,
    showPermissionsInitially: Boolean = false,
    placeholder: @Composable () -> Unit = {},
) {
    CleanerSheetLayout(Modifier.fillMaxHeight(0.84f)) {
        SheetHeader(R.string.details_title, onClose = onDismiss)
        val result = snapshot?.packages?.firstOrNull { it.entry.packageName == packageName }
        if (result != null) {
            key(packageName) {
                DetailPages(result, snapshot, showPermissionsInitially, Modifier.weight(1f))
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                placeholder()
            }
        }
    }
}

/** Switches between overview and permissions using an Expressive connected button group. */
@Composable
private fun DetailPages(
    result: DetectedPackage,
    snapshot: ScanSnapshot,
    showPermissionsInitially: Boolean,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(initialPage = if (showPermissionsInitially) 1 else 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            TonalToggleButton(
                checked = pager.currentPage == 0,
                onCheckedChange = { if (it) scope.launch { pager.animateScrollToPage(0) } },
                modifier = Modifier.weight(1f),
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            ) {
                Text(stringResource(R.string.details_overview_tab), maxLines = 1)
            }
            TonalToggleButton(
                checked = pager.currentPage == 1,
                onCheckedChange = { if (it) scope.launch { pager.animateScrollToPage(1) } },
                modifier = Modifier.weight(1f),
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            ) {
                Text(stringResource(R.string.details_permissions_tab), maxLines = 1)
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            if (page == 0) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DetailFields(result, snapshot.users)
                }
            } else {
                PackagePermissionsPage(result, snapshot.users, snapshot.currentUserId)
            }
        }
    }
}

@Composable
private fun DetailFields(result: DetectedPackage, users: List<AndroidUser>) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(R.string.copy_package_name)

    val metadataRows = buildList<Pair<Int, String>> {
        result.versionName?.let { add(R.string.details_version to it) }
        result.systemPath?.let { add(R.string.details_system_path to it) }
    }
    val hasCarriers = result.entry.carriers.isNotEmpty()
    val totalInfoCards = 1 + metadataRows.size + if (hasCarriers) 1 else 0

    Column(verticalArrangement = Arrangement.spacedBy(SegmentedListDefaults.Gap)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = segmentedItemShape(0, totalInfoCards),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = result.entry.displayName,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                    PackageNameText(result.entry.packageName)
                }
                FilledTonalIconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText(copyLabel, result.entry.packageName)),
                            )
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, result.entry.packageName, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = copyLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        metadataRows.forEachIndexed { offset, (label, value) ->
            InfoFieldCard(
                label = label,
                value = value,
                shape = segmentedItemShape(1 + offset, totalInfoCards),
            )
        }

        if (hasCarriers) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = segmentedItemShape(totalInfoCards - 1, totalInfoCards),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.details_carriers),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        result.entry.carriers.forEach { carrier ->
                            StatusBadge(stringResource(carrier.label()), StatusTone.PRIMARY)
                        }
                    }
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.details_installation),
            modifier = Modifier.padding(start = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(SegmentedListDefaults.Gap)) {
            users.forEachIndexed { index, user ->
                val state = result.users[user.id]
                val status = stringResource(result.installationStatusLabel(user.id))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = segmentedItemShape(index, users.size),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = user.displayName(),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        StatusBadge(text = status, tone = state.statusTone())
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoFieldCard(@StringRes label: Int, value: String, shape: Shape) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
