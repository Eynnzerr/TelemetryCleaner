package com.eynnzerr.telemetrycleaner.ui

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.AutoCleanupCheck
import com.eynnzerr.telemetrycleaner.data.AutoCleanupCheckStatus
import com.eynnzerr.telemetrycleaner.data.AutoCleanupHistory
import com.eynnzerr.telemetrycleaner.data.AutoCleanupRemoval
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import java.util.Date

/** Displays the latest automatic check and recent component removal results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoCleanupHistoryScreen(history: AutoCleanupHistory, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auto_cleanup_history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { LatestAutoCleanupCheck(history.lastCheck) }
                item {
                    Text(
                        text = stringResource(R.string.auto_cleanup_history_removals),
                        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                if (history.removals.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.auto_cleanup_history_no_removals),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(history.removals) { removal -> AutoCleanupRemovalRow(removal) }
                }
            }
        }
    }
}

/** Shows the most recent check, including checks that produced no removal records. */
@Composable
private fun LatestAutoCleanupCheck(check: AutoCleanupCheck?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.auto_cleanup_history_last_check),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            if (check == null) {
                Text(
                    text = stringResource(R.string.auto_cleanup_history_no_checks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(cleanupHistoryDateTime(check.timeMillis), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(check.status.historyLabel()),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (check.status == AutoCleanupCheckStatus.INCOMPLETE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Shows the package, Android space, time, and result for one removal. */
@Composable
private fun AutoCleanupRemovalRow(removal: AutoCleanupRemoval) {
    val componentName = PackageDirectory.variants[removal.packageName]?.displayName
    val spaceId = stringResource(R.string.space_id, removal.space.id)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(componentName ?: removal.packageName, style = MaterialTheme.typography.titleSmall)
            if (componentName != null && componentName != removal.packageName) {
                Text(
                    text = removal.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.status_in_space, removal.space.displayName(), spaceId),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = cleanupHistoryDateTime(removal.timeMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(if (removal.success) R.string.uninstalled_result else R.string.unverified_result),
                style = MaterialTheme.typography.labelLarge,
                color = if (removal.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Formats a saved instant using the device's locale and time format. */
@Composable
private fun cleanupHistoryDateTime(timeMillis: Long): String {
    val context = LocalContext.current
    val date = Date(timeMillis)
    return stringResource(
        R.string.auto_cleanup_history_date_time,
        DateFormat.getMediumDateFormat(context).format(date),
        DateFormat.getTimeFormat(context).format(date),
    )
}

/** Describes the saved check result independently from the service's live status. */
@StringRes
private fun AutoCleanupCheckStatus.historyLabel(): Int = when (this) {
    AutoCleanupCheckStatus.COMPLETE -> R.string.auto_cleanup_history_check_complete
    AutoCleanupCheckStatus.INCOMPLETE -> R.string.auto_cleanup_history_check_incomplete
    AutoCleanupCheckStatus.NO_SPACES -> R.string.auto_cleanup_history_check_no_spaces
    AutoCleanupCheckStatus.INTERRUPTED -> R.string.auto_cleanup_history_check_interrupted
}
