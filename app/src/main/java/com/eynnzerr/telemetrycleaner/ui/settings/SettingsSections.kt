package com.eynnzerr.telemetrycleaner.ui.settings

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.data.preferences.ThemePalette
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerActivity
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerError
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState
import com.eynnzerr.telemetrycleaner.ui.components.displayName
import com.eynnzerr.telemetrycleaner.ui.components.statusLabel
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme
import com.eynnzerr.telemetrycleaner.ui.theme.swatchColor

/** Lets the user select the same privilege backend for foreground and background operations. */
@Composable
internal fun PrivilegeBackendSettings(state: CleanerState, onSelect: (PrivilegeBackend) -> Unit) {
    val enabled = state.activity == CleanerActivity.IDLE && state.connection.status != PrivilegeStatus.CONNECTING
    SettingsGroup(stringResource(R.string.settings_privilege_backend)) {
        Column(Modifier.selectableGroup()) {
            PrivilegeBackend.entries.forEach { backend ->
                val selected = state.connection.backend == backend
                val name = when (backend) {
                    PrivilegeBackend.SHIZUKU -> R.string.backend_shizuku
                    PrivilegeBackend.ROOT -> R.string.backend_root
                }
                ListItem(
                    headlineContent = { Text(stringResource(name)) },
                    supportingContent = if (selected && backend == PrivilegeBackend.ROOT &&
                        state.connection.status != PrivilegeStatus.READY
                    ) {
                        { Text(stringResource(state.connection.statusLabel())) }
                    } else {
                        null
                    },
                    trailingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
                    modifier = Modifier.selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(backend) },
                    ),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        if (state.error == CleanerError.BACKEND_SELECTION_FAILED) {
            Text(
                text = stringResource(R.string.backend_selection_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

/** Places a shared rounded surface under a settings heading. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
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
internal fun SettingsItem(
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
internal fun SettingsLink(
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
internal fun ThemeSettings(
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
internal fun ScopeSettings(state: CleanerState, onToggleSpace: (Int) -> Unit) {
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

/** Shows app version and links to project resources. */
@Composable
internal fun AboutSettings(
    appVersion: String,
    checkingForUpdate: Boolean,
    onCheckForUpdate: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenPackageDirectory: () -> Unit,
) {
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
