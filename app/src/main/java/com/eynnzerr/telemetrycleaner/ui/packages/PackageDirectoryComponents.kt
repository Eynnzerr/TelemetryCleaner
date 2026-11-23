@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.packages

import android.text.Annotation
import android.text.Spanned
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.feature.packages.AddCustomPackageStatus
import com.eynnzerr.telemetrycleaner.ui.components.PackageInfoCard
import com.eynnzerr.telemetrycleaner.ui.components.PackageNameText
import com.eynnzerr.telemetrycleaner.ui.components.StatusBadge
import com.eynnzerr.telemetrycleaner.ui.components.StatusTone
import com.eynnzerr.telemetrycleaner.ui.components.label
import com.eynnzerr.telemetrycleaner.ui.components.segmentedContainerColor

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

/** Displays one user-added custom test package in the directory with a one-tap remove action. */
@Composable
internal fun CustomDirectoryEntry(
    packageName: String,
    shape: Shape = MaterialTheme.shapes.large,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = segmentedContainerColor(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = packageName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(
                onClick = onRemove,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.remove_from_directory),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Prompts for an exact package identifier, explains the risk scope, and verifies device presence before adding. */
@Composable
internal fun AddCustomPackageDialog(
    input: String,
    status: AddCustomPackageStatus,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val verifying = status is AddCustomPackageStatus.Verifying
    val errorMessage = when (status) {
        AddCustomPackageStatus.Idle,
        AddCustomPackageStatus.Verifying,
        -> null
        AddCustomPackageStatus.InvalidFormat -> stringResource(R.string.custom_package_invalid_format)
        AddCustomPackageStatus.CriticalPackage -> stringResource(R.string.custom_package_critical_protected)
        AddCustomPackageStatus.AlreadyBundled -> stringResource(R.string.custom_package_already_bundled)
        AddCustomPackageStatus.AlreadyAdded -> stringResource(R.string.custom_package_already_added)
        AddCustomPackageStatus.NeedsConnection -> stringResource(R.string.custom_package_needs_connection)
        is AddCustomPackageStatus.NotFound -> stringResource(R.string.custom_package_not_found, status.packageName)
        AddCustomPackageStatus.LookupFailed -> stringResource(R.string.custom_package_lookup_failed)
        AddCustomPackageStatus.SaveFailed -> stringResource(R.string.settings_save_failed)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_package_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.custom_package_add_warning),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !verifying,
                    label = { Text(stringResource(R.string.custom_package_input_label)) },
                    placeholder = { Text(stringResource(R.string.custom_package_input_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!verifying && input.isNotBlank()) onSubmit()
                        },
                    ),
                    isError = errorMessage != null,
                    supportingText = when {
                        verifying -> {
                            { Text(stringResource(R.string.custom_package_verifying)) }
                        }
                        errorMessage != null -> {
                            { Text(errorMessage) }
                        }
                        else -> null
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = !verifying && input.isNotBlank(),
                shapes = ButtonDefaults.shapes(),
            ) {
                if (verifying) {
                    LoadingIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.custom_package_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
