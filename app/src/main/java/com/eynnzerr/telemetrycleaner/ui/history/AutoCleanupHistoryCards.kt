package com.eynnzerr.telemetrycleaner.ui.history

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupCheck
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupCheckStatus
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupRemoval
import com.eynnzerr.telemetrycleaner.ui.components.PackageInfoCard
import com.eynnzerr.telemetrycleaner.ui.components.displayName
import java.util.Date

/** Shows the most recent check, including checks that produced no removal records. */
@Composable
internal fun LatestAutoCleanupCheck(check: AutoCleanupCheck?) {
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
internal fun AutoCleanupRemovalRow(removal: AutoCleanupRemoval) {
    val componentName = PackageDirectory.variants[removal.packageName]?.displayName
    val spaceId = stringResource(R.string.space_id, removal.space.id)
    PackageInfoCard {
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
