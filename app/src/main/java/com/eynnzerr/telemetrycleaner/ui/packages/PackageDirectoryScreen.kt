@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.packages

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.feature.packages.PackageDirectoryUiState
import com.eynnzerr.telemetrycleaner.ui.components.SecondaryListHeading
import com.eynnzerr.telemetrycleaner.ui.components.SecondaryListScreen
import com.eynnzerr.telemetrycleaner.ui.components.label
import com.eynnzerr.telemetrycleaner.ui.components.segmentedItemShape
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme

/**
 * Shows the bundled component catalogue grouped by ROM family plus any user-added custom test
 * packages, with an action in the top bar to verify and add an exact package name.
 */
@Composable
internal fun PackageDirectoryScreen(
    state: PackageDirectoryUiState,
    onOpenAddDialog: () -> Unit,
    onDismissAddDialog: () -> Unit,
    onInputChange: (String) -> Unit,
    onSubmitAdd: () -> Unit,
    onRemoveCustomPackage: (String) -> Unit,
    onOpenIssues: () -> Unit,
    onBack: () -> Unit,
) {
    SecondaryListScreen(
        title = R.string.package_directory_title,
        onBack = onBack,
        actions = {
            FilledTonalIconButton(
                onClick = onOpenAddDialog,
                modifier = Modifier.padding(end = 4.dp),
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.custom_package_add_action),
                )
            }
        },
    ) {
        item(key = "feedback") {
            PackageDirectoryFeedback(onOpenIssues)
        }
        if (state.customPackageNames.isNotEmpty()) {
            item(key = "custom_heading") {
                SecondaryListHeading(R.string.section_custom_packages)
            }
            itemsIndexed(state.customPackageNames, key = { _, name -> "custom_$name" }) { index, packageName ->
                CustomDirectoryEntry(
                    packageName = packageName,
                    shape = segmentedItemShape(index, state.customPackageNames.size),
                    onRemove = { onRemoveCustomPackage(packageName) },
                )
            }
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

    if (state.addDialogOpen) {
        AddCustomPackageDialog(
            input = state.input,
            status = state.status,
            onInputChange = onInputChange,
            onSubmit = onSubmitAdd,
            onDismiss = onDismissAddDialog,
        )
    }
}

/** Renders the static catalogue alongside a sample custom test package without a device connection. */
@Preview(name = "内置组件目录", widthDp = 400, heightDp = 850, locale = "zh-rCN")
@Preview(name = "Bundled components", widthDp = 400, heightDp = 850, locale = "en")
@Composable
private fun PackageDirectoryScreenPreview() {
    TelemetryCleanerTheme(dynamicColor = false) {
        PackageDirectoryScreen(
            state = PackageDirectoryUiState(customPackageNames = listOf("com.vendor.customtelemetry")),
            onOpenAddDialog = {},
            onDismissAddDialog = {},
            onInputChange = {},
            onSubmitAdd = {},
            onRemoveCustomPackage = {},
            onOpenIssues = {},
            onBack = {},
        )
    }
}
