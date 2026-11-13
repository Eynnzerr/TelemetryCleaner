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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import com.eynnzerr.telemetrycleaner.data.AutoCleanupHistory
import com.eynnzerr.telemetrycleaner.data.AutoCleanupHistoryStore
import com.eynnzerr.telemetrycleaner.data.AutoCleanupStatus
import com.eynnzerr.telemetrycleaner.data.CleanerSettings
import com.eynnzerr.telemetrycleaner.data.DEFAULT_AUTO_CLEANUP_INTERVAL_MINUTES
import com.eynnzerr.telemetrycleaner.data.ReleaseCheckResult
import com.eynnzerr.telemetrycleaner.data.ThemePalette
import com.eynnzerr.telemetrycleaner.data.checkLatestRelease
import com.eynnzerr.telemetrycleaner.feature.CleanerIntent
import com.eynnzerr.telemetrycleaner.feature.CleanerViewModel
import com.eynnzerr.telemetrycleaner.service.AutoCleanupService
import com.eynnzerr.telemetrycleaner.ui.AutoCleanupHistoryScreen
import com.eynnzerr.telemetrycleaner.ui.CleanerScreen
import com.eynnzerr.telemetrycleaner.ui.PackageDirectoryScreen
import com.eynnzerr.telemetrycleaner.ui.ReleaseCheckDialog
import com.eynnzerr.telemetrycleaner.ui.SettingsScreen
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/** The repository used for project links and on-demand release checks. */
private const val GITHUB_REPOSITORY = "Eynnzerr/TelemetryCleaner"

/** The project's homepage on GitHub. */
private const val GITHUB_PROJECT_URL = "https://github.com/$GITHUB_REPOSITORY"

/** Saveable route keys for the cleaner and its settings destinations. */
private enum class Destination { CLEANER, SETTINGS, AUTO_CLEANUP_HISTORY, PACKAGE_DIRECTORY }

/** Slides both pages in the same direction using a shared timing curve. */
private fun AnimatedContentTransitionScope<*>.pageSlide(
    direction: SlideDirection,
    easing: Easing = FastOutSlowInEasing,
): ContentTransform {
    val animation = tween<IntOffset>(durationMillis = 280, easing = easing)
    return slideIntoContainer(direction, animation) togetherWith slideOutOfContainer(direction, animation)
}

/** Hosts the carrier telemetry screen and reconnects it after external setup. */
class MainActivity : ComponentActivity() {
    /** Resolves the cleaner store in this activity's ViewModelStore. */
    private val cleanerViewModel: CleanerViewModel by viewModel()

    /** Shares the stored opt-in and scope with the foreground service. */
    private val settings: CleanerSettings by inject()

    /** Observes the check and removal results recorded by the foreground service. */
    private val cleanupHistory: AutoCleanupHistoryStore by inject()

    /** Requests the notification drawer entry when the user turns on auto cleanup. */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lifecycleScope.launch {
            AutoCleanupService.startIfEnabled(this@MainActivity, settings, refreshNotification = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state = cleanerViewModel.state.collectAsStateWithLifecycle()
            val preferences = settings.preferences.collectAsStateWithLifecycle(initialValue = null)
            val autoCleanupStatus = settings.autoCleanupStatus.collectAsStateWithLifecycle()
            val palette = preferences.value?.themePalette ?: ThemePalette.SYSTEM
            TelemetryCleanerTheme(palette = palette) {
                val backStack = rememberSaveable { mutableStateListOf(Destination.CLEANER) }
                val goBack: () -> Unit = {
                    if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
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
                    entryProvider = { destination ->
                        when (destination) {
                            Destination.CLEANER -> NavEntry(destination) {
                                CleanerScreen(
                                    state = state.value,
                                    onIntent = cleanerViewModel::dispatch,
                                    onOpenSettings = {
                                        if (backStack.last() == Destination.CLEANER) {
                                            backStack.add(Destination.SETTINGS)
                                        }
                                    },
                                )
                            }
                            Destination.SETTINGS -> NavEntry(destination) {
                                val savedPreferences = preferences.value
                                val updateScope = rememberCoroutineScope()
                                var checkingForUpdate by remember { mutableStateOf(false) }
                                var releaseResult by remember { mutableStateOf<ReleaseCheckResult?>(null) }
                                SettingsScreen(
                                    state = state.value,
                                    autoCleanupEnabled = savedPreferences?.autoCleanupEnabled,
                                    autoCleanupStatus = autoCleanupStatus.value,
                                    autoCleanupIntervalMinutes = savedPreferences?.autoCleanupIntervalMinutes
                                        ?: DEFAULT_AUTO_CLEANUP_INTERVAL_MINUTES,
                                    onAutoCleanupChange = ::setAutoCleanupEnabled,
                                    onAutoCleanupIntervalChange = ::setAutoCleanupInterval,
                                    onToggleSpace = { cleanerViewModel.dispatch(CleanerIntent.ToggleSpace(it)) },
                                    onOpenAutoCleanupHistory = {
                                        if (backStack.last() == Destination.SETTINGS) {
                                            backStack.add(Destination.AUTO_CLEANUP_HISTORY)
                                        }
                                    },
                                    onOpenPackageDirectory = {
                                        if (backStack.last() == Destination.SETTINGS) {
                                            backStack.add(Destination.PACKAGE_DIRECTORY)
                                        }
                                    },
                                    appVersion = BuildConfig.VERSION_NAME,
                                    themePalette = savedPreferences?.themePalette ?: ThemePalette.SYSTEM,
                                    onThemePaletteChange = ::setThemePalette,
                                    checkingForUpdate = checkingForUpdate,
                                    onCheckForUpdate = {
                                        if (!checkingForUpdate) {
                                            checkingForUpdate = true
                                            updateScope.launch {
                                                try {
                                                    releaseResult = checkLatestRelease(
                                                        GITHUB_REPOSITORY,
                                                        BuildConfig.VERSION_NAME,
                                                    )
                                                } finally {
                                                    checkingForUpdate = false
                                                }
                                            }
                                        }
                                    },
                                    onOpenProject = { openGitHub(GITHUB_PROJECT_URL) },
                                    onBack = goBack,
                                )
                                releaseResult?.let { result ->
                                    ReleaseCheckDialog(
                                        result = result,
                                        currentVersion = BuildConfig.VERSION_NAME,
                                        onOpenRelease = { url ->
                                            releaseResult = null
                                            openGitHub(url)
                                        },
                                        onOpenReleases = {
                                            releaseResult = null
                                            openGitHub("$GITHUB_PROJECT_URL/releases")
                                        },
                                        onDismiss = { releaseResult = null },
                                    )
                                }
                            }
                            Destination.AUTO_CLEANUP_HISTORY -> NavEntry(destination) {
                                val history = cleanupHistory.history.collectAsStateWithLifecycle(AutoCleanupHistory())
                                AutoCleanupHistoryScreen(history.value, onBack = goBack)
                            }
                            Destination.PACKAGE_DIRECTORY -> NavEntry(destination) {
                                PackageDirectoryScreen(
                                    onBack = goBack,
                                    onOpenIssues = { openGitHub("$GITHUB_PROJECT_URL/issues") },
                                )
                            }
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

    /** Saves the interval used for subsequent automatic cleanup checks. */
    private fun setAutoCleanupInterval(minutes: Int) {
        lifecycleScope.launch {
            if (!settings.setAutoCleanupInterval(minutes)) {
                Toast.makeText(this@MainActivity, R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Saves the selected accent palette for all screens. */
    private fun setThemePalette(palette: ThemePalette) {
        lifecycleScope.launch {
            if (!settings.setThemePalette(palette)) {
                Toast.makeText(this@MainActivity, R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
            }
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

    /** Persists the switch and applies it while the activity can start a foreground service. */
    private fun setAutoCleanupEnabled(enabled: Boolean) {
        lifecycleScope.launch {
            if (!settings.setAutoCleanupEnabled(enabled)) return@launch
            if (enabled) {
                AutoCleanupService.startIfEnabled(this@MainActivity, settings)
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                settings.setAutoCleanupStatus(AutoCleanupStatus.OFF)
                stopService(Intent(this@MainActivity, AutoCleanupService::class.java))
            }
        }
    }
}
