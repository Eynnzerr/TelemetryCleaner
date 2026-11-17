package com.eynnzerr.telemetrycleaner.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.annotation.MainThread
import com.eynnzerr.telemetrycleaner.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

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

/** Owns the app's shared privilege connection and executes the carrier catalogue's package operations. */
class CleanerRepository internal constructor(context: Context, private val settings: CleanerSettings) {
    private val appContext = context.applicationContext

    /** Resolves stable Android profile identities independently of the package dump. */
    private val userManager = appContext.getSystemService(UserManager::class.java)

    /** Serializes scans, changes and backend selection across the screen and service. */
    private val packageMutex = Mutex()

    /** Orders backend switching and Root connection attempts. */
    private val backendMutex = Mutex()

    /** Keeps a foreground-authorized session alive across screen and service recreation. */
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The process-local Root session, replaced while holding the package lock. */
    private var rootExecutor: RootPackageExecutor? = null

    /** Prevents Shizuku callbacks from publishing a connection before loading the saved backend. */
    private var selectionLoaded = false

    /** Holds the current choice and authorization state as one snapshot. */
    private val mutableConnection = MutableStateFlow(
        PrivilegeConnection(PrivilegeBackend.SHIZUKU, PrivilegeStatus.CONNECTING),
    )

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
    val connection: StateFlow<PrivilegeConnection> = mutableConnection.asStateFlow()

    /** Shares background mutations with the screen. */
    private val automaticChangesFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Signals that background cleanup may have changed the screen's snapshot. */
    internal val automaticChanges: SharedFlow<Unit> = automaticChangesFlow.asSharedFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshConnection() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshConnection() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == PERMISSION_REQUEST) refreshConnection()
    }

    /** Loads the persisted choice once without requesting Root from a service or boot receiver. */
    private val loadSelection = connectionScope.launch {
        val backend = settings.readPreferences().privilegeBackend
        selectionLoaded = true
        mutableConnection.value = disconnectedConnection(backend)
    }

    init {
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
    }

    /** Rechecks Shizuku or the existing Root session without requesting authorization. */
    @MainThread
    fun refreshConnection() {
        if (!selectionLoaded) return
        val current = connection.value
        when (current.backend) {
            PrivilegeBackend.SHIZUKU -> mutableConnection.value = current.copy(status = shizukuStatus())
            PrivilegeBackend.ROOT -> if (current.status == PrivilegeStatus.READY && rootExecutor?.isAlive() != true) {
                mutableConnection.compareAndSet(current, current.copy(status = PrivilegeStatus.STOPPED))
            }
        }
    }

    /** Saves the foreground user's choice and opens Root when selected. */
    suspend fun selectBackend(backend: PrivilegeBackend): Boolean = withContext(Dispatchers.Main.immediate) {
        loadSelection.join()
        backendMutex.withLock backendChange@{
            if (connection.value.backend != backend) {
                val saved = packageMutex.withLock {
                    withContext(NonCancellable) {
                        if (!settings.setPrivilegeBackend(backend)) return@withContext false
                        val previousRoot = rootExecutor
                        rootExecutor = null
                        mutableConnection.value = disconnectedConnection(backend)
                        previousRoot?.close()
                        true
                    }
                }
                if (!saved) return@backendChange false
            }
            if (backend == PrivilegeBackend.ROOT) connectRoot()
            true
        }
    }

    /** Starts the selected authorization flow from the foreground. */
    suspend fun requestPermission(): Unit = withContext(Dispatchers.Main.immediate) {
        loadSelection.join()
        val current = connection.value
        when (current.backend) {
            PrivilegeBackend.ROOT -> connectRoot()
            PrivilegeBackend.SHIZUKU -> if (current.status == PrivilegeStatus.PERMISSION_REQUIRED) {
                try {
                    Shizuku.requestPermission(PERMISSION_REQUEST)
                } catch (_: RuntimeException) {
                    refreshConnection()
                }
            }
        }
    }

    /** Opens one Root session outside the package lock so authorization cannot hold up an operation. */
    @MainThread
    private fun connectRoot() {
        val current = connection.value
        if (current.status == PrivilegeStatus.CONNECTING || current.status == PrivilegeStatus.READY) return
        val connecting = current.copy(status = PrivilegeStatus.CONNECTING)
        mutableConnection.value = connecting
        connectionScope.launch {
            backendMutex.withLock {
                if (connection.value != connecting) return@withLock
                val executor = withContext(Dispatchers.IO) {
                    try {
                        RootPackageExecutor.open()
                    } catch (failure: Exception) {
                        Log.w(TAG, "Unable to open a Root session", failure)
                        null
                    }
                }
                packageMutex.withLock {
                    rootExecutor?.close()
                    rootExecutor = executor
                    mutableConnection.value = connecting.copy(
                        status = if (executor == null) PrivilegeStatus.ERROR else PrivilegeStatus.READY,
                    )
                }
            }
        }
    }

    /** Reads active package records for the app's Android user and other visible spaces. */
    suspend fun scan(): ScanSnapshot = withPackageCommands { commands, _ ->
        scanPackages(commands, PackageDirectory.variants.keys, appContext.packageManager)
    }

    /** Applies the confirmed manual actions serially, optionally trying to disable retained packages. */
    suspend fun change(
        targets: List<ChangeTarget>,
        action: PackageAction,
        disableOnUninstallFailure: Boolean = true,
        expectedBackend: PrivilegeBackend? = null,
    ): List<ChangeOutcome> =
        withPackageCommands { commands, backend ->
            check(expectedBackend == null || expectedBackend == backend)
            targets.distinct().map { target ->
                currentCoroutineContext().ensureActive()
                val outcome = changePackage(
                    commands,
                    backend,
                    target,
                    action,
                    requireVerifiedSystemIdentity = false,
                    disableOnUninstallFailure = disableOnUninstallFailure,
                )
                val denialLog = outcome.denialLog
                if (denialLog == null) outcome else outcome.copy(denialLog = denialContext(target, backend) + denialLog)
            }
        }

    /** Scans opted-in spaces and records each background removal result as it becomes known. */
    internal suspend fun autoCleanup(
        history: AutoCleanupHistoryStore,
        expectedBackend: PrivilegeBackend,
    ): AutoCleanupResult =
        withPackageCommands { commands, backend ->
            check(backend == expectedBackend)
            val operationContext = currentCoroutineContext()
            if (!settings.readPreferences().autoCleanupEnabled) {
                return@withPackageCommands AutoCleanupResult(hasSpaces = false, outcomes = emptyList())
            }
            val snapshot = scanPackages(commands, PackageDirectory.autoCleanupNames)
            val users = snapshot.users.associateBy(AndroidUser::id)
            val spaces = settings.readPreferences().selectedUsers(snapshot)
            val targets = snapshot.targetsFor(PackageAction.UNINSTALL, spaces)
            val hasUnverifiedPackages = snapshot.packages.any { detected ->
                spaces.any { detected.users[it] == PackageState.UNKNOWN }
            }
            var submittedChange = false
            try {
                val outcomes = targets.map { target ->
                    operationContext.ensureActive()
                    val user = users.getValue(target.userId)
                    var allowedToSubmit: Boolean? = null
                    val outcome = changePackage(commands, backend, target, PackageAction.UNINSTALL) {
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

    /** Resolves one ready backend under the operation lock and holds it for the whole operation. */
    private suspend fun <T> withPackageCommands(
        operation: suspend (PackageCommands, PrivilegeBackend) -> T,
    ): T = withContext(Dispatchers.IO) {
        packageMutex.withLock {
            val current = connection.value
            val executor = when (current.backend) {
                PrivilegeBackend.SHIZUKU -> PackageBinder
                PrivilegeBackend.ROOT -> rootExecutor
            }
            try {
                check(current.status == PrivilegeStatus.READY && executor != null)
                operation(PackageCommands(executor), current.backend)
            } finally {
                if (current.status == PrivilegeStatus.READY && executor is RootPackageExecutor && !executor.isAlive()) {
                    mutableConnection.compareAndSet(current, current.copy(status = PrivilegeStatus.STOPPED))
                }
            }
        }
    }

    /** Reads a current snapshot while the caller holds the shared operation lock. */
    private suspend fun scanPackages(
        commands: PackageCommands,
        packageNames: Set<String>,
        permissionManager: PackageManager? = null,
    ): ScanSnapshot {
        // Android allocates a range of 100,000 UIDs to each user, including profiles.
        val snapshot = commands.scan(Process.myUid() / PER_USER_RANGE, packageNames, permissionManager)
        return snapshot.copy(users = snapshot.users.map { it.copy(serialNumber = userSerialNumber(it.id)) })
    }

    /** Returns null when the platform cannot identify this profile. */
    private fun userSerialNumber(userId: Int): Long? = try {
        val handle = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        userManager?.getSerialNumberForUser(handle)?.takeIf { it >= 0 }
    } catch (_: SecurityException) {
        null
    }

    /** Submits one verified change and converts platform failures into per-target outcomes. */
    private suspend fun changePackage(
        commands: PackageCommands,
        backend: PrivilegeBackend,
        target: ChangeTarget,
        action: PackageAction,
        requireVerifiedSystemIdentity: Boolean = true,
        disableOnUninstallFailure: Boolean = false,
        canSubmit: suspend () -> Boolean = { true },
    ): ChangeOutcome = try {
        if (backend == PrivilegeBackend.SHIZUKU && !Shizuku.pingBinder()) {
            ChangeOutcome(target, false, "Shizuku 已断开。")
        } else {
            commands.change(
                target = target,
                action = action,
                requireVerifiedSystemIdentity = requireVerifiedSystemIdentity,
                captureDenialLog = !requireVerifiedSystemIdentity && action == PackageAction.UNINSTALL,
                disableOnUninstallFailure = disableOnUninstallFailure,
                canSubmit = canSubmit,
                deleteThroughAppProcess = if (backend == PrivilegeBackend.SHIZUKU) ::uninstallThroughShizuku else null,
            )
        }
    } catch (_: SecurityException) {
        ChangeOutcome(target, false, "系统拒绝了对该空间的操作。")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ChangeOutcome(target, false, "无法确认组件状态。请刷新后重试。")
    }

    /** Runs the existing shell UID uninstall fallback through Shizuku. */
    private fun uninstallThroughShizuku(packageName: String, userId: Int): PackageFallbackResult =
        fallbackApkPath?.let { PackageFallback.uninstall(it, packageName, userId) }
            ?: PackageFallbackResult(false, "Helper APK unavailable")

    /** Adds the device, caller and effective restriction needed to interpret a denial. */
    private fun denialContext(target: ChangeTarget, backend: PrivilegeBackend): String = buildString {
        val handle = UserHandle.getUserHandleForUid(target.userId * PER_USER_RANGE)
        val noUninstallApps = runCatching {
            userManager?.getUserRestrictions(handle)?.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS)
        }.getOrNull()
        val uid = when (backend) {
            PrivilegeBackend.SHIZUKU -> runCatching { Shizuku.getUid().takeIf { it >= 0 } }.getOrNull()
            PrivilegeBackend.ROOT -> 0
        }
        appendLine("TelemetryCleaner ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        appendLine("Build: ${Build.DISPLAY} (${Build.FINGERPRINT})")
        appendLine("Backend: ${backend.name}, UID: ${uid ?: "unknown"}")
        appendLine("Effective no_uninstall_apps for user ${target.userId}: ${noUninstallApps ?: "unknown"}")
    }

    /** Restores the selected backend without opening a Root session. */
    private fun disconnectedConnection(backend: PrivilegeBackend): PrivilegeConnection =
        PrivilegeConnection(
            backend,
            if (backend == PrivilegeBackend.SHIZUKU) shizukuStatus() else PrivilegeStatus.STOPPED,
        )

    /** Resolves the current Shizuku connection and permission state. */
    private fun shizukuStatus(): PrivilegeStatus = try {
        when {
            !Shizuku.pingBinder() -> unavailableShizukuStatus()
            Shizuku.isPreV11() -> PrivilegeStatus.ERROR
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> PrivilegeStatus.PERMISSION_REQUIRED
            else -> PrivilegeStatus.READY
        }
    } catch (_: RuntimeException) {
        if (Shizuku.pingBinder()) PrivilegeStatus.ERROR else unavailableShizukuStatus()
    }

    /** Distinguishes an absent Shizuku installation from a stopped connection. */
    private fun unavailableShizukuStatus(): PrivilegeStatus =
        try {
            appContext.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
            PrivilegeStatus.STOPPED
        } catch (_: PackageManager.NameNotFoundException) {
            PrivilegeStatus.MISSING
        }

    private companion object {
        /** Identifies backend connection failures in logcat. */
        const val TAG = "CleanerRepository"

        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val PERMISSION_REQUEST = 31
        const val PER_USER_RANGE = 100_000
    }
}
