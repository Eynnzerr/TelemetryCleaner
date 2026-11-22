@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.history

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
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
import com.eynnzerr.telemetrycleaner.ui.components.PackageNameText
import com.eynnzerr.telemetrycleaner.ui.components.StatusBadge
import com.eynnzerr.telemetrycleaner.ui.components.StatusTone
import com.eynnzerr.telemetrycleaner.ui.components.displayName
import com.eynnzerr.telemetrycleaner.ui.components.segmentedContainerColor
import java.util.Date

/** Shows the most recent check with Expressive semantic status coloring. */
@Composable
internal fun LatestAutoCleanupCheck(check: AutoCleanupCheck?) {
    val colors = MaterialTheme.colorScheme
    val (container, content, tone) = when (check?.status) {
        AutoCleanupCheckStatus.COMPLETE -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, StatusTone.PRIMARY)
        AutoCleanupCheckStatus.INCOMPLETE, AutoCleanupCheckStatus.INTERRUPTED -> Triple(
            colors.errorContainer,
            colors.onErrorContainer,
            StatusTone.ERROR,
        )
        else -> Triple(segmentedContainerColor(), colors.onSurface, StatusTone.NEUTRAL)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = container,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.auto_cleanup_history_last_check),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                if (check != null) {
                    StatusBadge(
                        text = stringResource(check.status.historyLabel()),
                        tone = tone,
                    )
                }
            }
            if (check == null) {
                Text(
                    text = stringResource(R.string.auto_cleanup_history_no_checks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.78f),
                )
            } else {
                Text(
                    text = cleanupHistoryDateTime(check.timeMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/** Shows the package, Android space, time, and result badge for one removal inside a segmented card. */
@Composable
internal fun AutoCleanupRemovalRow(
    removal: AutoCleanupRemoval,
    shape: Shape = MaterialTheme.shapes.large,
) {
    val componentName = PackageDirectory.variants[removal.packageName]?.displayName
    val spaceId = stringResource(R.string.space_id, removal.space.id)
    PackageInfoCard(shape = shape) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = componentName ?: removal.packageName,
                style = MaterialTheme.typography.titleSmallEmphasized,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(
                text = stringResource(if (removal.success) R.string.uninstalled_result else R.string.unverified_result),
                tone = if (removal.success) StatusTone.PRIMARY else StatusTone.ERROR,
            )
        }
        if (componentName != null && componentName != removal.packageName) {
            PackageNameText(removal.packageName)
        }
        Text(
            text = stringResource(R.string.status_in_space, removal.space.displayName(), spaceId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = cleanupHistoryDateTime(removal.timeMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
