@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.settings

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupStatus
import com.eynnzerr.telemetrycleaner.data.preferences.label
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme

/** Groups automatic cleanup settings into a 3-item Segmented List. */
@Composable
internal fun CleanupSettings(
    enabled: Boolean?,
    status: AutoCleanupStatus,
    intervalMinutes: Int,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onOpenHistory: () -> Unit,
) {
    SettingsGroup(stringResource(R.string.settings_group_cleanup)) {
        AutoCleanupSettings(
            enabled = enabled,
            status = status,
            intervalMinutes = intervalMinutes,
            index = 0,
            count = 3,
            onEnabledChange = onEnabledChange,
        )
        AutoCleanupIntervalSettings(
            enabled = enabled == true,
            intervalMinutes = intervalMinutes,
            index = 1,
            count = 3,
            onIntervalChange = onIntervalChange,
        )
        SettingsLink(
            icon = R.drawable.ic_settings_history,
            title = R.string.auto_cleanup_history_title,
            description = R.string.settings_history_description,
            index = 2,
            count = 3,
            enabled = enabled == true,
            onClick = onOpenHistory,
        )
    }
}

/** Renders an Expressive Switch with a check icon inside the thumb when enabled. */
@Composable
private fun ExpressiveSwitch(checked: Boolean, enabled: Boolean) {
    Switch(
        checked = checked,
        enabled = enabled,
        onCheckedChange = null,
        thumbContent = if (checked) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_check),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        } else {
            null
        },
    )
}

/** Explains the effect of enabling cleanup and reports the foreground service's status. */
@Composable
private fun AutoCleanupSettings(
    enabled: Boolean?,
    status: AutoCleanupStatus,
    intervalMinutes: Int,
    index: Int,
    count: Int,
    onEnabledChange: (Boolean) -> Unit,
) {
    var showEnableConfirmation by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    SettingsItem(
        icon = R.drawable.ic_refresh,
        title = stringResource(R.string.auto_cleanup_notification_title),
        index = index,
        count = count,
        interactionSource = interactionSource,
        modifier = Modifier.toggleable(
            value = enabled == true,
            enabled = enabled != null,
            role = Role.Switch,
            interactionSource = interactionSource,
            indication = ripple(),
            onValueChange = { requestedEnabled ->
                if (requestedEnabled) {
                    showEnableConfirmation = true
                } else {
                    onEnabledChange(false)
                }
            },
        ),
        supportingContent = {
            val failed = status == AutoCleanupStatus.CHECK_FAILED || status == AutoCleanupStatus.START_FAILED
            val subtitle = if (status == AutoCleanupStatus.OFF) {
                stringResource(R.string.settings_auto_cleanup_description, cleanupIntervalLabel(intervalMinutes))
            } else {
                stringResource(status.label())
            }
            Text(
                text = subtitle,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { ExpressiveSwitch(checked = enabled == true, enabled = enabled != null) },
    )
    if (showEnableConfirmation && enabled == false) {
        AutoCleanupEnableDialog(
            intervalMinutes = intervalMinutes,
            onConfirm = {
                showEnableConfirmation = false
                onEnabledChange(true)
            },
            onDismiss = { showEnableConfirmation = false },
        )
    }
}

/** Opens the interval editor when automatic cleanup is enabled. */
@Composable
private fun AutoCleanupIntervalSettings(
    enabled: Boolean,
    intervalMinutes: Int,
    index: Int,
    count: Int,
    onIntervalChange: (Int) -> Unit,
) {
    var editing by rememberSaveable(enabled) { mutableStateOf(false) }
    SettingsItem(
        icon = R.drawable.ic_settings_schedule,
        title = stringResource(R.string.settings_auto_cleanup_interval),
        index = index,
        count = count,
        supportingContent = { Text(cleanupIntervalLabel(intervalMinutes)) },
        enabled = enabled,
        onClick = { editing = true },
    )
    if (editing && enabled) {
        AutoCleanupIntervalDialog(
            intervalMinutes = intervalMinutes,
            onConfirm = {
                editing = false
                onIntervalChange(it)
            },
            onDismiss = { editing = false },
        )
    }
}

/** Edits a positive interval in whole minutes. */
@Composable
private fun AutoCleanupIntervalDialog(
    intervalMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(intervalMinutes.toString()) }
    val minutes = input.trim().toIntOrNull()?.takeIf { it > 0 }
    val invalid = input.isNotBlank() && minutes == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_cleanup_interval)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_auto_cleanup_interval_unit)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = invalid,
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.settings_auto_cleanup_interval_error)) }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = minutes != null,
                onClick = { minutes?.let(onConfirm) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Formats the persisted interval with the localized minute unit. */
@Composable
private fun cleanupIntervalLabel(intervalMinutes: Int): String =
    pluralStringResource(R.plurals.settings_auto_cleanup_interval_minutes, intervalMinutes, intervalMinutes)

/** Asks for consent before enabling automatic removal in the selected spaces. */
@Composable
private fun AutoCleanupEnableDialog(intervalMinutes: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_cleanup_enable_title)) },
        text = { Text(stringResource(R.string.auto_cleanup_enable_message, cleanupIntervalLabel(intervalMinutes))) },
        confirmButton = {
            TextButton(onClick = onConfirm, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.auto_cleanup_enable_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Previews the enablement notice without starting the service. */
@Preview(name = "开启自动清理", widthDp = 400, locale = "zh-rCN")
@Preview(name = "Enable automatic cleanup", widthDp = 400, locale = "en")
@Composable
private fun AutoCleanupEnableDialogPreview() {
    TelemetryCleanerTheme(dynamicColor = false) {
        AutoCleanupEnableDialog(intervalMinutes = 30, onConfirm = {}, onDismiss = {})
    }
}

/** Previews the interval editor with an existing value. */
@Preview(name = "清理周期", widthDp = 400, locale = "zh-rCN")
@Composable
private fun AutoCleanupIntervalDialogPreview() {
    TelemetryCleanerTheme(dynamicColor = false) {
        AutoCleanupIntervalDialog(intervalMinutes = 30, onConfirm = {}, onDismiss = {})
    }
}
