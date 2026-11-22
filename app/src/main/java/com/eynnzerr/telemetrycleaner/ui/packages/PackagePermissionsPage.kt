@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.packages

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.ComponentPermission
import com.eynnzerr.telemetrycleaner.data.DetectedPackage
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.data.PermissionProtection
import com.eynnzerr.telemetrycleaner.ui.components.SegmentedListDefaults
import com.eynnzerr.telemetrycleaner.ui.components.StatusBadge
import com.eynnzerr.telemetrycleaner.ui.components.StatusTone
import com.eynnzerr.telemetrycleaner.ui.components.displayName
import com.eynnzerr.telemetrycleaner.ui.components.segmentedItemShape

/** Lists Android-recorded permission requests in Expressive Segmented Cards with semantic risk badges. */
@Composable
internal fun PackagePermissionsPage(result: DetectedPackage, users: List<AndroidUser>, currentUserId: Int) {
    val permissions = result.permissions
    if (permissions.isNullOrEmpty()) {
        Text(
            stringResource(if (permissions == null) R.string.details_permissions_unavailable else R.string.details_permissions_empty),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    var selectedUserId by rememberSaveable(currentUserId) { mutableIntStateOf(currentUserId) }
    val selected = users.firstOrNull { it.id == selectedUserId } ?: users.firstOrNull()
    val packageState = selected?.let { result.users[it.id] }
    val sorted = remember(permissions) {
        permissions.sortedWith(compareBy<ComponentPermission> { it.protection.ordinal }.thenBy { it.name })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(SegmentedListDefaults.Gap),
    ) {
        item(key = "permissions_header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.details_permissions_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selected != null) {
                    PermissionSpacePicker(users, selected, onSelect = { selectedUserId = it })
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        itemsIndexed(sorted, key = { _, permission -> permission.name }) { index, permission ->
            PermissionRow(
                permission = permission,
                userId = selected?.id,
                state = packageState,
                shape = segmentedItemShape(index, sorted.size),
            )
        }
    }
}

/** Changes the space shown in this tab using an Expressive FilterChip without modifying scan scope. */
@Composable
private fun PermissionSpacePicker(users: List<AndroidUser>, selected: AndroidUser, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.details_permission_space, selected.displayName())
    if (users.size == 1) {
        StatusBadge(text = label, tone = StatusTone.SECONDARY)
    } else {
        Box {
            FilterChip(
                selected = true,
                onClick = { expanded = true },
                label = { Text(label) },
                trailingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_settings_expand),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                users.forEach { user ->
                    DropdownMenuItem(text = { Text(user.displayName()) }, onClick = {
                        expanded = false
                        onSelect(user.id)
                    })
                }
            }
        }
    }
}

/** Displays a permission's exact identifier, protection badge, and grant badge inside a segmented card. */
@Composable
private fun PermissionRow(
    permission: ComponentPermission,
    userId: Int?,
    state: PackageState?,
    shape: Shape,
) {
    val highRisk = permission.protection == PermissionProtection.DANGEROUS ||
        permission.protection == PermissionProtection.PRIVILEGED
    val granted = userId?.let(permission.grants::get)
    val (grantRes, grantTone) = when {
        state == PackageState.RESTORABLE || state == PackageState.NOT_FOUND ->
            R.string.details_permission_uninstalled to StatusTone.NEUTRAL
        granted == true ->
            R.string.details_permission_granted to if (highRisk) StatusTone.ERROR else StatusTone.PRIMARY
        granted == false ->
            R.string.details_permission_denied to StatusTone.NEUTRAL
        else ->
            R.string.status_unknown to StatusTone.NEUTRAL
    }
    val protectionTone = when (permission.protection) {
        PermissionProtection.DANGEROUS, PermissionProtection.PRIVILEGED -> StatusTone.ERROR
        PermissionProtection.SIGNATURE -> StatusTone.SECONDARY
        PermissionProtection.OTHER, PermissionProtection.UNKNOWN -> StatusTone.NEUTRAL
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            permission.label?.takeUnless { it == permission.name }?.let { label ->
                Text(label, style = MaterialTheme.typography.titleSmallEmphasized)
            }
            Text(
                text = permission.name,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusBadge(stringResource(permission.protection.label()), protectionTone)
                StatusBadge(stringResource(grantRes), grantTone)
            }
        }
    }
}

/** Returns the localized name for Android's reported protection type. */
@StringRes
private fun PermissionProtection.label(): Int = when (this) {
    PermissionProtection.DANGEROUS -> R.string.details_permission_dangerous
    PermissionProtection.PRIVILEGED -> R.string.details_permission_privileged
    PermissionProtection.SIGNATURE -> R.string.details_permission_signature
    PermissionProtection.OTHER -> R.string.details_permission_other
    PermissionProtection.UNKNOWN -> R.string.details_permission_unknown
}
