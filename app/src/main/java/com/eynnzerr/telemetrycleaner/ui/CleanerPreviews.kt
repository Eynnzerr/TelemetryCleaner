@file:OptIn(ExperimentalMaterial3Api::class)

package com.eynnzerr.telemetrycleaner.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.AndroidUser
import com.eynnzerr.telemetrycleaner.data.AutoCleanupCheck
import com.eynnzerr.telemetrycleaner.data.AutoCleanupCheckStatus
import com.eynnzerr.telemetrycleaner.data.AutoCleanupHistory
import com.eynnzerr.telemetrycleaner.data.AutoCleanupRemoval
import com.eynnzerr.telemetrycleaner.data.AutoCleanupStatus
import com.eynnzerr.telemetrycleaner.data.Carrier
import com.eynnzerr.telemetrycleaner.data.ChangeOutcome
import com.eynnzerr.telemetrycleaner.data.ChangeTarget
import com.eynnzerr.telemetrycleaner.data.ComponentPermission
import com.eynnzerr.telemetrycleaner.data.DEFAULT_AUTO_CLEANUP_INTERVAL_MINUTES
import com.eynnzerr.telemetrycleaner.data.DetectedPackage
import com.eynnzerr.telemetrycleaner.data.PackageAction
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PackageScanIssue
import com.eynnzerr.telemetrycleaner.data.PackageState
import com.eynnzerr.telemetrycleaner.data.PermissionProtection
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.data.ShizukuStatus
import com.eynnzerr.telemetrycleaner.data.ThemePalette
import com.eynnzerr.telemetrycleaner.feature.CleanerActivity
import com.eynnzerr.telemetrycleaner.feature.CleanerError
import com.eynnzerr.telemetrycleaner.feature.CleanerPanel
import com.eynnzerr.telemetrycleaner.feature.CleanerState
import com.eynnzerr.telemetrycleaner.ui.theme.TelemetryCleanerTheme

private val owner = AndroidUser(0, "Owner", serialNumber = 0)
private val clone = AndroidUser(10, "system_clone", serialNumber = 10)
private val profiles = listOf(owner, clone)
private val unicom = PackageEntry("com.mobiletools.systemhelper", "SystemHelper", setOf(Carrier.UNICOM))
private val telecom = PackageEntry("com.oppo.ctautoregist", "CTAutoRegist", setOf(Carrier.TELECOM))
private val mobile = PackageEntry("com.coloros.regservice", "RegService", setOf(Carrier.MOBILE))
private val shared = PackageEntry(
    "com.vivo.devicereg",
    "Device Management",
    setOf(Carrier.TELECOM, Carrier.MOBILE),
    R.string.package_note_vivo_devicereg,
)
private val unassigned = PackageEntry("com.meizu.regservice", "RegService", emptySet())

/** Supplies package-manager fixtures without reaching Shizuku or Android's package service. */
private fun samplePackage(
    entry: PackageEntry,
    ownerState: PackageState,
    cloneState: PackageState = ownerState,
    hasPackageRecord: Boolean = ownerState != PackageState.NOT_FOUND && ownerState != PackageState.UNKNOWN,
    hasVerifiedSystemIdentity: Boolean = hasPackageRecord,
    hasSystemCopy: Boolean = true,
): DetectedPackage = DetectedPackage(
    entry = entry,
    versionName = "16.0.0".takeIf { hasPackageRecord },
    versionCode = 31L.takeIf { hasPackageRecord },
    systemPath = if (!hasPackageRecord) null else if (hasSystemCopy) {
        "/product/priv-app/${entry.displayName}/${entry.displayName}.apk"
    } else {
        "/data/app/${entry.packageName}/base.apk"
    },
    users = mapOf(owner.id to ownerState, clone.id to cloneState),
    hasVerifiedSystemIdentity = hasVerifiedSystemIdentity,
    hasSystemCopy = hasSystemCopy.takeIf { hasPackageRecord },
)

/** Creates a state whose scan belongs to the same user profile as the running app. */
private fun scannedState(vararg packages: DetectedPackage) = CleanerState(
    connection = ShizukuStatus.READY,
    snapshot = ScanSnapshot(owner.id, profiles, packages.toList()),
    selectedUserIds = setOf(owner.id),
)

private val scan = scannedState(
    samplePackage(unicom, PackageState.RESTORABLE),
    samplePackage(telecom, PackageState.INSTALLED),
    samplePackage(mobile, PackageState.INSTALLED, PackageState.RESTORABLE),
)

private val afterPartialUninstall = scannedState(
    samplePackage(unicom, PackageState.RESTORABLE),
    samplePackage(telecom, PackageState.RESTORABLE, PackageState.INSTALLED),
    samplePackage(mobile, PackageState.INSTALLED, PackageState.RESTORABLE),
)

private val afterRestore = scannedState(
    samplePackage(unicom, PackageState.INSTALLED, PackageState.RESTORABLE),
    samplePackage(telecom, PackageState.INSTALLED),
    samplePackage(mobile, PackageState.INSTALLED, PackageState.RESTORABLE),
)

private val bothSpaces = setOf(owner.id, clone.id)
private val telecomOwner = ChangeTarget(telecom.packageName, owner.id)
private val telecomClone = ChangeTarget(telecom.packageName, clone.id)
private val unicomOwner = ChangeTarget(unicom.packageName, owner.id)
private val unicomClone = ChangeTarget(unicom.packageName, clone.id)

/** Named fixtures for connection, scan, package and selection states on the main screen. */
internal class CleanerScreenPreviewProvider : PreviewParameterProvider<CleanerState> {
    private val cases = listOf(
        "未安装 Shizuku" to CleanerState(connection = ShizukuStatus.MISSING),
        "Shizuku 未启动" to CleanerState(connection = ShizukuStatus.STOPPED),
        "等待授权" to CleanerState(connection = ShizukuStatus.PERMISSION_REQUIRED),
        "正在连接" to CleanerState(connection = ShizukuStatus.CONNECTING),
        "连接失败" to CleanerState(connection = ShizukuStatus.ERROR),
        "已连接 · 等待检测" to CleanerState(connection = ShizukuStatus.READY),
        "首次检测中" to CleanerState(connection = ShizukuStatus.READY, activity = CleanerActivity.SCANNING),
        "刷新已有结果" to scan.copy(activity = CleanerActivity.SCANNING),
        "已安装与已卸载" to scan,
        "全部已卸载" to scannedState(
            samplePackage(unicom, PackageState.RESTORABLE),
            samplePackage(telecom, PackageState.RESTORABLE),
            samplePackage(mobile, PackageState.RESTORABLE),
        ),
        "未检出组件" to scannedState(
            samplePackage(unicom, PackageState.NOT_FOUND),
            samplePackage(telecom, PackageState.NOT_FOUND),
            samplePackage(mobile, PackageState.NOT_FOUND),
        ),
        "组件查询失败 · 显示原因" to scannedState(
            samplePackage(unicom, PackageState.UNKNOWN)
                .copy(scanIssues = mapOf(owner.id to PackageScanIssue.PERMISSION_DENIED)),
            samplePackage(telecom, PackageState.UNKNOWN)
                .copy(scanIssues = mapOf(owner.id to PackageScanIssue.UNRECOGNIZED_RESPONSE)),
            samplePackage(mobile, PackageState.INSTALLED),
            samplePackage(unassigned, PackageState.UNKNOWN)
                .copy(scanIssues = mapOf(owner.id to PackageScanIssue.QUERY_FAILED)),
        ),
        "分身安装状态缺失" to scannedState(
            samplePackage(unicom, PackageState.INSTALLED, PackageState.UNKNOWN)
                .copy(scanIssues = mapOf(clone.id to PackageScanIssue.USER_STATE_MISSING)),
        ).copy(selectedUserIds = bothSpaces),
        "已停用" to scannedState(samplePackage(unicom, PackageState.DISABLED)),
        "系统组件 · 可恢复" to scannedState(
            samplePackage(shared, PackageState.RESTORABLE, hasVerifiedSystemIdentity = false),
        ),
        "普通应用 · 卸载不可恢复" to scannedState(
            samplePackage(
                unassigned,
                PackageState.INSTALLED,
                PackageState.RESTORABLE,
                hasVerifiedSystemIdentity = false,
                hasSystemCopy = false,
            ),
        ),
        "未选择空间" to scan.copy(selectedUserIds = emptySet()),
        "多个空间 · 批量操作" to scan.copy(
            selectedUserIds = bothSpaces,
            selectedPackageNames = setOf(unicom.packageName, telecom.packageName),
        ),
        "全部选中 · 批量操作" to scan.copy(
            selectedPackageNames = setOf(unicom.packageName, telecom.packageName, mobile.packageName),
        ),
        "操作执行中" to scannedState(
            samplePackage(unicom, PackageState.UNKNOWN, PackageState.RESTORABLE, hasPackageRecord = true),
            samplePackage(telecom, PackageState.INSTALLED),
        ).copy(activity = CleanerActivity.CHANGING),
        "无法读取用户空间" to CleanerState(
            connection = ShizukuStatus.READY,
            error = CleanerError.SCAN_SPACE_PERMISSION_DENIED,
        ),
        "刷新失败 · 上次结果" to scan.copy(error = CleanerError.SCAN_FAILED),
        "操作失败 · 上次结果" to scan.copy(error = CleanerError.ACTION_FAILED),
        "连接断开 · 上次结果" to scan.copy(connection = ShizukuStatus.STOPPED),
    )

    /** Supplies all main-screen examples in their display order. */
    override val values: Sequence<CleanerState> get() = cases.asSequence().map { it.second }

    /** Labels each generated preview in Android Studio. */
    override fun getDisplayName(index: Int): String = cases[index].first
}

/** Named fixtures for each bottom sheet and both operation results. */
internal class CleanerSheetPreviewProvider : PreviewParameterProvider<CleanerState> {
    private val cases = listOf(
        "组件详情" to scan.copy(panel = CleanerPanel.Details(telecom.packageName)),
        "确认卸载" to scan.copy(
            selectedUserIds = bothSpaces,
            panel = CleanerPanel.Confirmation(PackageAction.UNINSTALL, listOf(telecomOwner, telecomClone)),
        ),
        "确认卸载 · 未测试组件" to scannedState(
            samplePackage(telecom, PackageState.INSTALLED),
            samplePackage(shared, PackageState.INSTALLED),
            samplePackage(unassigned, PackageState.INSTALLED),
        ).copy(
            selectedUserIds = bothSpaces,
            panel = CleanerPanel.Confirmation(
                PackageAction.UNINSTALL,
                listOf(
                    telecomOwner,
                    ChangeTarget(shared.packageName, owner.id),
                    ChangeTarget(shared.packageName, clone.id),
                    ChangeTarget(unassigned.packageName, owner.id),
                ),
            ),
        ),
        "确认恢复" to scan.copy(
            panel = CleanerPanel.Confirmation(PackageAction.RESTORE, listOf(unicomOwner)),
        ),
        "卸载结果 · 部分失败" to afterPartialUninstall.copy(
            selectedUserIds = bothSpaces,
            panel = CleanerPanel.Results(
                PackageAction.UNINSTALL,
                listOf(ChangeOutcome(telecomOwner, true, null), ChangeOutcome(telecomClone, false, null)),
            ),
        ),
        "恢复成功" to afterRestore.copy(
            panel = CleanerPanel.Results(PackageAction.RESTORE, listOf(ChangeOutcome(unicomOwner, true, null))),
        ),
        "恢复失败 · 系统原因" to scan.copy(
            selectedUserIds = bothSpaces,
            panel = CleanerPanel.Results(
                PackageAction.RESTORE,
                listOf(ChangeOutcome(unicomClone, false, "系统拒绝了对该空间的操作。")),
            ),
        ),
    )

    /** Supplies all sheet examples in their display order. */
    override val values: Sequence<CleanerState> get() = cases.asSequence().map { it.second }

    /** Labels each generated sheet preview in Android Studio. */
    override fun getDisplayName(index: Int): String = cases[index].first
}

/** Shows representative Android grants and both empty permission list states. */
internal class PackagePermissionsPreviewProvider : PreviewParameterProvider<CleanerState> {
    private val permissions = listOf(
        ComponentPermission(
            "android.permission.READ_SMS", "读取短信", PermissionProtection.DANGEROUS,
            mapOf(owner.id to true, clone.id to false),
        ),
        ComponentPermission(
            "android.permission.ACCESS_FINE_LOCATION", "获取精确位置", PermissionProtection.DANGEROUS,
            mapOf(owner.id to false, clone.id to true),
        ),
        ComponentPermission(
            "android.permission.READ_PRIVILEGED_PHONE_STATE", "读取特权电话状态", PermissionProtection.PRIVILEGED,
            mapOf(owner.id to true, clone.id to true),
        ),
        ComponentPermission("android.permission.INTERNET", "访问互联网", PermissionProtection.OTHER, emptyMap()),
    )

    private val cases = listOf(
        "已申请的权限" to state(permissions, PackageState.INSTALLED),
        "此空间未安装" to state(permissions, PackageState.RESTORABLE),
        "无申请项" to state(emptyList(), PackageState.INSTALLED),
        "权限列表不可用" to state(null, PackageState.INSTALLED),
    )

    /** Supplies a details panel backed by simulated scan results. */
    private fun state(permissions: List<ComponentPermission>?, ownerState: PackageState) =
        scannedState(samplePackage(telecom, ownerState, PackageState.INSTALLED).copy(permissions = permissions))
            .copy(panel = CleanerPanel.Details(telecom.packageName))

    /** Supplies each permission tab example. */
    override val values: Sequence<CleanerState> get() = cases.asSequence().map { it.second }

    /** Labels each generated permissions preview in Android Studio. */
    override fun getDisplayName(index: Int): String = cases[index].first
}

/** Renders one main-screen state in the Chinese locale without device permissions. */
@Preview(group = "主页面", widthDp = 400, heightDp = 850, locale = "zh-rCN")
@Composable
private fun CleanerScreenStatePreview(
    @PreviewParameter(CleanerScreenPreviewProvider::class) state: CleanerState,
) {
    TelemetryCleanerTheme(dynamicColor = false) {
        CleanerScreen(state, onIntent = {}, onOpenSettings = {})
    }
}

/** Draws the same sheet content in-place so it is visible before a modal window animates in. */
@Preview(group = "底部面板", widthDp = 400, heightDp = 850, locale = "zh-rCN")
@Composable
private fun CleanerSheetStatePreview(
    @PreviewParameter(CleanerSheetPreviewProvider::class) state: CleanerState,
) {
    CleanerSheetPreviewContent(state)
}

/** Renders the real sheet directly on the permissions tab. */
@Preview(group = "组件权限", widthDp = 400, heightDp = 850, locale = "zh-rCN")
@Composable
private fun PackagePermissionsStatePreview(
    @PreviewParameter(PackagePermissionsPreviewProvider::class) state: CleanerState,
) {
    CleanerSheetPreviewContent(state, showPermissionsInitially = true)
}

/** Provides the production sheet shell to static overview and permission previews. */
@Composable
private fun CleanerSheetPreviewContent(
    state: CleanerState,
    showPermissionsInitially: Boolean = false,
) {
    TelemetryCleanerTheme(dynamicColor = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            CleanerScreen(state.copy(panel = null), onIntent = {}, onOpenSettings = {})
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = BottomSheetDefaults.ExpandedShape,
                color = BottomSheetDefaults.ContainerColor,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BottomSheetDefaults.DragHandle()
                    CleanerSheetContent(
                        checkNotNull(state.panel), state, onIntent = {}, showPermissionsInitially = showPermissionsInitially,
                    )
                }
            }
        }
    }
}

/** Shows both localized titles and checks the summary card on a narrower screen. */
@Preview(name = "English · narrow", group = "语言与主题", widthDp = 340, heightDp = 760, locale = "en")
@Preview(
    name = "简体中文 · 深色",
    group = "语言与主题",
    widthDp = 400,
    heightDp = 850,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CleanerLocaleAndThemePreview() {
    TelemetryCleanerTheme(dynamicColor = false) {
        CleanerScreen(scan, onIntent = {}, onOpenSettings = {})
    }
}

/** Displays the enabled refresh action in the pinned app bar. */
@Preview(name = "可刷新", group = "顶部栏", widthDp = 400, heightDp = 104, locale = "zh-rCN")
@Composable
private fun CleanerIdleTopBarPreview() {
    CleanerTopBarPreview(activity = CleanerActivity.IDLE)
}

/** Displays the refresh action disabled while a scan is running. */
@Preview(name = "刷新中 · 禁用", group = "顶部栏", widthDp = 400, heightDp = 104, locale = "zh-rCN")
@Composable
private fun CleanerScanningTopBarPreview() {
    CleanerTopBarPreview(activity = CleanerActivity.SCANNING)
}

/** Previews the production top bar with the supplied scan activity. */
@Composable
private fun CleanerTopBarPreview(activity: CleanerActivity) {
    TelemetryCleanerTheme(dynamicColor = false) {
        CleanerTopBar(activity, onRefresh = {}, onOpenSettings = {})
    }
}

/** Supplies one settings preview for each automatic cleanup status. */
internal class AutoCleanupPreviewProvider : PreviewParameterProvider<AutoCleanupStatus> {
    /** Emits statuses in their declaration order. */
    override val values: Sequence<AutoCleanupStatus> get() = AutoCleanupStatus.entries.asSequence()
}

/** Renders scope options and the automatic cleanup switch and status. */
@Preview(group = "设置页", widthDp = 400, heightDp = 850, locale = "zh-rCN")
@Composable
private fun SettingsScreenPreview(
    @PreviewParameter(AutoCleanupPreviewProvider::class) status: AutoCleanupStatus,
) {
    val state = when (status) {
        AutoCleanupStatus.WAITING_FOR_SHIZUKU -> CleanerState(connection = ShizukuStatus.STOPPED)
        AutoCleanupStatus.NO_SPACES -> scan.copy(selectedUserIds = emptySet())
        AutoCleanupStatus.MONITORING -> scan.copy(selectedUserIds = bothSpaces)
        else -> scan
    }
    val intervalMinutes = if (status == AutoCleanupStatus.MONITORING) 45 else DEFAULT_AUTO_CLEANUP_INTERVAL_MINUTES
    TelemetryCleanerTheme(dynamicColor = false) {
        SettingsScreen(
            state = state,
            autoCleanupEnabled = status != AutoCleanupStatus.OFF,
            autoCleanupStatus = status,
            autoCleanupIntervalMinutes = intervalMinutes,
            onAutoCleanupChange = {},
            onAutoCleanupIntervalChange = {},
            onToggleSpace = {},
            onOpenAutoCleanupHistory = {},
            onOpenPackageDirectory = {},
            appVersion = "1.0",
            themePalette = ThemePalette.SYSTEM,
            onThemePaletteChange = {},
            checkingForUpdate = false,
            onCheckForUpdate = {},
            onOpenProject = {},
            onBack = {},
        )
    }
}

/** Previews the history page before any automatic checks run. */
@Preview(name = "暂无记录", group = "自动清理记录", widthDp = 400, heightDp = 850, locale = "zh-rCN")
@Composable
private fun EmptyAutoCleanupHistoryPreview() {
    TelemetryCleanerTheme(dynamicColor = false) {
        AutoCleanupHistoryScreen(history = AutoCleanupHistory(), onBack = {})
    }
}

/** Previews a partially completed check with two removal results. */
@Preview(name = "检查与卸载结果", group = "自动清理记录", widthDp = 400, heightDp = 850, locale = "zh-rCN")
@Composable
private fun PopulatedAutoCleanupHistoryPreview() {
    val checkTime = 1_778_846_400_000L
    TelemetryCleanerTheme(dynamicColor = false) {
        AutoCleanupHistoryScreen(
            history = AutoCleanupHistory(
                lastCheck = AutoCleanupCheck(checkTime, AutoCleanupCheckStatus.INCOMPLETE),
                removals = listOf(
                    AutoCleanupRemoval(checkTime - 2_000, telecom.packageName, clone, success = false),
                    AutoCleanupRemoval(checkTime - 4_000, unicom.packageName, owner, success = true),
                ),
            ),
            onBack = {},
        )
    }
}
