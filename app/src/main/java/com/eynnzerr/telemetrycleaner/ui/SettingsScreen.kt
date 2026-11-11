package com.eynnzerr.telemetrycleaner.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.AutoCleanupStatus
import com.eynnzerr.telemetrycleaner.data.ThemePalette
import com.eynnzerr.telemetrycleaner.feature.CleanerActivity
import com.eynnzerr.telemetrycleaner.feature.CleanerState
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme
import com.eynnzerr.telemetrycleaner.ui.theme.swatchColor

/** Displays scan, cleanup, appearance, and project settings in grouped rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: CleanerState,
    autoCleanupEnabled: Boolean?,
    autoCleanupStatus: AutoCleanupStatus,
    autoCleanupIntervalMinutes: Int,
    onAutoCleanupChange: (Boolean) -> Unit,
    onAutoCleanupIntervalChange: (Int) -> Unit,
    onToggleSpace: (Int) -> Unit,
    onOpenAutoCleanupHistory: () -> Unit,
    onOpenPackageDirectory: () -> Unit,
    appVersion: String,
    themePalette: ThemePalette,
    onThemePaletteChange: (ThemePalette) -> Unit,
    checkingForUpdate: Boolean,
    onCheckForUpdate: () -> Unit,
    onOpenProject: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
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
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ScopeSettings(state, onToggleSpace)
                SettingsGroup(stringResource(R.string.settings_group_cleanup)) {
                    AutoCleanupSettings(
                        enabled = autoCleanupEnabled,
                        status = autoCleanupStatus,
                        intervalMinutes = autoCleanupIntervalMinutes,
                        onEnabledChange = onAutoCleanupChange,
                    )
                    AutoCleanupIntervalSettings(
                        enabled = autoCleanupEnabled == true,
                        intervalMinutes = autoCleanupIntervalMinutes,
                        onIntervalChange = onAutoCleanupIntervalChange,
                    )
                    SettingsLink(
                        icon = R.drawable.ic_settings_history,
                        title = R.string.auto_cleanup_history_title,
                        description = R.string.settings_history_description,
                        enabled = autoCleanupEnabled == true,
                        onClick = onOpenAutoCleanupHistory,
                    )
                }
                SettingsGroup(stringResource(R.string.settings_group_appearance)) {
                    ThemeSettings(themePalette, onThemePaletteChange)
                }
                SettingsGroup(stringResource(R.string.settings_group_about)) {
                    SettingsItem(
                        icon = R.drawable.ic_settings_version,
                        title = stringResource(R.string.settings_version),
                        supportingContent = {
                            Text(
                                if (checkingForUpdate) stringResource(R.string.settings_version_checking)
                                else stringResource(R.string.settings_version_description, appVersion),
                            )
                        },
                        trailingContent = if (checkingForUpdate) {
                            {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            null
                        },
                        enabled = !checkingForUpdate,
                        onClick = onCheckForUpdate,
                    )
                    SettingsLink(
                        icon = R.drawable.ic_settings_project,
                        title = R.string.settings_project,
                        description = R.string.settings_project_description,
                        onClick = onOpenProject,
                    )
                    SettingsLink(
                        icon = R.drawable.ic_settings_directory,
                        title = R.string.package_directory_title,
                        description = R.string.settings_directory_description,
                        onClick = onOpenPackageDirectory,
                    )
                }
            }
        }
    }
}

/** Places a shared rounded surface under a settings heading. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 16.dp).semantics { heading() },
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(content = content)
        }
    }
}

/** Renders one setting with optional supporting text, trailing content, and row click. */
@Composable
private fun SettingsItem(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val disabledColor = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ListItem(
        headlineContent = { Text(title) },
        modifier = if (onClick == null) modifier else modifier.clickable(enabled = enabled, onClick = onClick),
        supportingContent = supportingContent,
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = disabledColor,
            supportingColor = disabledColor,
            leadingIconColor = disabledColor,
        ),
    )
}

/** Displays a link to a separate settings page or project resource. */
@Composable
private fun SettingsLink(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes description: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingsItem(
        icon = icon,
        title = stringResource(title),
        supportingContent = { Text(stringResource(description)) },
        enabled = enabled,
        onClick = onClick,
    )
}

/** Lets the user expand and select the app's color palette. */
@Composable
private fun ThemeSettings(
    selectedPalette: ThemePalette,
    onSelect: (ThemePalette) -> Unit,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val swatchScroll = rememberScrollState()
    SettingsItem(
        icon = R.drawable.ic_settings_palette,
        title = stringResource(R.string.settings_theme),
        supportingContent = { Text(stringResource(selectedPalette.labelRes())) },
        trailingContent = {
            Icon(
                painterResource(R.drawable.ic_settings_expand),
                contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        },
        onClick = { expanded = !expanded },
    )
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .horizontalScroll(swatchScroll)
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ThemePalette.entries.forEach { palette ->
                ThemeSwatch(palette, selected = palette == selectedPalette, onClick = { onSelect(palette) })
            }
        }
    }
}

/** Displays a selectable color sample and its palette name. */
@Composable
private fun ThemeSwatch(palette: ThemePalette, selected: Boolean, onClick: () -> Unit) {
    val color = palette.swatchColor()
    Column(
        modifier = Modifier
            .widthIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .border(1.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                .padding(4.dp)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    painterResource(R.drawable.ic_settings_check),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (color.luminance() > 0.45f) Color.Black else Color.White,
                )
            }
        }
        Text(
            text = stringResource(palette.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Resolves the localized label displayed for a color palette. */
@StringRes
private fun ThemePalette.labelRes(): Int = when (this) {
    ThemePalette.SYSTEM -> R.string.theme_palette_system
    ThemePalette.TEAL -> R.string.theme_palette_teal
    ThemePalette.BLUE -> R.string.theme_palette_blue
    ThemePalette.PURPLE -> R.string.theme_palette_purple
    ThemePalette.AMBER -> R.string.theme_palette_amber
    ThemePalette.GREEN -> R.string.theme_palette_green
    ThemePalette.CYAN -> R.string.theme_palette_cyan
    ThemePalette.ROSE -> R.string.theme_palette_rose
    ThemePalette.ORANGE -> R.string.theme_palette_orange
    ThemePalette.SLATE -> R.string.theme_palette_slate
}

/** Lets the user choose the Android spaces included in manual and automatic checks. */
@Composable
private fun ScopeSettings(state: CleanerState, onToggleSpace: (Int) -> Unit) {
    val snapshot = state.snapshot
    SettingsGroup(stringResource(R.string.scope_spaces)) {
        if (snapshot == null) {
            SettingsItem(
                icon = R.drawable.ic_settings_space,
                title = stringResource(R.string.scope_unavailable),
            )
        }
        snapshot?.users.orEmpty().forEach { user ->
            val checked = user.id in state.selectedUserIds
            SpaceRow(
                user = user,
                checked = checked,
                current = user.id == snapshot?.currentUserId,
                enabled = state.activity == CleanerActivity.IDLE && (user.serialNumber != null || checked),
                onClick = { onToggleSpace(user.id) },
            )
        }
    }
}

/** Shows one discovered space and its inclusion in the current scope. */
@Composable
private fun SpaceRow(
    user: AndroidUser,
    checked: Boolean,
    current: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SettingsItem(
        icon = R.drawable.ic_settings_space,
        title = user.displayName(),
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onClick() },
        supportingContent = {
            Text(
                when {
                    user.serialNumber == null -> stringResource(R.string.scope_space_unavailable)
                    current -> stringResource(R.string.current_space)
                    else -> stringResource(R.string.space_id, user.id)
                },
            )
        },
        trailingContent = { Checkbox(checked = checked, enabled = enabled, onCheckedChange = null) },
    )
}

/** Explains the effect of enabling cleanup and reports the foreground service's status. */
@Composable
private fun AutoCleanupSettings(
    enabled: Boolean?,
    status: AutoCleanupStatus,
    intervalMinutes: Int,
    onEnabledChange: (Boolean) -> Unit,
) {
    var showEnableConfirmation by rememberSaveable { mutableStateOf(false) }
    SettingsItem(
        icon = R.drawable.ic_refresh,
        title = stringResource(R.string.auto_cleanup_notification_title),
        modifier = Modifier.toggleable(
            value = enabled == true,
            enabled = enabled != null,
            role = Role.Switch,
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
        trailingContent = { Switch(checked = enabled == true, enabled = enabled != null, onCheckedChange = null) },
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
    onIntervalChange: (Int) -> Unit,
) {
    var editing by rememberSaveable(enabled) { mutableStateOf(false) }
    SettingsItem(
        icon = R.drawable.ic_settings_schedule,
        title = stringResource(R.string.settings_auto_cleanup_interval),
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
            TextButton(enabled = minutes != null, onClick = { minutes?.let(onConfirm) }) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.auto_cleanup_enable_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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

/** Previews the expanded palette chooser with a preset selected. */
@Preview(name = "主题选色", widthDp = 400, locale = "zh-rCN")
@Composable
private fun ThemeSettingsPreview() {
    TelemetryCleanerTheme(dynamicColor = false, palette = ThemePalette.PURPLE) {
        SettingsGroup(stringResource(R.string.settings_group_appearance)) {
            ThemeSettings(ThemePalette.PURPLE, onSelect = {}, initiallyExpanded = true)
        }
    }
}

/** Returns the localized status label used by settings and the foreground notification. */
@StringRes
internal fun AutoCleanupStatus.label(): Int = when (this) {
    AutoCleanupStatus.OFF -> R.string.auto_cleanup_off
    AutoCleanupStatus.STARTING -> R.string.auto_cleanup_starting
    AutoCleanupStatus.WAITING_FOR_SHIZUKU -> R.string.auto_cleanup_waiting_for_shizuku
    AutoCleanupStatus.CHECKING -> R.string.auto_cleanup_checking
    AutoCleanupStatus.MONITORING -> R.string.auto_cleanup_monitoring
    AutoCleanupStatus.NO_SPACES -> R.string.scope_empty
    AutoCleanupStatus.CHECK_FAILED -> R.string.auto_cleanup_check_failed
    AutoCleanupStatus.START_FAILED -> R.string.auto_cleanup_start_failed
}
