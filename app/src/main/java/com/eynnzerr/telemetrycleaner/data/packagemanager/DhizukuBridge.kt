package com.eynnzerr.telemetrycleaner.data.packagemanager

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.eynnzerr.telemetrycleaner.data.DhizukuStatus
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.coroutines.resume

/** Abstraction over Dhizuku status checks and authorization for ViewModel/UI testability. */
internal interface DhizukuController {
    val status: StateFlow<DhizukuStatus>
    suspend fun refreshStatus(): DhizukuStatus
    suspend fun requestPermission(): DhizukuStatus
}

/**
 * Receives silent uninstall results from [PackageInstaller] for requests issued via [DhizukuBridge].
 *
 * Registered in the manifest as `android:exported="false"` so [PendingIntent.FLAG_MUTABLE] uses an
 * explicit component on Android 14+ without accepting spoofed external broadcasts.
 */
class DhizukuUninstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
        if (requestId < 0) return
        DhizukuBridge.deliverUninstallCallback(requestId, intent)
    }

    internal companion object {
        const val EXTRA_REQUEST_ID = "com.eynnzerr.telemetrycleaner.extra.DHIZUKU_REQUEST_ID"
    }
}

/**
 * Optional Device Owner bridge backed by Dhizuku.
 *
 * Used strictly as an enhancement channel in Shizuku mode for the current Android user when
 * shell-UID (2000) package operations are blocked by OEM PMS restrictions. All state mutations
 * are still independently verified through PMS dumps by [PackageCommands].
 */
internal class DhizukuBridge(context: Context) : DhizukuController {
    private val appContext = context.applicationContext
    private val mutableStatus = MutableStateFlow(DhizukuStatus.MISSING)

    override val status: StateFlow<DhizukuStatus> = mutableStatus.asStateFlow()

    /** Exempts only the package-installer and device-policy hidden API prefixes needed by Binder wrappers. */
    private val hiddenApiExempted: Boolean by lazy {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/content/pm/",
                "Landroid/app/admin/",
            )
            true
        }.getOrDefault(false)
    }

    /** Re-probes Dhizuku availability and authorization off the main thread. */
    override suspend fun refreshStatus(): DhizukuStatus = withContext(Dispatchers.IO) {
        probeStatus().also { mutableStatus.value = it }
    }

    /** Prompts the user to grant Dhizuku API access when an active Dhizuku server is awaiting approval. */
    override suspend fun requestPermission(): DhizukuStatus {
        val current = refreshStatus()
        if (current != DhizukuStatus.PERMISSION_REQUIRED) return current
        withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Int> { continuation ->
                try {
                    Dhizuku.requestPermission(
                        object : DhizukuRequestPermissionListener() {
                            override fun onRequestPermission(grantResult: Int) {
                                if (continuation.isActive) {
                                    continuation.resume(grantResult)
                                }
                            }
                        },
                    )
                } catch (_: Throwable) {
                    if (continuation.isActive) {
                        continuation.resume(PackageManager.PERMISSION_DENIED)
                    }
                }
            }
        }
        return refreshStatus()
    }

    /** Returns whether Dhizuku currently reports an active, authorized session. */
    fun isReadyNow(): Boolean = probeStatus().also { mutableStatus.value = it } == DhizukuStatus.READY

    /** Returns the active Device/Profile Owner package name for diagnostics, if available. */
    fun ownerPackageNameOrNull(): String? = runCatching {
        Dhizuku.getOwnerPackageName()
    }.getOrNull()

    /**
     * Requests a silent per-user uninstall through `IPackageInstaller.uninstall` wrapped with
     * Dhizuku's Device Owner Binder identity.
     *
     * Inside AOSP's `PackageInstallerService.uninstall`, a Device Owner hits
     * `canSilentlyInstallPackage` and clears calling identity before calling
     * `mPm.deletePackageVersioned`, allowing `DELETE_SYSTEM_APP` requests to execute as
     * `UID 1000 (system_server)` rather than `UID 2000 (shell)`.
     */
    @SuppressLint("PrivateApi")
    fun uninstall(
        packageName: String,
        userId: Int,
        expectedVersionCode: Long,
        isSystemApp: Boolean,
    ): PackageFallbackResult {
        val currentUser = currentUserId()
        if (userId != currentUser) {
            return PackageFallbackResult(
                submitted = false,
                detail = "Dhizuku uninstall only supports current user $currentUser (requested $userId)",
            )
        }
        if (expectedVersionCode < 0L) {
            return PackageFallbackResult(false, "Expected version code unavailable for safe Dhizuku uninstall")
        }
        if (!isReadyNow()) {
            return PackageFallbackResult(false, "Dhizuku unavailable or unauthorized (status=${mutableStatus.value})")
        }
        ensureHiddenApiExempted()

        val requestId = nextRequestId.incrementAndGet()
        val waiter = CallbackWaiter()
        pendingCallbacks[requestId] = waiter
        val pendingIntent = createUninstallPendingIntent(requestId)
        return try {
            val ownerPackage = Dhizuku.getOwnerPackageName()
            val wrappedInstaller = wrapPackageInstaller(ownerPackage)
            val flags = if (isSystemApp) deleteSystemAppFlag() else 0
            val versioned = VersionedPackage(packageName, expectedVersionCode)
            val uninstallMethod = wrappedInstaller.javaClass.getMethod(
                "uninstall",
                VersionedPackage::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                IntentSender::class.java,
                Int::class.javaPrimitiveType,
            )
            uninstallMethod.invoke(
                wrappedInstaller,
                versioned,
                ownerPackage,
                flags,
                pendingIntent.intentSender,
                userId,
            )

            val received = waiter.await(UNINSTALL_CALLBACK_TIMEOUT_SECONDS)
            if (received == null) {
                // Keep submitted=true so PackageCommands still checks dumpsys in case the callback lagged.
                PackageFallbackResult(
                    submitted = true,
                    detail = "DHIZUKU_UNINSTALL_TIMEOUT $packageName user=$userId versionCode=$expectedVersionCode flags=$flags",
                )
            } else {
                parseUninstallCallback(received, packageName, userId, expectedVersionCode, flags)
            }
        } catch (failure: Throwable) {
            val cause = (failure as? InvocationTargetException)?.targetException ?: failure
            PackageFallbackResult(false, "DHIZUKU_UNINSTALL_ERROR ${cause.javaClass.simpleName}: ${cause.message}")
        } finally {
            pendingCallbacks.remove(requestId)
            runCatching { pendingIntent.cancel() }
        }
    }

    /** Hides or unhides [packageName] in the current user through DevicePolicyManager via Dhizuku. */
    @SuppressLint("PrivateApi")
    fun setApplicationHidden(
        packageName: String,
        userId: Int,
        hidden: Boolean,
    ): PackageFallbackResult {
        val currentUser = currentUserId()
        if (userId != currentUser) {
            return PackageFallbackResult(
                submitted = false,
                detail = "Dhizuku hide only supports current user $currentUser (requested $userId)",
            )
        }
        if (!isReadyNow()) {
            return PackageFallbackResult(false, "Dhizuku unavailable or unauthorized (status=${mutableStatus.value})")
        }
        ensureHiddenApiExempted()

        return try {
            val applied = withWrappedDevicePolicyManager { dpm, admin ->
                dpm.setApplicationHidden(admin, packageName, hidden)
            }
            PackageFallbackResult(
                submitted = applied,
                detail = "DHIZUKU_SET_HIDDEN hidden=$hidden applied=$applied $packageName user=$userId",
            )
        } catch (failure: Throwable) {
            val cause = (failure as? InvocationTargetException)?.targetException ?: failure
            PackageFallbackResult(false, "DHIZUKU_SET_HIDDEN_ERROR ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    /** Restores an uninstalled system package in the current user through DevicePolicyManager via Dhizuku. */
    @SuppressLint("PrivateApi")
    fun installExistingPackage(
        packageName: String,
        userId: Int,
    ): PackageFallbackResult {
        val currentUser = currentUserId()
        if (userId != currentUser) {
            return PackageFallbackResult(
                submitted = false,
                detail = "Dhizuku restore only supports current user $currentUser (requested $userId)",
            )
        }
        if (!isReadyNow()) {
            return PackageFallbackResult(false, "Dhizuku unavailable or unauthorized (status=${mutableStatus.value})")
        }
        ensureHiddenApiExempted()

        return try {
            val installed = withWrappedDevicePolicyManager { dpm, admin ->
                dpm.installExistingPackage(admin, packageName)
            }
            PackageFallbackResult(
                submitted = installed,
                detail = "DHIZUKU_INSTALL_EXISTING installed=$installed $packageName user=$userId",
            )
        } catch (failure: Throwable) {
            val cause = (failure as? InvocationTargetException)?.targetException ?: failure
            PackageFallbackResult(false, "DHIZUKU_INSTALL_EXISTING_ERROR ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    /** Resolves Dhizuku's live server and authorization status synchronously. */
    private fun probeStatus(): DhizukuStatus = try {
        if (Dhizuku.init(appContext)) {
            if (Dhizuku.isPermissionGranted()) DhizukuStatus.READY
            else DhizukuStatus.PERMISSION_REQUIRED
        } else {
            if (isOfficialDhizukuInstalled()) DhizukuStatus.INACTIVE else DhizukuStatus.MISSING
        }
    } catch (_: Throwable) {
        if (isOfficialDhizukuInstalled()) DhizukuStatus.ERROR else DhizukuStatus.MISSING
    }

    private fun isOfficialDhizukuInstalled(): Boolean = try {
        appContext.packageManager.getApplicationInfo(OFFICIAL_DHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun ensureHiddenApiExempted() {
        hiddenApiExempted
    }

    /** Obtains an `IPackageInstaller` proxy routed through Dhizuku without mutating app singletons. */
    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi")
    private fun wrapPackageInstaller(ownerPackage: String): Any {
        val ownerContext = appContext.createPackageContext(ownerPackage, Context.CONTEXT_IGNORE_SECURITY)
        val installer = ownerContext.packageManager.packageInstaller
        val field = installer.javaClass.getDeclaredField("mInstaller").apply { isAccessible = true }
        val rawInstaller = checkNotNull(field.get(installer)) { "PackageInstaller.mInstaller is null" }
        val rawBinder = rawInstaller.javaClass.getMethod("asBinder").invoke(rawInstaller) as IBinder
        val wrappedBinder = Dhizuku.binderWrapper(rawBinder)
        val stubClass = Class.forName("android.content.pm.IPackageInstaller\$Stub")
        return checkNotNull(stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, wrappedBinder)) {
            "IPackageInstaller.Stub.asInterface returned null"
        }
    }

    /** Runs [block] against a fresh DevicePolicyManager instance whose Binder is wrapped by Dhizuku. */
    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi")
    private fun <T> withWrappedDevicePolicyManager(block: (DevicePolicyManager, ComponentName) -> T): T {
        val admin = Dhizuku.getOwnerComponent()
        val ownerContext = appContext.createPackageContext(admin.packageName, Context.CONTEXT_IGNORE_SECURITY)
        val dpm = checkNotNull(ownerContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager) {
            "DevicePolicyManager unavailable"
        }
        val field = dpm.javaClass.getDeclaredField("mService").apply { isAccessible = true }
        val oldService = checkNotNull(field.get(dpm)) { "DevicePolicyManager.mService is null" }
        val oldBinder = oldService.javaClass.getMethod("asBinder").invoke(oldService) as IBinder
        val wrappedBinder = Dhizuku.binderWrapper(oldBinder)
        val stubClass = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
        val wrappedService = checkNotNull(
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, wrappedBinder),
        ) { "IDevicePolicyManager.Stub.asInterface returned null" }
        field.set(dpm, wrappedService)
        return try {
            block(dpm, admin)
        } finally {
            runCatching { field.set(dpm, oldService) }
        }
    }

    private fun createUninstallPendingIntent(requestId: Int): PendingIntent {
        val intent = Intent(appContext, DhizukuUninstallReceiver::class.java)
            .putExtra(DhizukuUninstallReceiver.EXTRA_REQUEST_ID, requestId)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(appContext, requestId, intent, flags)
    }

    private fun parseUninstallCallback(
        intent: Intent,
        packageName: String,
        userId: Int,
        expectedVersionCode: Long,
        flags: Int,
    ): PackageFallbackResult {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val legacyStatus = intent.getIntExtra(EXTRA_LEGACY_STATUS, Int.MIN_VALUE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().trim()
        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> PackageFallbackResult(
                submitted = true,
                detail = "DHIZUKU_UNINSTALL_SUCCESS $packageName user=$userId versionCode=$expectedVersionCode flags=$flags",
            )
            PackageInstaller.STATUS_PENDING_USER_ACTION -> PackageFallbackResult(
                submitted = false,
                detail = "DHIZUKU_UNINSTALL_PENDING_USER_ACTION $packageName (silent uninstall not permitted by system)",
            )
            else -> {
                val legacyPart = if (legacyStatus != Int.MIN_VALUE) " legacy=$legacyStatus" else ""
                val messagePart = if (statusMessage.isNotEmpty()) " message=$statusMessage" else ""
                PackageFallbackResult(
                    submitted = false,
                    detail = "DHIZUKU_UNINSTALL_FAILED status=$status$legacyPart$messagePart",
                )
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun deleteSystemAppFlag(): Int = runCatching {
        PackageManager::class.java.getField("DELETE_SYSTEM_APP").getInt(null)
    }.getOrDefault(DELETE_SYSTEM_APP_FALLBACK)

    private fun currentUserId(): Int = Process.myUid() / PER_USER_RANGE

    private class CallbackWaiter {
        private val latch = CountDownLatch(1)
        private val result = AtomicReference<Intent?>()

        fun complete(intent: Intent) {
            result.set(intent)
            latch.countDown()
        }

        fun await(timeoutSeconds: Long): Intent? =
            if (latch.await(timeoutSeconds, TimeUnit.SECONDS)) result.get() else null
    }

    internal companion object {
        const val OFFICIAL_DHIZUKU_PACKAGE = "com.rosan.dhizuku"
        const val DHIZUKU_PROJECT_URL = "https://github.com/iamr0s/Dhizuku"

        private const val PER_USER_RANGE = 100_000
        private const val DELETE_SYSTEM_APP_FALLBACK = 0x00000004
        private const val EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS"
        private const val UNINSTALL_CALLBACK_TIMEOUT_SECONDS = 10L
        private const val PERMISSION_TIMEOUT_MS = 20_000L

        private val nextRequestId = AtomicInteger(1_000)
        private val pendingCallbacks = ConcurrentHashMap<Int, CallbackWaiter>()

        internal fun deliverUninstallCallback(requestId: Int, intent: Intent) {
            pendingCallbacks.remove(requestId)?.complete(intent)
        }
    }
}
