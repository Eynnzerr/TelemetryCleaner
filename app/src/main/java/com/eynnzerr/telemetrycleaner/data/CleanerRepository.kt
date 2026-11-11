package com.eynnzerr.telemetrycleaner.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.annotation.MainThread
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** Tracks Shizuku authorization and executes the carrier catalogue's package operations. */
class CleanerRepository(context: Context) : Closeable {
    private val appContext = context.applicationContext

    /** Resolves stable Android profile identities independently of the package dump. */
    private val userManager = appContext.getSystemService(UserManager::class.java)
    private val mutableConnection = MutableStateFlow(ShizukuStatus.STOPPED)

    /** Whether the app can use the Shizuku connection. */
    val connection: StateFlow<ShizukuStatus> = mutableConnection.asStateFlow()

    /** Signals that background cleanup may have changed the screen's snapshot. */
    internal val automaticChanges: SharedFlow<Unit> = automaticChangesFlow.asSharedFlow()

    private var closed = false
    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshConnection() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshConnection() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == PERMISSION_REQUEST) refreshConnection()
    }

    init {
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        refreshConnection()
    }

    /** Rechecks service availability and authorization after external setup. */
    @MainThread
    fun refreshConnection() {
        if (closed) return
        mutableConnection.value = try {
            when {
                !Shizuku.pingBinder() -> unavailableStatus()
                Shizuku.isPreV11() -> ShizukuStatus.ERROR
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    ShizukuStatus.PERMISSION_REQUIRED
                else -> ShizukuStatus.READY
            }
        } catch (_: RuntimeException) {
            if (Shizuku.pingBinder()) ShizukuStatus.ERROR else unavailableStatus()
        }
    }

    /** Asks the user to authorize Telemetry Cleaner in Shizuku. */
    @MainThread
    fun requestPermission() {
        if (mutableConnection.value != ShizukuStatus.PERMISSION_REQUIRED) return
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST)
        } catch (_: RuntimeException) {
            refreshConnection()
        }
    }

    /** Reads active package records for the app's Android user and other visible spaces. */
    suspend fun scan(): ScanSnapshot = withContext(Dispatchers.IO) {
        packageMutex.withLock { scanPackages(PackageDirectory.variants.keys, appContext.packageManager) }
    }

    /** Applies the confirmed actions serially and reports each target independently. */
    suspend fun change(targets: List<ChangeTarget>, action: PackageAction): List<ChangeOutcome> =
        withContext(Dispatchers.IO) {
            packageMutex.withLock {
                targets.distinct().map { target ->
                    ensureActive()
                    changePackage(target, action)
                }
            }
        }

    /** Scans opted-in spaces and records each background removal result as it becomes known. */
    internal suspend fun autoCleanup(
        settings: CleanerSettings,
        history: AutoCleanupHistoryStore,
    ): AutoCleanupResult =
        withContext(Dispatchers.IO) {
            val operationContext = currentCoroutineContext()
            packageMutex.withLock {
                if (!settings.readPreferences().autoCleanupEnabled) {
                    return@withLock AutoCleanupResult(hasSpaces = false, outcomes = emptyList())
                }
                val snapshot = scanPackages(PackageDirectory.autoCleanupNames)
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
                        val outcome = changePackage(target, PackageAction.UNINSTALL) {
                            val serial = userSerialNumber(user.id)
                            operationContext.ensureActive()
                            val latest = settings.readPreferences()
                            operationContext.ensureActive()
                            val allowed = latest.autoCleanupEnabled &&
                                serial == user.serialNumber && latest.includes(user, snapshot.currentUserId)
                            allowedToSubmit = allowed
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
        }

    /** Reads a current snapshot while the caller holds the shared operation lock. */
    private suspend fun scanPackages(
        packageNames: Set<String>,
        permissionManager: PackageManager? = null,
    ): ScanSnapshot {
        check(Shizuku.pingBinder()) { "请先连接 Shizuku。" }
        // Android allocates a range of 100,000 UIDs to each user, including profiles.
        val snapshot = PackageCommands.scan(Process.myUid() / PER_USER_RANGE, packageNames, permissionManager)
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
        target: ChangeTarget,
        action: PackageAction,
        canSubmit: suspend () -> Boolean = { true },
    ): ChangeOutcome = try {
        if (Shizuku.pingBinder()) {
            PackageCommands.change(target, action, canSubmit)
        } else {
            ChangeOutcome(target, false, "Shizuku 已断开。")
        }
    } catch (_: SecurityException) {
        ChangeOutcome(target, false, "系统拒绝了对该空间的操作。")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ChangeOutcome(target, false, "无法确认组件状态。请刷新后重试。")
    }

    /** Unregisters listeners when the screen store is cleared. */
    @MainThread
    override fun close() {
        if (closed) return
        closed = true
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    private fun unavailableStatus(): ShizukuStatus =
        try {
            appContext.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
            ShizukuStatus.STOPPED
        } catch (_: PackageManager.NameNotFoundException) {
            ShizukuStatus.MISSING
        }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val PERMISSION_REQUEST = 31
        const val PER_USER_RANGE = 100_000

        /** Serializes scans and writes across the UI and service repository instances. */
        val packageMutex = Mutex()

        /** Shares background mutations with any active UI repository. */
        val automaticChangesFlow = MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }
}
