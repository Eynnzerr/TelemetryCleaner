@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.packages

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.DetectedPackage
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.ui.components.CleanerSheetLayout
import com.eynnzerr.telemetrycleaner.ui.components.ExpressiveButton
import com.eynnzerr.telemetrycleaner.ui.components.SheetTitle
import com.eynnzerr.telemetrycleaner.ui.components.displayName
import com.eynnzerr.telemetrycleaner.ui.components.installationStatusLabel
import com.eynnzerr.telemetrycleaner.ui.components.label
import kotlinx.coroutines.launch

/** Shows the package overview and permissions, or a placeholder while details are unavailable. */
@Composable
internal fun PackageDetailsSheetContent(
    packageName: String,
    snapshot: ScanSnapshot?,
    onDismiss: () -> Unit,
    showPermissionsInitially: Boolean = false,
    placeholder: @Composable () -> Unit = {},
) {
    CleanerSheetLayout(Modifier.fillMaxHeight(0.82f)) {
        SheetTitle(R.string.details_title)
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
        ExpressiveButton(R.string.done, Modifier.fillMaxWidth(), onClick = onDismiss)
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

@Composable
private fun DetailFields(result: DetectedPackage, users: List<AndroidUser>) {
    Text(result.entry.displayName, style = MaterialTheme.typography.titleLargeEmphasized)
    InfoField(R.string.details_package, result.entry.packageName)
    result.versionName?.let { InfoField(R.string.details_version, it) }
    result.systemPath?.let { InfoField(R.string.details_system_path, it) }
    if (result.entry.carriers.isNotEmpty()) {
        val names = result.entry.carriers.map { stringResource(it.label()) }
        InfoField(R.string.details_carriers, names.joinToString())
    }
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
