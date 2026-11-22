package com.eynnzerr.telemetrycleaner.ui.history

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupHistory
import com.eynnzerr.telemetrycleaner.ui.components.SecondaryListHeading
import com.eynnzerr.telemetrycleaner.ui.components.SecondaryListScreen

/** Displays the latest automatic check and recent component removal results. */
@Composable
internal fun AutoCleanupHistoryScreen(history: AutoCleanupHistory, onBack: () -> Unit) {
    SecondaryListScreen(R.string.auto_cleanup_history_title, onBack) {
        item { LatestAutoCleanupCheck(history.lastCheck) }
        item { SecondaryListHeading(R.string.auto_cleanup_history_removals) }
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
