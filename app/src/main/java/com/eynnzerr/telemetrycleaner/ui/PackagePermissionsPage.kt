package com.eynnzerr.telemetrycleaner.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

/** Lists Android-recorded permission requests and shows grants for the chosen space. */
@Composable
internal fun PackagePermissionsPage(result: DetectedPackage, users: List<AndroidUser>, currentUserId: Int) {
    val permissions = result.permissions
    if (permissions.isNullOrEmpty()) {
        Text(
            stringResource(if (permissions == null) R.string.details_permissions_unavailable else R.string.details_permissions_empty),
            modifier = Modifier.padding(top = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
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
        contentPadding = PaddingValues(top = 14.dp, bottom = 4.dp),
    ) {
        item {
            Text(
                stringResource(R.string.details_permissions_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selected != null) {
                PermissionSpacePicker(users, selected, onSelect = { selectedUserId = it })
            }
        }
        items(sorted, key = { it.name }) { permission ->
            PermissionRow(permission, selected?.id, packageState)
        }
    }
}

/** Changes the space shown in this tab without changing the cleaner's scan scope. */
@Composable
private fun PermissionSpacePicker(users: List<AndroidUser>, selected: AndroidUser, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.details_permission_space, selected.displayName())
    if (users.size == 1) {
        Text(label, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
    } else {
        Box(Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { expanded = true }) {
                Text(label)
                Icon(
                    painterResource(R.drawable.ic_settings_expand),
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp).size(18.dp),
                )
            }
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

/** Displays a permission's exact identifier, protection type, and package-manager grant. */
@Composable
private fun PermissionRow(permission: ComponentPermission, userId: Int?, state: PackageState?) {
    val emphasized = permission.protection == PermissionProtection.DANGEROUS ||
        permission.protection == PermissionProtection.PRIVILEGED
    val granted = userId?.let(permission.grants::get)
    val grant = when {
        state == PackageState.RESTORABLE || state == PackageState.NOT_FOUND -> R.string.details_permission_uninstalled
        granted == true -> R.string.details_permission_granted
        granted == false -> R.string.details_permission_denied
        else -> R.string.status_unknown
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        permission.label?.takeUnless { it == permission.name }?.let { label ->
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
        Text(permission.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(
            stringResource(R.string.status_in_space, stringResource(permission.protection.label()), stringResource(grant)),
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
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
