package com.eynnzerr.telemetrycleaner.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupStatus
import com.eynnzerr.telemetrycleaner.data.preferences.ThemePalette
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerState
import com.eynnzerr.telemetrycleaner.ui.components.BackTopAppBar

/** Displays scan, cleanup, appearance, and project settings in grouped rows. */
@Composable
internal fun SettingsScreen(
    state: CleanerState,
    onSelectBackend: (PrivilegeBackend) -> Unit,
    autoCleanupEnabled: Boolean?,
    autoCleanupStatus: AutoCleanupStatus,
    autoCleanupIntervalMinutes: Int,
    onAutoCleanupChange: (Boolean) -> Unit,
    onAutoCleanupIntervalChange: (Int) -> Unit,
    disableOnUninstallFailure: Boolean?,
    onDisableOnUninstallFailureChange: (Boolean) -> Unit,
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
    Scaffold(topBar = { BackTopAppBar(R.string.settings, onBack) }) { padding ->
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
                PrivilegeBackendSettings(state, onSelectBackend)
                ScopeSettings(state, onToggleSpace)
                CleanupSettings(
                    disableOnUninstallFailure = disableOnUninstallFailure,
                    onDisableOnUninstallFailureChange = onDisableOnUninstallFailureChange,
                    enabled = autoCleanupEnabled,
                    status = autoCleanupStatus,
                    intervalMinutes = autoCleanupIntervalMinutes,
                    onEnabledChange = onAutoCleanupChange,
                    onIntervalChange = onAutoCleanupIntervalChange,
                    onOpenHistory = onOpenAutoCleanupHistory,
                )
                SettingsGroup(stringResource(R.string.settings_group_appearance)) {
                    ThemeSettings(themePalette, onThemePaletteChange)
                }
                AboutSettings(
                    appVersion = appVersion,
                    checkingForUpdate = checkingForUpdate,
                    onCheckForUpdate = onCheckForUpdate,
                    onOpenProject = onOpenProject,
                    onOpenPackageDirectory = onOpenPackageDirectory,
                )
            }
        }
    }
}
