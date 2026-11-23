# ColorOS 对 com.oplus.olc 的卸载保护分析

实测机型：OPPO PGEM10（ColorOS 16 / Android 16，`PGEM10_16.0.2.401(CN01)`）。实测日期：2026-11-19。测试时设备未 Root、未解锁 Bootloader，使用 ADB shell、系统设置/应用详情界面，以及项目现有的 Shizuku 与 `app_process` 路径。

## 结论

`com.oplus.olc` 是 OPPO 预置的「日志服务」，位于 `/system_ext`，不是运营商组件。它同时受 ColorOS 的卸载保护和 Android 的系统应用删除规则约束，**本次实测中，非 Root 的 ADB、Shizuku 和 `app_process` 都无法卸载；停用也会被 ColorOS 拒绝**。在本文测试范围内，唯一可行的用户侧路径是 Root 后以 UID 0 执行按用户卸载。

本文对应一项清单调整：从 `PackageDirectory.kt` 的 `COLOR_OS` 分组移除 `com.oplus.olc`。README 的运营商组件表本来没有这个包，因此不需要改表。

## 包信息

| 项目 | 值 |
| --- | --- |
| 包名 / 界面名 | `com.oplus.olc` / 日志服务 |
| APK 路径 | `/system_ext/app/Olc/Olc.apk` |
| 版本 | `16.0.63`（versionCode `160000063`） |
| 共享 UID | `android.uid.system`（UID 1000） |
| 包标记 | `SYSTEM`、`HAS_CODE`、`PERSISTENT`、`ALLOW_CLEAR_USER_DATA` |
| 清单元数据 | `ForbidUninstall=true` |
| 安装用户 | 用户 0（机主）、用户 10（system_clone） |

真机 `dumpsys package` 显示它默认获得了定位、联系人、通话记录、短信、相机、录音等运行时权限。本文只分析卸载保护，不展开它的数据采集行为。

## 两层保护

### 1. ColorOS 的 shell 专用检查

`oplus-services.jar` 中的 `OplusForbidUninstallAppManager.isForbidDeletePackage()` 在调用者 UID 的 appId 为 **2000（shell）** 时，会读取待删除包的 `ApplicationInfo` 元数据：

```java
if (!forbidden && uid2 == 2000) {
    if (toDeletePs != null && toDeletePs.isSystem()) {
        Bundle bundle = toDeletePkg == null ? null : toDeletePkg.getMetaData();
        if (bundle != null && bundle.getBoolean("ForbidUninstall")) {
            forbidden = true;
            Slog.w(TAG, "Cannot uninstall customize protect app " + packageName);
        }
    }
}
```

OLC 的 APK 清单里正好有 `ForbidUninstall=true`，因此 `pm`、Shizuku、`app_process` 这些以 shell 身份发起的删除都会在这里被拒绝。日志为：

```text
W/OplusForbidUninstallAppManager: Cannot uninstall customize protect app com.oplus.olc
```

ColorOS 还通过 `OplusForbidHideOrDisableManager` 阻止停用。`pm disable-user --user 0 com.oplus.olc` 返回 `new state: default`，状态没有变化，日志为：

```text
W/OplusForbidHideOrDisableManager: Cannot disable protect app com.oplus.olc
```

### 2. Android 的系统应用删除规则

AOSP 的 `DeletePackageHelper.mayDeletePackageLocked()` 对系统应用有一条限制：

```java
boolean deleteAllUsers = true;
boolean deleteSystem = (flags & 4) != 0;   // DELETE_SYSTEM_APP
if (user != null && user.getIdentifier() != -1) {
    deleteAllUsers = false;
}
if ((!deleteSystem || deleteAllUsers) && disabledPs == null) {
    Slog.w("PackageManager", "Attempt to delete unknown system package " + ps.getName());
    return null;
}
```

OLC 没有 `/data/app` 更新，`disabledPs` 为 `null`。按用户删除时 `deleteAllUsers` 为 `false`，所以必须带 `DELETE_SYSTEM_APP`（flags=4）才能过这一层。`pm uninstall` 会对系统应用自动补上这个标志；系统安装器 UI 走的是 flags=0，因此日志是：

```text
W/PackageManager: Attempt to delete unknown system package com.oplus.olc
```

## 实测结果

| 入口 | 操作 | 结果 |
| --- | --- | --- |
| `pm` / `cmd package` | `uninstall --user 0`、`--user 10`、`--user all` | `Failure [DELETE_FAILED_INTERNAL_ERROR]`，OPPO 层拒绝 |
| 系统应用详情 | 点击「卸载」 | 页面显示「卸载失败」，日志 `callUid=10184`、`Attempt to delete unknown system package` |
| 系统设置 | 点击「停用」/ `pm disable-user` | 状态仍为 `default`，`OplusForbidHideOrDisableManager` 拒绝 |
| `pm hide` | `hide --user 0` | `SecurityException: requires android.permission.MANAGE_USERS` |
| `pm suspend` | `suspend --user 0` | 状态未变化 |
| `am force-stop` | 停止进程 | persistent 进程仍存活 |
| 系统 APK 更新 | `adb install -r -d Olc.apk` | `INSTALL_FAILED_INVALID_APK: ... Persistent apps are not updateable` |
| 项目 `app_process` 兜底 | `PackageUninstaller com.oplus.olc 0` | 输出 `DELETE_REQUESTED ... flags=4`，随后仍被 OPPO 层拒绝 |
| `adb root` | 重启 adbd | `adbd cannot run as root in production builds`，设备也没有 `su` |
| 隐藏卸载参数 | `--delete-system-app`、`--force`、`--full` | `Unknown option` |

其中项目兜底的完整输出是：

```text
DELETE_REQUESTED com.oplus.olc user=0 flags=4
```

紧接着系统日志仍然是：

```text
W/OplusForbidUninstallAppManager: Cannot uninstall customize protect app com.oplus.olc
```

这说明兜底路径已经正确带上了 `DELETE_SYSTEM_APP`，失败原因不是缺少标志，而是执行身份仍是 shell。

## 为什么项目现有兜底不行

项目的 [PackageUninstaller.java](../../app/src/main/java/com/eynnzerr/telemetrycleaner/shell/PackageUninstaller.java) 确实通过反射调用了 `IPackageManager.deletePackageAsUser`，并且取了 `DELETE_SYSTEM_APP`。问题在于：

1. 入口类要求 `Process.myUid() == 2000`，否则直接 `SKIP_NOT_SHELL`；
2. [PackageFallback.kt](../../app/src/main/java/com/eynnzerr/telemetrycleaner/data/packagemanager/PackageFallback.kt) 通过 Shizuku 的 `startShizukuProcess` 启动它，身份就是 shell；
3. shell 正好命中 ColorOS 的 `uid2 == 2000` 分支。

因此这条路径能过 AOSP 的 flags=4 检查，但过不了 OPPO 的元数据检查。项目在提交后回读包状态、等待删除生效的做法是正确的；`DELETE_REQUESTED` 只代表请求已提交，不代表删除成功。

卸载失败后的 disable 兜底对 OLC 同样无效，因为 `OplusForbidHideOrDisableManager` 会拒绝停用。对带 `ForbidUninstall` 的包，建议直接跳过 disable 兜底，避免给用户“至少停用了”的错误预期。

## 可行的 Root 路径

UID 0 不进入 ColorOS 的 `uid2 == 2000` 分支，`pm uninstall` 又会为系统应用补上 `DELETE_SYSTEM_APP`，所以两层都能过。理论上执行：

```bash
adb shell su -c 'pm uninstall --user 0 com.oplus.olc'
adb shell su -c 'pm uninstall --user 10 com.oplus.olc'
```

恢复时系统 APK 仍在 `/system_ext`：

```bash
adb shell su -c 'pm install-existing --user 0 com.oplus.olc'
```

项目已有的 Root 后端走的是 [PackageCommands.kt](../../app/src/main/java/com/eynnzerr/telemetrycleaner/data/packagemanager/PackageCommands.kt) 里的 `pm uninstall --user <id>`，由 [RootPackageExecutor.kt](../../app/src/main/java/com/eynnzerr/telemetrycleaner/data/packagemanager/RootPackageExecutor.kt) 以 UID 0 执行，路径本身是可行的，但本次设备没有 Root，未做实测。

注意：按用户卸载（`--user 0` / `--user 10`）只改变用户状态，系统 APK 保留，才能用 `pm install-existing` 恢复；不要为了省事直接执行不带 `--user` 的全局卸载。解锁 Bootloader 和 Root 通常会清数据、需要 OPPO 深度测试审核，并可能影响保修，属于本文实测范围之外的操作。

## 对项目的调整

1. 已从 `PackageDirectory.kt` 的 `COLOR_OS` 分组移除 `com.oplus.olc`。它不是运营商组件，且当前没有非 Root 的可用卸载路径，继续放在组件检查清单里会让用户误以为可以由 Shizuku 管理。
2. 保留 `DELETE_SYSTEM_APP` 兜底。它对其他 ROM 的“系统应用删除”场景仍然有价值，只是无法解决 ColorOS 的 shell 专用保护。
3. 可考虑在扫描阶段读取 `ApplicationInfo.metaData` 的 `ForbidUninstall`、`ForbidDisabled`，并结合 `sharedUserId=android.uid.system` / `PERSISTENT` 标记为「ROM 保护，仅 Root」。卸载失败时识别 `OplusForbidUninstallAppManager` 日志，给出明确提示，而不是泛化的 `DELETE_FAILED_INTERNAL_ERROR`。
4. 不建议把 OLC 放进自动清理名单。它本来也不在 `autoCleanupNames` 中；保留这个状态即可。

## 设备状态

测试结束后包仍安装在用户 0 和用户 10，`stopped=false`，进程仍在运行。没有清数据、没有安装更新、没有修改系统设置；临时 force-stop 后已用 `pm unstop` 恢复。测试文件已清理，项目代码只做了上述清单条目删除。
