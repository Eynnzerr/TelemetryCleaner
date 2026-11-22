@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.packages

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.feature.packages.PackageDetailsUiState
import com.eynnzerr.telemetrycleaner.ui.components.CleanerModalSheet
import com.eynnzerr.telemetrycleaner.ui.components.PackageLoadingIndicator
import com.eynnzerr.telemetrycleaner.ui.components.PackageNameText
import com.eynnzerr.telemetrycleaner.ui.components.StatusBadge
import com.eynnzerr.telemetrycleaner.ui.components.StatusTone
import com.eynnzerr.telemetrycleaner.ui.components.rememberAnimatedSegmentedShape
import com.eynnzerr.telemetrycleaner.ui.components.segmentedContainerColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Centers the Expressive contained loading indicator while package inventory loads. */
@Composable
internal fun SearchResultsLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PackageLoadingIndicator(contained = true)
    }
}

/** Renders the search detail sheet using state managed by [com.eynnzerr.telemetrycleaner.feature.packages.PackageSearchViewModel]. */
@Composable
internal fun SearchPackageDetailsSheet(
    details: PackageDetailsUiState,
    onDismiss: () -> Unit,
) {
    val packageName = when (details) {
        PackageDetailsUiState.Closed -> return
        is PackageDetailsUiState.Loading -> details.packageName
        is PackageDetailsUiState.Loaded -> details.packageName
        is PackageDetailsUiState.Failed -> details.packageName
    }
    val snapshot = (details as? PackageDetailsUiState.Loaded)?.snapshot
    CleanerModalSheet(onDismiss = onDismiss) {
        PackageDetailsSheetContent(packageName, snapshot, onDismiss) {
            if (details is PackageDetailsUiState.Failed) {
                Text(stringResource(R.string.details_load_failed, packageName))
            } else {
                PackageLoadingIndicator(contained = true)
            }
        }
    }
}

/** Shows one package inside an Expressive segmented row with press corner morphing and status badges. */
@Composable
internal fun SearchResult(
    entry: PackageEntry,
    index: Int,
    count: Int,
    added: Boolean,
    onAdd: (String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusBadge = when {
        entry.packageName in PackageDirectory.variants -> R.string.package_search_bundled to StatusTone.PRIMARY
        added -> R.string.package_search_added to StatusTone.SECONDARY
        else -> null
    }
    val interactionSource = remember { MutableInteractionSource() }
    val shape = rememberAnimatedSegmentedShape(index, count, interactionSource)

    Surface(
        modifier = modifier
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClickLabel = stringResource(R.string.view_details),
                onClick = onOpen,
            ),
        shape = shape,
        color = segmentedContainerColor(),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = if (entry.displayName != entry.packageName) {
                {
                    PackageNameText(entry.packageName, singleLine = true)
                }
            } else {
                null
            },
            leadingContent = { SearchResultIcon(entry.packageName) },
            trailingContent = {
                if (statusBadge == null) {
                    FilledTonalButton(
                        onClick = { onAdd(entry.packageName) },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.package_search_add))
                    }
                } else {
                    StatusBadge(
                        text = stringResource(statusBadge.first),
                        tone = statusBadge.second,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** Loads the app icon off the main thread and shows a themed fallback when the icon is unavailable. */
@Composable
private fun SearchResultIcon(packageName: String) {
    val packageManager = LocalContext.current.packageManager
    val size = 40.dp
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, packageName, sizePx) {
        value = withContext(Dispatchers.IO) {
            try {
                val info = packageManager.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                info.loadIcon(packageManager).toBitmap(width = sizePx, height = sizePx).asImageBitmap()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Surface(
            modifier = Modifier.size(size),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_settings_directory), contentDescription = null)
            }
        }
    }
}
