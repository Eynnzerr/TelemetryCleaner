package com.eynnzerr.telemetrycleaner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerSettings
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerIntent
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerUiEvent
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerViewModel
import com.eynnzerr.telemetrycleaner.feature.history.AutoCleanupHistoryViewModel
import com.eynnzerr.telemetrycleaner.feature.packages.PackageDirectoryEvent
import com.eynnzerr.telemetrycleaner.feature.packages.PackageDirectoryViewModel
import com.eynnzerr.telemetrycleaner.feature.settings.SettingsEvent
import com.eynnzerr.telemetrycleaner.feature.settings.SettingsViewModel
import com.eynnzerr.telemetrycleaner.service.AutoCleanupService
import com.eynnzerr.telemetrycleaner.ui.cleaner.CleanerScreen
import com.eynnzerr.telemetrycleaner.ui.history.AutoCleanupHistoryScreen
import com.eynnzerr.telemetrycleaner.ui.packages.PackageDirectoryScreen
import com.eynnzerr.telemetrycleaner.ui.settings.ReleaseCheckDialog
import com.eynnzerr.telemetrycleaner.ui.settings.SettingsScreen
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/** The repository used for project links and on-demand release checks. */
private const val GITHUB_REPOSITORY = "Eynnzerr/TelemetryCleaner"

/** The project's homepage on GitHub. */
private const val GITHUB_PROJECT_URL = "https://github.com/$GITHUB_REPOSITORY"

/** Serializable Navigation 3 route keys persisted safely across process and configuration changes. */
@Serializable
internal sealed interface Destination : NavKey {
    @Serializable
    data object Cleaner : Destination

    @Serializable
    data object Settings : Destination

    @Serializable
    data object AutoCleanupHistory : Destination

    @Serializable
    data object PackageDirectory : Destination
}

/** Slides both pages in the same direction using a shared timing curve. */
private fun AnimatedContentTransitionScope<*>.pageSlide(
    direction: SlideDirection,
    easing: Easing = FastOutSlowInEasing,
): ContentTransform {
    val animation = tween<IntOffset>(durationMillis = 280, easing = easing)
    return slideIntoContainer(direction, animation) togetherWith slideOutOfContainer(direction, animation)
}

/** Hosts the navigation display and bridges Android platform capabilities (permissions, service, intents). */
class MainActivity : ComponentActivity() {
    private val cleanerViewModel: CleanerViewModel by viewModel()
    private val directoryViewModel: PackageDirectoryViewModel by viewModel()
    private val settingsViewModel: SettingsViewModel by viewModel()
    private val historyViewModel: AutoCleanupHistoryViewModel by viewModel()

    /** Used only to coordinate foreground service startup from lifecycle and permission callbacks. */
    private val settings: CleanerSettings by inject()

    /** Requests the notification drawer entry when the user turns on auto cleanup. */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lifecycleScope.launch {
            AutoCleanupService.startIfEnabled(this@MainActivity, settings, refreshNotification = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeViewModelEvents()
        enableEdgeToEdge()
        setContent {
            val cleanerState = cleanerViewModel.state.collectAsStateWithLifecycle()
            val settingsState = settingsViewModel.state.collectAsStateWithLifecycle()
            val directoryState = directoryViewModel.state.collectAsStateWithLifecycle()
            val historyState = historyViewModel.history.collectAsStateWithLifecycle()

            TelemetryCleanerTheme(palette = settingsState.value.themePalette) {
                val backStack = rememberNavBackStack(Destination.Cleaner)
                val goBack: () -> Unit = {
                    if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                }
                val navigateIfAt: (expected: Destination, target: Destination) -> Unit = { expected, target ->
                    if (backStack.lastOrNull() == expected) backStack.add(target)
                }

                NavDisplay(
                    backStack = backStack,
                    onBack = goBack,
                    transitionSpec = { pageSlide(SlideDirection.Left) },
                    popTransitionSpec = { pageSlide(SlideDirection.Right) },
                    predictivePopTransitionSpec = { swipeEdge ->
                        val direction = if (swipeEdge == NavigationEvent.EDGE_RIGHT) {
                            SlideDirection.Left
                        } else {
                            SlideDirection.Right
                        }
                        pageSlide(direction, easing = LinearEasing)
                    },
                    entryProvider = entryProvider {
                        entry<Destination.Cleaner> {
                            CleanerScreen(
                                state = cleanerState.value,
                                onIntent = cleanerViewModel::dispatch,
                                onOpenSettings = { navigateIfAt(Destination.Cleaner, Destination.Settings) },
                                customPackageNames = settingsState.value.customPackageNames,
                                onRemoveCustomPackage = cleanerViewModel::removeCustomPackage,
                            )
                        }
                        entry<Destination.Settings> {
                            val currentSettings = settingsState.value
                            SettingsScreen(
                                state = cleanerState.value,
                                onSelectBackend = { cleanerViewModel.dispatch(CleanerIntent.SelectBackend(it)) },
                                autoCleanupEnabled = currentSettings.autoCleanupEnabled,
                                autoCleanupStatus = currentSettings.autoCleanupStatus,
                                autoCleanupIntervalMinutes = currentSettings.autoCleanupIntervalMinutes,
                                onAutoCleanupChange = settingsViewModel::setAutoCleanupEnabled,
                                onAutoCleanupIntervalChange = settingsViewModel::setAutoCleanupInterval,
                                disableOnUninstallFailure = currentSettings.disableOnUninstallFailure,
                                onDisableOnUninstallFailureChange = settingsViewModel::setDisableOnUninstallFailure,
                                onToggleSpace = { cleanerViewModel.dispatch(CleanerIntent.ToggleSpace(it)) },
                                onOpenAutoCleanupHistory = {
                                    navigateIfAt(Destination.Settings, Destination.AutoCleanupHistory)
                                },
                                onOpenPackageDirectory = {
                                    navigateIfAt(Destination.Settings, Destination.PackageDirectory)
                                },
                                appVersion = BuildConfig.VERSION_NAME,
                                themePalette = currentSettings.themePalette,
                                onThemePaletteChange = settingsViewModel::setThemePalette,
                                checkingForUpdate = currentSettings.checkingForUpdate,
                                onCheckForUpdate = {
                                    settingsViewModel.checkForUpdate(GITHUB_REPOSITORY, BuildConfig.VERSION_NAME)
                                },
                                onOpenProject = { openGitHub(GITHUB_PROJECT_URL) },
                                onBack = goBack,
                            )
                            currentSettings.releaseResult?.let { result ->
                                ReleaseCheckDialog(
                                    result = result,
                                    currentVersion = BuildConfig.VERSION_NAME,
                                    onOpenRelease = { url ->
                                        settingsViewModel.dismissReleaseDialog()
                                        openGitHub(url)
                                    },
                                    onOpenReleases = {
                                        settingsViewModel.dismissReleaseDialog()
                                        openGitHub("$GITHUB_PROJECT_URL/releases")
                                    },
                                    onDismiss = settingsViewModel::dismissReleaseDialog,
                                )
                            }
                        }
                        entry<Destination.AutoCleanupHistory> {
                            AutoCleanupHistoryScreen(historyState.value, onBack = goBack)
                        }
                        entry<Destination.PackageDirectory> {
                            PackageDirectoryScreen(
                                state = directoryState.value,
                                onOpenAddDialog = directoryViewModel::openAddDialog,
                                onDismissAddDialog = directoryViewModel::dismissAddDialog,
                                onInputChange = directoryViewModel::onInputChange,
                                onSubmitAdd = directoryViewModel::submitAdd,
                                onRemoveCustomPackage = directoryViewModel::removeCustomPackage,
                                onOpenIssues = { openGitHub("$GITHUB_PROJECT_URL/issues") },
                                onBack = goBack,
                            )
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { AutoCleanupService.startIfEnabled(this@MainActivity, settings) }
        cleanerViewModel.dispatch(CleanerIntent.Refresh)
    }

    /** Routes one-shot ViewModel side effects (toasts, foreground service control) to Android APIs. */
    private fun observeViewModelEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    cleanerViewModel.events.collect { event ->
                        when (event) {
                            CleanerUiEvent.SaveFailed -> showSaveFailedToast()
                        }
                    }
                }
                launch {
                    directoryViewModel.events.collect { event ->
                        when (event) {
                            PackageDirectoryEvent.SaveFailed -> showSaveFailedToast()
                        }
                    }
                }
                launch {
                    settingsViewModel.events.collect { event ->
                        when (event) {
                            SettingsEvent.SaveFailed -> showSaveFailedToast()
                            SettingsEvent.StartAutoCleanupService -> startAutoCleanupService()
                            SettingsEvent.StopAutoCleanupService -> {
                                stopService(Intent(this@MainActivity, AutoCleanupService::class.java))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showSaveFailedToast() {
        Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
    }

    private suspend fun startAutoCleanupService() {
        AutoCleanupService.startIfEnabled(this, settings)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Opens an HTTPS GitHub link in the user's browser. */
    private fun openGitHub(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_open_link_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
