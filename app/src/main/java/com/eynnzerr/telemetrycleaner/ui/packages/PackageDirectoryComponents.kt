package com.eynnzerr.telemetrycleaner.ui.packages

import android.text.Annotation
import android.text.Spanned
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.ui.components.PackageInfoCard
import com.eynnzerr.telemetrycleaner.ui.components.PackageNameText
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
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Shows one package identity and its carriers. */
@Composable
internal fun PackageDirectoryEntry(entry: PackageEntry) {
    val carriers = entry.carriers.map { stringResource(it.label()) }
    PackageInfoCard {
        Text(entry.displayName, style = MaterialTheme.typography.titleMedium)
        PackageNameText(entry.packageName)
        if (carriers.isNotEmpty()) {
            Text(
                text = carriers.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
