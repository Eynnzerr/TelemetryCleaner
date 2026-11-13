package com.eynnzerr.telemetrycleaner.ui

import android.text.Annotation
import android.text.Spanned
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PackageFamily
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme

/** Shows the bundled component catalogue by ROM family with a link for feedback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PackageDirectoryScreen(onOpenIssues: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.package_directory_title)) },
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
                item {
                    PackageDirectoryFeedback(onOpenIssues)
                }
                PackageDirectory.groups.forEach { (family, entries) ->
                    item(key = family.name) {
                        Text(
                            text = stringResource(family.label()),
                            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    items(entries, key = PackageEntry::packageName) { entry ->
                        PackageDirectoryEntry(entry)
                    }
                }
            }
        }
    }
}

/** Links the annotated word in the translated feedback prompt to GitHub Issues. */
@Composable
private fun PackageDirectoryFeedback(onOpenIssues: () -> Unit) {
    val feedback = LocalResources.current.getText(R.string.package_directory_feedback)
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    Text(
        text = buildAnnotatedString {
            append(feedback.toString())
            if (feedback is Spanned) {
                feedback.getSpans(0, feedback.length, Annotation::class.java)
                    .firstOrNull { it.key == "link" && it.value == "issues" }
                    ?.let { span ->
                        addLink(
                            LinkAnnotation.Clickable(tag = "issues", styles = linkStyle) { onOpenIssues() },
                            feedback.getSpanStart(span),
                            feedback.getSpanEnd(span),
                        )
                    }
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Shows one package identity and its carriers. */
@Composable
private fun PackageDirectoryEntry(entry: PackageEntry) {
    val carriers = entry.carriers.map { stringResource(it.label()) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = entry.packageName,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = carriers.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Resolves the localized label for a group in the bundled catalogue. */
@StringRes
internal fun PackageFamily.label(): Int = when (this) {
    PackageFamily.CROSS_VENDOR -> R.string.package_family_cross_vendor
    PackageFamily.COLOR_OS -> R.string.package_family_color_os
    PackageFamily.XIAOMI -> R.string.package_family_xiaomi
    PackageFamily.VIVO -> R.string.package_family_vivo
    PackageFamily.HUAWEI -> R.string.package_family_huawei
    PackageFamily.HONOR -> R.string.package_family_honor
    PackageFamily.FLYME -> R.string.package_family_flyme
    PackageFamily.NUBIA -> R.string.package_family_nubia
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
