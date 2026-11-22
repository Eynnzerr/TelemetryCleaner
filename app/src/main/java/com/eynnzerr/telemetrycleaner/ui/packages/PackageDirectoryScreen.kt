package com.eynnzerr.telemetrycleaner.ui.packages

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.ui.components.SecondaryListHeading
import com.eynnzerr.telemetrycleaner.ui.components.SecondaryListScreen
import com.eynnzerr.telemetrycleaner.ui.components.label
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme

/** Shows the bundled component catalogue by ROM family. */
@Composable
internal fun PackageDirectoryScreen(
    onOpenIssues: () -> Unit,
    onBack: () -> Unit,
) {
    SecondaryListScreen(R.string.package_directory_title, onBack) {
        item {
            PackageDirectoryFeedback(onOpenIssues)
        }
        PackageDirectory.groups.forEach { (family, entries) ->
            item(key = family.name) {
                SecondaryListHeading(family.label())
            }
            items(entries, key = PackageEntry::packageName) { entry ->
                PackageDirectoryEntry(entry)
            }
        }
    }
}

/** Renders the static catalogue without a device connection. */
@Preview(name = "内置组件目录", widthDp = 400, heightDp = 850, locale = "zh-rCN")
@Preview(name = "Bundled components", widthDp = 400, heightDp = 850, locale = "en")
@Composable
private fun PackageDirectoryScreenPreview() {
    TelemetryCleanerTheme(dynamicColor = false) {
        PackageDirectoryScreen(onOpenIssues = {}, onBack = {})
    }
}
