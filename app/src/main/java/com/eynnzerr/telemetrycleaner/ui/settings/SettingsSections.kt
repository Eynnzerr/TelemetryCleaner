@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
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
import com.eynnzerr.telemetrycleaner.data.DhizukuStatus
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.data.preferences.ThemePalette
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerActivity
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerError
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState
import com.eynnzerr.telemetrycleaner.ui.components.ExpressiveButton
import com.eynnzerr.telemetrycleaner.ui.components.ExpressiveButtonSize
import com.eynnzerr.telemetrycleaner.ui.components.SegmentedListDefaults
import com.eynnzerr.telemetrycleaner.ui.components.actionLabel
import com.eynnzerr.telemetrycleaner.ui.components.displayName
import com.eynnzerr.telemetrycleaner.ui.components.performDhizukuStatusAction
import com.eynnzerr.telemetrycleaner.ui.components.rememberAnimatedSegmentedShape
import com.eynnzerr.telemetrycleaner.ui.components.segmentedContainerColor
import com.eynnzerr.telemetrycleaner.ui.components.segmentedItemShape
import com.eynnzerr.telemetrycleaner.ui.components.statusLabel
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme
import com.eynnzerr.telemetrycleaner.ui.theme.previewColors

/**
 * Lets the user select the privilege backend using an Expressive Connected ButtonGroup,
 * plus an optional Dhizuku Device Owner enhancement card when Shizuku is selected.
 */
@Composable
internal fun PrivilegeBackendSettings(
    state: CleanerState,
    onSelect: (PrivilegeBackend) -> Unit,
    dhizukuFallbackEnabled: Boolean?,
    dhizukuStatus: DhizukuStatus,
    onDhizukuFallbackChange: (Boolean) -> Unit,
    onRequestDhizukuPermission: () -> Unit,
    onRefreshDhizukuStatus: () -> Unit,
) {
    val enabled = state.activity == CleanerActivity.IDLE && state.connection.status != PrivilegeStatus.CONNECTING
    val showDhizuku = state.connection.backend == PrivilegeBackend.SHIZUKU
    SettingsGroup(stringResource(R.string.settings_privilege_backend)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = if (showDhizuku) segmentedItemShape(0, 2) else RoundedCornerShape(SegmentedListDefaults.OuterCorner),
            color = segmentedContainerColor(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    PrivilegeBackend.entries.forEachIndexed { index, backend ->
                        val selected = state.connection.backend == backend
                        val name = when (backend) {
                            PrivilegeBackend.SHIZUKU -> R.string.backend_shizuku
                            PrivilegeBackend.ROOT -> R.string.backend_root
                        }
                        val shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            else -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        }
                        TonalToggleButton(
                            checked = selected,
                            onCheckedChange = { if (it) onSelect(backend) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            shapes = shapes,
                        ) {
                            if (selected) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = stringResource(name),
                                style = MaterialTheme.typography.labelLargeEmphasized,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (state.connection.backend == PrivilegeBackend.ROOT &&
                    state.connection.status != PrivilegeStatus.READY
                ) {
                    Text(
                        text = stringResource(state.connection.statusLabel()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (state.error == CleanerError.BACKEND_SELECTION_FAILED) {
                    Text(
                        text = stringResource(R.string.backend_selection_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        if (showDhizuku) {
            DhizukuEnhancementItem(
                enabled = dhizukuFallbackEnabled,
                status = dhizukuStatus,
                onEnabledChange = onDhizukuFallbackChange,
                onRequestPermission = onRequestDhizukuPermission,
                onRefreshStatus = onRefreshDhizukuStatus,
            )
        }
    }
}

/** Optional Dhizuku Device Owner enhancement row shown beneath Shizuku access selection. */
@Composable
private fun DhizukuEnhancementItem(
    enabled: Boolean?,
    status: DhizukuStatus,
    onEnabledChange: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onRefreshStatus: () -> Unit,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val shape = rememberAnimatedSegmentedShape(index = 1, count = 2, interactionSource = interactionSource)
    val checked = enabled == true
    val statusTextRes = if (!checked) {
        R.string.settings_dhizuku_enhancement_description
    } else {
        when (status) {
            DhizukuStatus.READY -> R.string.dhizuku_status_ready
            DhizukuStatus.PERMISSION_REQUIRED -> R.string.dhizuku_status_permission_required
            DhizukuStatus.INACTIVE -> R.string.dhizuku_status_inactive
            DhizukuStatus.MISSING -> R.string.dhizuku_status_missing
            DhizukuStatus.ERROR -> R.string.dhizuku_status_error
        }
    }
    val statusColor = when {
        !checked -> MaterialTheme.colorScheme.onSurfaceVariant
        status == DhizukuStatus.READY -> MaterialTheme.colorScheme.primary
        status == DhizukuStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape),
        shape = shape,
        color = segmentedContainerColor(),
    ) {
        Column {
            ListItem(
                modifier = Modifier.toggleable(
                    value = checked,
                    enabled = enabled != null,
                    role = Role.Switch,
                    interactionSource = interactionSource,
                    indication = ripple(),
                    onValueChange = onEnabledChange,
                ),
                headlineContent = {
                    Text(
                        stringResource(R.string.settings_dhizuku_enhancement),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                supportingContent = {
                    Text(
                        text = stringResource(statusTextRes),
                        color = statusColor,
                    )
                },
                leadingContent = {
                    Icon(painterResource(R.drawable.ic_settings_check), contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = checked,
                        enabled = enabled != null,
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
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            AnimatedVisibility(visible = checked && status != DhizukuStatus.READY) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 56.dp, end = 16.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpressiveButton(
                        label = status.actionLabel(),
                        size = ExpressiveButtonSize.SMALL,
                    ) {
                        performDhizukuStatusAction(
                            context = context,
                            status = status,
                            onRequestPermission = onRequestPermission,
                            onRefreshStatus = onRefreshStatus,
                        )
                    }
                }
            }
        }
    }
}

/** Places an Expressive primary section title above a Segmented List column (`2.dp` item gaps). */
@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 12.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(SegmentedListDefaults.Gap),
            content = content,
        )
    }
}

/** Renders one segmented settings row with Expressive press corner morphing. */
@Composable
internal fun SettingsItem(
    @DrawableRes icon: Int,
    title: String,
    index: Int = 0,
    count: Int = 1,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onClick: (() -> Unit)? = null,
) {
    val activeInteraction = interactionSource ?: remember { MutableInteractionSource() }
    val shape = rememberAnimatedSegmentedShape(
        index = index,
        count = count,
        interactionSource = if (onClick != null || interactionSource != null) activeInteraction else null,
    )
    val disabledColor = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (onClick == null) modifier
                else modifier.clickable(
                    interactionSource = activeInteraction,
                    indication = ripple(),
                    enabled = enabled,
                    onClick = onClick,
                ),
            ),
        shape = shape,
        color = segmentedContainerColor(),
    ) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
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
}

/** Displays a segmented link row to a separate settings page or project resource. */
@Composable
internal fun SettingsLink(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes description: Int,
    index: Int = 0,
    count: Int = 1,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingsItem(
        icon = icon,
        title = stringResource(title),
        index = index,
        count = count,
        supportingContent = { Text(stringResource(description)) },
        enabled = enabled,
        onClick = onClick,
    )
}

/** Lets the user expand and select the app's color palette with tri-tone Expressive swatches. */
@Composable
internal fun ThemeSettings(
    selectedPalette: ThemePalette,
    onSelect: (ThemePalette) -> Unit,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val swatchScroll = rememberScrollState()
    val motion = MaterialTheme.motionScheme
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = motion.fastSpatialSpec(),
        label = "themeChevronRotation",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SegmentedListDefaults.OuterCorner),
        color = segmentedContainerColor(),
    ) {
        Column {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(stringResource(selectedPalette.labelRes())) },
                leadingContent = { Icon(painterResource(R.drawable.ic_settings_palette), contentDescription = null) },
                trailingContent = {
                    Icon(
                        painterResource(R.drawable.ic_settings_expand),
                        contentDescription = null,
                        modifier = Modifier.rotate(chevronRotation),
                    )
                },
                modifier = Modifier.clickable { expanded = !expanded },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = motion.defaultSpatialSpec()) +
                    fadeIn(animationSpec = motion.fastEffectsSpec()),
                exit = shrinkVertically(animationSpec = motion.fastSpatialSpec()) +
                    fadeOut(animationSpec = motion.fastEffectsSpec()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup()
                        .horizontalScroll(swatchScroll)
                        .padding(start = 12.dp, end = 12.dp, bottom = 14.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ThemePalette.entries.forEach { palette ->
                        ThemeSwatch(palette, selected = palette == selectedPalette, onClick = { onSelect(palette) })
                    }
                }
            }
        }
    }
}

/**
 * Displays a Material 3 Expressive tri-tone color sample (primary / primaryContainer / tertiaryContainer)
 * that morphs to a Cookie4Sided badge when selected.
 */
@Composable
private fun ThemeSwatch(palette: ThemePalette, selected: Boolean, onClick: () -> Unit) {
    val (primary, primaryContainer, tertiaryContainer) = palette.previewColors()
    val swatchShape: Shape = if (selected) MaterialShapes.Cookie4Sided.toShape() else CircleShape
    Column(
        modifier = Modifier
            .widthIn(min = 60.dp)
            .clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(swatchShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val halfH = size.height / 2f
                val halfW = size.width / 2f
                drawRect(color = primary, topLeft = Offset.Zero, size = Size(size.width, halfH))
                drawRect(color = primaryContainer, topLeft = Offset(0f, halfH), size = Size(halfW, halfH))
                drawRect(color = tertiaryContainer, topLeft = Offset(halfW, halfH), size = Size(halfW, halfH))
            }
            if (selected) {
                Surface(
                    modifier = Modifier.size(22.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_settings_check),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(palette.labelRes()),
            style = if (selected) MaterialTheme.typography.labelMediumEmphasized else MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                index = 0,
                count = 1,
            )
        } else {
            val users = snapshot.users
            users.forEachIndexed { index, user ->
                val checked = user.id in state.selectedUserIds
                SpaceRow(
                    user = user,
                    index = index,
                    count = users.size,
                    checked = checked,
                    current = user.id == snapshot.currentUserId,
                    enabled = state.activity == CleanerActivity.IDLE && (user.serialNumber != null || checked),
                    onClick = { onToggleSpace(user.id) },
                )
            }
        }
    }
}

/** Shows one discovered space and its inclusion in the current scope. */
@Composable
private fun SpaceRow(
    user: AndroidUser,
    index: Int,
    count: Int,
    checked: Boolean,
    current: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    SettingsItem(
        icon = R.drawable.ic_settings_space,
        title = user.displayName(),
        index = index,
        count = count,
        interactionSource = interactionSource,
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Checkbox,
            interactionSource = interactionSource,
            indication = ripple(),
            onValueChange = { onClick() },
        ),
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

/** Shows app version and links to project resources in a 3-item Segmented List. */
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
            index = 0,
            count = 3,
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
            index = 1,
            count = 3,
            onClick = onOpenProject,
        )
        SettingsLink(
            icon = R.drawable.ic_settings_directory,
            title = R.string.package_directory_title,
            description = R.string.settings_directory_description,
            index = 2,
            count = 3,
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
