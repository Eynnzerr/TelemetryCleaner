package com.eynnzerr.telemetrycleaner.data.packagemanager

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.MainThread
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeConnection
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Owns the process-wide privilege backend lifecycle (Shizuku Binder or Root shell)
 * and serializes package-service operations across the UI and foreground service.
 */
internal class PrivilegeSessionManager(
    context: Context,
    private val settings: CleanerSettings,
) {
    private val appContext = context.applicationContext

    /** Serializes scans, changes, and backend switches across the screen and service. */
    private val packageMutex = Mutex()

    /** Orders backend switching and Root connection attempts. */
    private val backendMutex = Mutex()

    /** Keeps a foreground-authorized session alive across screen and service recreation. */
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The process-local Root session, replaced while holding [packageMutex]. */
    private var rootExecutor: RootPackageExecutor? = null

    /** Prevents Shizuku callbacks from publishing a connection before loading the saved backend. */
    private var selectionLoaded = false

    /** Holds the current choice and authorization state as one snapshot. */
    private val mutableConnection = MutableStateFlow(
        PrivilegeConnection(PrivilegeBackend.SHIZUKU, PrivilegeStatus.CONNECTING),
    )

    /** The selected backend and its current authorization state. */
    val connection: StateFlow<PrivilegeConnection> = mutableConnection.asStateFlow()

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

    /** Resolves one ready backend under the operation lock and holds it for the whole operation. */
    suspend fun <T> withPackageCommands(
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

    /** Reports whether the Shizuku binder is currently reachable. */
    fun isShizukuBinderAlive(): Boolean = Shizuku.pingBinder()

    /** Returns the effective UID for diagnostics on the active privilege backend. */
    fun currentUid(backend: PrivilegeBackend): Int? = when (backend) {
        PrivilegeBackend.SHIZUKU -> runCatching { Shizuku.getUid().takeIf { it >= 0 } }.getOrNull()
        PrivilegeBackend.ROOT -> 0
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
        const val TAG = "PrivilegeSessionManager"

        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val PERMISSION_REQUEST = 31
    }
}
