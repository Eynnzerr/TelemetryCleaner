package com.eynnzerr.telemetrycleaner.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.ReleaseCheckResult
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme

/** Shows the result of an on-demand release check and offers the applicable GitHub link. */
@Composable
internal fun ReleaseCheckDialog(
    result: ReleaseCheckResult,
    currentVersion: String,
    onOpenRelease: (String) -> Unit,
    onOpenReleases: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, message) = when (result) {
        is ReleaseCheckResult.UpdateAvailable -> stringResource(R.string.update_available_title) to
            stringResource(R.string.update_available_message, currentVersion, result.version)
        is ReleaseCheckResult.UpToDate -> stringResource(R.string.update_up_to_date_title) to
            stringResource(R.string.update_up_to_date_message, currentVersion)
        ReleaseCheckResult.NoRelease -> stringResource(R.string.update_no_release_title) to
            stringResource(R.string.update_no_release_message)
        ReleaseCheckResult.Failed -> stringResource(R.string.update_failed_title) to
            stringResource(R.string.update_failed_message)
    }
    val hasGitHubLink = result is ReleaseCheckResult.UpdateAvailable || result == ReleaseCheckResult.NoRelease
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            when (result) {
                is ReleaseCheckResult.UpdateAvailable -> TextButton(onClick = { onOpenRelease(result.releaseUrl) }) {
                    Text(stringResource(R.string.update_open_release))
                }
                ReleaseCheckResult.NoRelease -> TextButton(onClick = onOpenReleases) {
                    Text(stringResource(R.string.update_open_releases))
                }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_close)) }
            }
        },
        dismissButton = {
            if (hasGitHubLink) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_close)) }
            }
        },
    )
}

/** Supplies examples of each release check result. */
internal class ReleaseCheckPreviewProvider : PreviewParameterProvider<ReleaseCheckResult> {
    /** Emits a preview per result without making a network request. */
    override val values: Sequence<ReleaseCheckResult> = sequenceOf(
        ReleaseCheckResult.UpdateAvailable("v1.1.0", "https://github.com/Eynnzerr/TelemetryCleaner/releases/tag/v1.1.0"),
        ReleaseCheckResult.UpToDate("v1.0.0"),
        ReleaseCheckResult.NoRelease,
        ReleaseCheckResult.Failed,
    )
}

/** Previews the release check feedback. */
@Preview(group = "版本检查", widthDp = 400, locale = "zh-rCN")
@Composable
private fun ReleaseCheckDialogPreview(@PreviewParameter(ReleaseCheckPreviewProvider::class) result: ReleaseCheckResult) {
    TelemetryCleanerTheme(dynamicColor = false) {
        ReleaseCheckDialog(
            result = result,
            currentVersion = "1.0",
            onOpenRelease = {},
            onOpenReleases = {},
            onDismiss = {},
        )
    }
}
