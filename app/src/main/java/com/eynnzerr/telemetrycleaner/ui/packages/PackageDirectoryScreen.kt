package com.eynnzerr.telemetrycleaner.ui.packages

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.ui.components.SecondaryListHeading
import com.eynnzerr.telemetrycleaner.ui.components.SecondaryListScreen
import com.eynnzerr.telemetrycleaner.ui.components.label
import com.eynnzerr.telemetrycleaner.ui.components.segmentedItemShape
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme

/** Shows the bundled component catalogue grouped by ROM family in Expressive Segmented Lists. */
@Composable
internal fun PackageDirectoryScreen(
    onOpenIssues: () -> Unit,
    onBack: () -> Unit,
) {
    SecondaryListScreen(R.string.package_directory_title, onBack) {
        item(key = "feedback") {
            PackageDirectoryFeedback(onOpenIssues)
        }
        PackageDirectory.groups.forEach { (family, entries) ->
            item(key = family.name) {
                SecondaryListHeading(family.label())
            }
            itemsIndexed(entries, key = { _, entry -> entry.packageName }) { index, entry ->
                PackageDirectoryEntry(
                    entry = entry,
                    shape = segmentedItemShape(index, entries.size),
                )
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
