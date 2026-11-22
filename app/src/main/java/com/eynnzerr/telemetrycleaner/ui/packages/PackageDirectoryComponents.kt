@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.packages

import android.text.Annotation
import android.text.Spanned
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.ui.components.PackageInfoCard
import com.eynnzerr.telemetrycleaner.ui.components.PackageNameText
import com.eynnzerr.telemetrycleaner.ui.components.StatusBadge
import com.eynnzerr.telemetrycleaner.ui.components.StatusTone
import com.eynnzerr.telemetrycleaner.ui.components.label

/** Links the annotated word in the translated feedback prompt to GitHub Issues. */
@Composable
internal fun PackageDirectoryFeedback(onOpenIssues: () -> Unit) {
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
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Shows one package identity and its carriers as tonal badges inside a segmented card. */
@Composable
internal fun PackageDirectoryEntry(
    entry: PackageEntry,
    shape: Shape = MaterialTheme.shapes.large,
) {
    PackageInfoCard(shape = shape) {
        Text(entry.displayName, style = MaterialTheme.typography.titleMediumEmphasized)
        PackageNameText(entry.packageName)
        if (entry.carriers.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                entry.carriers.forEach { carrier ->
                    StatusBadge(stringResource(carrier.label()), StatusTone.PRIMARY)
                }
            }
        }
    }
}
