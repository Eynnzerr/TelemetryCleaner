package com.eynnzerr.telemetrycleaner.ui.history

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupHistory
import com.eynnzerr.telemetrycleaner.ui.components.SecondaryListHeading
import com.eynnzerr.telemetrycleaner.ui.components.SecondaryListScreen
import com.eynnzerr.telemetrycleaner.ui.components.segmentedItemShape

/** Displays the latest automatic check and recent component removal results in Expressive Segmented Lists. */
@Composable
internal fun AutoCleanupHistoryScreen(history: AutoCleanupHistory, onBack: () -> Unit) {
    SecondaryListScreen(R.string.auto_cleanup_history_title, onBack) {
        item(key = "last_check") { LatestAutoCleanupCheck(history.lastCheck) }
        item(key = "removals_heading") { SecondaryListHeading(R.string.auto_cleanup_history_removals) }
        if (history.removals.isEmpty()) {
            item(key = "no_removals") {
                Text(
                    text = stringResource(R.string.auto_cleanup_history_no_removals),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            itemsIndexed(history.removals) { index, removal ->
                AutoCleanupRemovalRow(
                    removal = removal,
                    shape = segmentedItemShape(index, history.removals.size),
                )
            }
        }
    }
}
