package com.eynnzerr.telemetrycleaner.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.annotation.MainThread
import com.eynnzerr.telemetrycleaner.BuildConfig
import com.eynnzerr.telemetrycleaner.data.packagemanager.DhizukuBridge
import com.eynnzerr.telemetrycleaner.data.packagemanager.PackageCommands
import com.eynnzerr.telemetrycleaner.data.packagemanager.PackageFallback
import com.eynnzerr.telemetrycleaner.data.packagemanager.PackageFallbackResult
import com.eynnzerr.telemetrycleaner.data.packagemanager.PackageLookupResult
import com.eynnzerr.telemetrycleaner.data.packagemanager.PrivilegeSessionManager
import com.eynnzerr.telemetrycleaner.data.packagemanager.toLookupResult
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupHistoryStore
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupRemoval
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Results of one background check against a fresh package-manager snapshot.
 *
 * @property hasSpaces Whether any opted-in spaces were available.
 * @property outcomes Results of the attempted removals.
 * @property hasUnverifiedPackages Whether some observed states could not be confirmed.
 */
internal data class AutoCleanupResult(
    val hasSpaces: Boolean,
    val outcomes: List<ChangeOutcome>,
    val hasUnverifiedPackages: Boolean = false,
)

/** Signals a persisted change to the user's custom package directory. */
internal sealed interface CustomPackageChange {
    data class Added(val packageName: String) : CustomPackageChange
    data class Removed(val packageName: String) : CustomPackageChange
}

/**
 * Persists a custom-directory membership edit under [mutex] so waiting for the lock remains
 * cancellable, while the write and change-event emission complete atomically once acquired.
 */
internal suspend fun commitCustomPackageChange(
    mutex: Mutex,
    packageName: String,
    included: Boolean,
    persist: suspend (String, Boolean) -> Boolean,
    emitChange: suspend (CustomPackageChange) -> Unit,
): Boolean {
    val blocked = included && (packageName in PackageDirectory.variants || packageName in CriticalSystemPackages.names)
    if (blocked || packageName in PackageDirectory.variants) return false
    return mutex.withLock {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val saved = persist(packageName, included)
            if (saved) {
                val change = if (included) {
                    CustomPackageChange.Added(packageName)
                } else {
                    CustomPackageChange.Removed(packageName)
                }
                emitChange(change)
            }
            saved
        }
    }
}

/** Coordinates package queries, manual mutations, custom directory edits, and background cleanup. */
class CleanerRepository internal constructor(
    context: Context,
    private val settings: CleanerSettings,
    private val sessionManager: PrivilegeSessionManager,
    private val dhizukuBridge: DhizukuBridge,
) {
    private val appContext = context.applicationContext

    /** Resolves stable Android profile identities independently of the package dump. */
    private val userManager = appContext.getSystemService(UserManager::class.java)

    /** Serializes custom list edits with confirmed manual package changes. */
    private val customPackagesMutex = Mutex()

    /** The APK that app_process loads for the privileged uninstall fallback. */
    private val fallbackApkPath: String? by lazy {
        try {
            appContext.packageManager.getApplicationInfo(appContext.packageName, 0).sourceDir
        } catch (_: Exception) {
            // The fallback is best-effort; an unreadable path must not mask the system error.
            null
        }
    }

    /** The selected backend and its current authorization state. */
    val connection: StateFlow<PrivilegeConnection> get() = sessionManager.connection

    /** Shares background mutations with the screen. */
    private val automaticChangesFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Signals that background cleanup may have changed the screen's snapshot. */
    internal val automaticChanges: SharedFlow<Unit> = automaticChangesFlow.asSharedFlow()

    /** Shares custom directory edits across ViewModels so the cleaner stays synchronized. */
    private val customPackageChangesFlow = MutableSharedFlow<CustomPackageChange>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** Emits after a custom package addition or removal is persisted. */
    internal val customPackageChanges: SharedFlow<CustomPackageChange> = customPackageChangesFlow.asSharedFlow()

    /** Rechecks Shizuku or the existing Root session without requesting authorization. */
    @MainThread
    fun refreshConnection() = sessionManager.refreshConnection()

    /** Saves the foreground user's choice and opens Root when selected. */
    suspend fun selectBackend(backend: PrivilegeBackend): Boolean = sessionManager.selectBackend(backend)

    /** Starts the selected authorization flow from the foreground. */
    suspend fun requestPermission() = sessionManager.requestPermission()

    /** Reads active package records for the app's Android user and other visible spaces. */
    suspend fun scan(): ScanSnapshot = sessionManager.withPackageCommands { commands, _ ->
        val manager = appContext.packageManager
        val customEntries = settings.readPreferences().customPackageNames.sorted().map(::customEntry)
        scanPackages(commands, PackageDirectory.variants.values + customEntries, manager)
    }

    /** Verifies whether an exact package name exists in an actionable state on the device. */
    internal suspend fun lookupPackage(packageName: String): PackageEntry? = sessionManager.withPackageCommands { commands, _ ->
        require(isExactPackageName(packageName))
        when (commands.read(packageName).toLookupResult()) {
            is PackageLookupResult.Found -> PackageDirectory.variants[packageName] ?: customEntry(packageName)
            PackageLookupResult.Missing -> null
            PackageLookupResult.Unavailable -> error("Could not verify $packageName")
        }
    }

    /** Changes directory membership atomically once the mutex is acquired so committed writes always emit refresh events. */
    suspend fun setCustomPackage(packageName: String, included: Boolean): Boolean = commitCustomPackageChange(
        mutex = customPackagesMutex,
        packageName = packageName,
        included = included,
        persist = settings::setCustomPackage,
        emitChange = customPackageChangesFlow::emit,
    )

    /** Resolves an optional application label for a package already identified by the privileged service. */
    private fun customEntry(packageName: String): PackageEntry {
        val info = try {
            appContext.packageManager.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
        return customEntry(packageName, info)
    }

    /** Other Android users' packages may be visible to the privileged service only by name. */
    private fun customEntry(packageName: String, info: ApplicationInfo?): PackageEntry {
        val label = info?.loadLabel(appContext.packageManager)?.toString()?.takeIf(String::isNotBlank)
        return PackageEntry(packageName, label ?: packageName, emptySet())
    }

    /** Applies the confirmed manual actions serially. */
    suspend fun change(
        targets: List<ChangeTarget>,
        action: PackageAction,
        expectedBackend: PrivilegeBackend? = null,
    ): List<ChangeOutcome> =
        sessionManager.withPackageCommands { commands, backend ->
            check(expectedBackend == null || expectedBackend == backend)
            customPackagesMutex.withLock {
                val preferences = settings.readPreferences()
                val managedNames = PackageDirectory.variants.keys + preferences.customPackageNames
                targets.distinct().map { target ->
                    currentCoroutineContext().ensureActive()
                    if (target.packageName !in managedNames) {
                        return@map ChangeOutcome(target, false, ChangeFailure.NotManaged)
                    }
                    if (action != PackageAction.RESTORE && target.packageName in CriticalSystemPackages.names) {
                        return@map ChangeOutcome(target, false, ChangeFailure.ProtectedSystemPackage)
                    }
                    val outcome = changePackage(
                        commands = commands,
                        backend = backend,
                        target = target,
                        action = action,
                        requireVerifiedSystemIdentity = false,
                        dhizukuFallbackEnabled = preferences.dhizukuFallbackEnabled,
                        submitDeniedFailure = ChangeFailure.SpaceStateUnverified,
                        canSubmit = {
                            val expectedSerial = target.userSerialNumber
                            expectedSerial == null || userSerialNumber(target.userId) == expectedSerial
                        },
                    )
                    val denialLog = outcome.denialLog
                    if (denialLog == null) {
                        outcome
                    } else {
                        outcome.copy(
                            denialLog = denialContext(target, backend, preferences.dhizukuFallbackEnabled) + denialLog,
                        )
                    }
                }
            }
        }

    /** Scans opted-in spaces and records each background removal result as it becomes known. */
    internal suspend fun autoCleanup(
        history: AutoCleanupHistoryStore,
        expectedBackend: PrivilegeBackend,
    ): AutoCleanupResult =
        sessionManager.withPackageCommands { commands, backend ->
            check(backend == expectedBackend)
            val operationContext = currentCoroutineContext()
            val initialPreferences = settings.readPreferences()
            if (!initialPreferences.autoCleanupEnabled) {
                return@withPackageCommands AutoCleanupResult(hasSpaces = false, outcomes = emptyList())
            }
            val snapshot = scanPackages(commands, PackageDirectory.autoCleanupNames.map(PackageDirectory.variants::getValue))
            val users = snapshot.users.associateBy(AndroidUser::id)
            val spaces = initialPreferences.selectedUsers(snapshot)
            val targets = snapshot.targetsFor(
                action = PackageAction.UNINSTALL,
                selectedUserIds = spaces,
                includeDisabledForUninstall = false,
            )
            val hasUnverifiedPackages = snapshot.packages.any { detected ->
                spaces.any { detected.users[it] == PackageState.UNKNOWN }
            }
            var submittedChange = false
            try {
                val outcomes = targets.map { target ->
                    operationContext.ensureActive()
                    val user = users.getValue(target.userId)
                    var allowedToSubmit: Boolean? = null
                    val outcome = changePackage(
                        commands = commands,
                        backend = backend,
                        target = target,
                        action = PackageAction.UNINSTALL,
                        dhizukuFallbackEnabled = initialPreferences.dhizukuFallbackEnabled,
                    ) {
                        val serial = userSerialNumber(user.id)
                        operationContext.ensureActive()
                        val latest = settings.readPreferences()
                        operationContext.ensureActive()
                        val allowed = latest.autoCleanupEnabled &&
                            serial == user.serialNumber && latest.includes(user, snapshot.currentUserId)
                        allowedToSubmit = allowedToSubmit == true || allowed
                        if (allowed) submittedChange = true
                        allowed
                    }
                    // An already absent package needs no removal. A changed scope cancels submission.
                    if (allowedToSubmit == true || (allowedToSubmit == null && !outcome.success)) {
                        withContext(NonCancellable) {
                            history.recordRemoval(
                                AutoCleanupRemoval(System.currentTimeMillis(), target.packageName, user, outcome.success),
                            )
                        }
                    }
                    outcome
                }
                AutoCleanupResult(spaces.isNotEmpty(), outcomes, hasUnverifiedPackages)
            } finally {
                if (submittedChange) automaticChangesFlow.tryEmit(Unit)
            }
        }

    /** Reads a current snapshot while the caller holds the shared operation lock. */
    private suspend fun scanPackages(
        commands: PackageCommands,
        entries: Collection<PackageEntry>,
        permissionManager: PackageManager? = null,
    ): ScanSnapshot {
        // Android allocates a range of 100,000 UIDs to each user, including profiles.
        val snapshot = commands.scan(Process.myUid() / PER_USER_RANGE, entries, permissionManager)
        return snapshot.copy(users = snapshot.users.map { it.copy(serialNumber = userSerialNumber(it.id)) })
    }

    /** Returns null when the platform cannot identify this profile. */
    private fun userSerialNumber(userId: Int): Long? = try {
        val handle = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        userManager?.getSerialNumberForUser(handle)?.takeIf { it >= 0 }
    } catch (_: Exception) {
        null
    }

    /** Submits one verified change and converts platform failures into per-target outcomes. */
    private suspend fun changePackage(
        commands: PackageCommands,
        backend: PrivilegeBackend,
        target: ChangeTarget,
        action: PackageAction,
        requireVerifiedSystemIdentity: Boolean = true,
        dhizukuFallbackEnabled: Boolean = false,
        submitDeniedFailure: ChangeFailure? = null,
        canSubmit: suspend () -> Boolean = { true },
    ): ChangeOutcome = try {
        if (backend == PrivilegeBackend.SHIZUKU && !sessionManager.isShizukuBinderAlive()) {
            ChangeOutcome(target, false, ChangeFailure.ShizukuDisconnected)
        } else {
            val currentUserId = Process.myUid() / PER_USER_RANGE
            val isMainSpaceShizuku = backend == PrivilegeBackend.SHIZUKU && target.userId == currentUserId
            val allowDhizukuFallback = isMainSpaceShizuku && dhizukuFallbackEnabled
            val allowDhizukuUnhide = action == PackageAction.RESTORE &&
                isMainSpaceShizuku &&
                (dhizukuFallbackEnabled || dhizukuBridge.isReadyNow())
            commands.change(
                target = target,
                action = action,
                requireVerifiedSystemIdentity = requireVerifiedSystemIdentity,
                captureDenialLog = !requireVerifiedSystemIdentity && action == PackageAction.UNINSTALL,
                canSubmit = canSubmit,
                submitDeniedFailure = submitDeniedFailure,
                deleteThroughAppProcess = if (backend == PrivilegeBackend.SHIZUKU) ::uninstallThroughShizuku else null,
                deleteThroughDhizuku = if (allowDhizukuFallback) dhizukuBridge::uninstall else null,
                hideThroughDhizuku = if (allowDhizukuFallback) { pkg, uid ->
                    dhizukuBridge.setApplicationHidden(pkg, uid, hidden = true)
                } else {
                    null
                },
                unhideThroughDhizuku = if (allowDhizukuUnhide) { pkg, uid ->
                    dhizukuBridge.setApplicationHidden(pkg, uid, hidden = false)
                } else {
                    null
                },
                installExistingThroughDhizuku = if (allowDhizukuFallback) dhizukuBridge::installExistingPackage else null,
                allowShellUnhide = backend == PrivilegeBackend.ROOT,
            )
        }
    } catch (_: SecurityException) {
        ChangeOutcome(target, false, ChangeFailure.SpaceOperationDenied)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ChangeOutcome(target, false, ChangeFailure.StateUnverified)
    }

    /** Runs the existing shell UID uninstall fallback through Shizuku with an exact 32-bit version constraint. */
    private fun uninstallThroughShizuku(
        packageName: String,
        userId: Int,
        expectedVersionCode: Int,
    ): PackageFallbackResult =
        fallbackApkPath?.let { PackageFallback.uninstall(it, packageName, userId, expectedVersionCode) }
            ?: PackageFallbackResult(false, "Helper APK unavailable")

    /** Adds the device, caller and effective restriction needed to interpret a denial. */
    private fun denialContext(
        target: ChangeTarget,
        backend: PrivilegeBackend,
        dhizukuFallbackEnabled: Boolean,
    ): String = buildString {
        val handle = UserHandle.getUserHandleForUid(target.userId * PER_USER_RANGE)
        val noUninstallApps = runCatching {
            userManager?.getUserRestrictions(handle)?.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS)
        }.getOrNull()
        val uid = sessionManager.currentUid(backend)
        appendLine("TelemetryCleaner ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        appendLine("Build: ${Build.DISPLAY} (${Build.FINGERPRINT})")
        appendLine("Backend: ${backend.name}, UID: ${uid ?: "unknown"}")
        if (backend == PrivilegeBackend.SHIZUKU) {
            appendLine(
                "Dhizuku enhancement: enabled=$dhizukuFallbackEnabled, status=${dhizukuBridge.status.value}, " +
                    "owner=${dhizukuBridge.ownerPackageNameOrNull() ?: "none"}",
            )
        }
        appendLine("Effective no_uninstall_apps for user ${target.userId}: ${noUninstallApps ?: "unknown"}")
    }

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}
