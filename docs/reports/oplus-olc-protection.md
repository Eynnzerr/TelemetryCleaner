# ColorOS 对 com.oplus.olc 的卸载保护分析

实测机型：OPPO PGEM10（ColorOS 16 / Android 16，`PGEM10_16.0.2.401(CN01)`）。首次实测日期：2026-11-19；补充实测日期：2026-11-26（同一台未 Root 真机，新增 Dhizuku v2.13.0 设备所有者通道）。测试时设备未 Root、未解锁 Bootloader，使用 ADB shell、系统设置/应用详情界面、项目现有的 Shizuku / `app_process` 路径，以及 2026-11-26 集成的 Dhizuku 增强通道。

## 结论

`com.oplus.olc` 是 OPPO 预置的「日志服务」，位于 `/system_ext`，不是运营商组件。它同时受 ColorOS 的卸载/停用隐藏保护和 Android 的系统应用删除规则约束：

- **纯 shell 路径（2026-11-19）**：非 Root 的 ADB、Shizuku 和 `app_process`（UID 2000）均无法卸载；停用也会被 ColorOS 拒绝。
- **Dhizuku（Device Owner）路径（2026-11-26 补充实测）**：同一台未 Root 真机上，通过 Dhizuku 代理 `IPackageInstaller.uninstall(..., DELETE_SYSTEM_APP)` 可在**主空间（用户 0）免 Root 成功卸载并恢复 `com.oplus.olc`**；但**停用/隐藏（「仅冻结」）仍会被 ColorOS 拦截**。系统分身（用户 10）超出 Device Owner 作用域，仍需 Root（UID 0）处理。

本文对应清单变更记录：2026-11-19 曾因无免 Root 路径将其从 `PackageDirectory.kt` 的 `COLOR_OS` 分组移除；2026-11-26 在真机验证 Dhizuku 免 Root 卸载与恢复可行后，已将 `com.oplus.olc` 重新保留回 `COLOR_OS` 分组（仍不加入 `autoCleanupNames`）。README 的运营商组件表本来没有这个包，因此不需要改表。

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

## 2026-11-26 补充实测：Dhizuku（Device Owner）免 Root 路径

在项目中集成可选的 Dhizuku（Device Owner）增强通道后，于同一台未 Root 的 OPPO PGEM10（ColorOS 16 / Android 16）安装官方签名版 Dhizuku `v2.13.0` 并激活 Device Owner，使用 TelemetryCleaner 开启「Dhizuku 增强」后对 `com.oplus.olc` 做了完整实测。

### 为什么 Dhizuku 可以免 Root 卸载与恢复

AOSP `PackageInstallerService.uninstall(...)` 在判定调用方具备 Device Owner 静默安装/卸载资格（`dpmi.canSilentlyInstallPackage`）时，会先执行 `Binder.clearCallingIdentity()`，再调用四参数 `mPm.deletePackageVersioned(versionedPackage, adapter.getBinder(), userId, flags)`。结果是：

1. 进入 PMS 时调用方身份变为 **`UID 1000`（`system_server`）**，不再命中 `OplusForbidUninstallAppManager` 的 `uid2 == 2000`（shell）专用拦截分支；
2. 保留了调用方传入的 `PackageManager.DELETE_SYSTEM_APP`（`flags = 4`），从而通过 AOSP `DeletePackageHelper.mayDeletePackageLocked()` 的系统应用删除检查。

两层校验同时放行，因此可在主空间（用户 0）免 Root 完成按用户卸载，并可通过系统保留在 `/system_ext` 的 APK 正常恢复。

### 为什么「仅冻结」仍然失败

手动选择「仅冻结」时，Shizuku 的 `pm disable-user` 与 Dhizuku 兜底调用的 `DevicePolicyManager.setApplicationHidden(..., true)` 均被 ColorOS 拦截。与 `OplusForbidUninstallAppManager` 只盯 `uid2 == 2000` 不同，`OplusForbidHideOrDisableManager` 对受保护应用的停用与隐藏检查不区分 shell 与 Device Owner（UID 1000），因此即使通过 Dhizuku 也无法隐藏 `com.oplus.olc`。

### 真机实测结果与日志节选

| 入口 | 操作 | 结果 |
| --- | --- | --- |
| TelemetryCleaner（Shizuku + Dhizuku 增强） | 主空间卸载 `com.oplus.olc` | **成功**：前两层 shell 路径被拦截后自动回落到 Dhizuku `IPackageInstaller`，用户 0 卸载成功并停止进程 |
| TelemetryCleaner（Shizuku + Dhizuku 增强） | 主空间恢复 `com.oplus.olc` | **成功**：使用 `/system_ext` 预置 APK 恢复为已安装 |
| TelemetryCleaner（Shizuku + Dhizuku 增强） | 主空间「仅冻结」`com.oplus.olc` | **失败**：`disable-user` 与 Dhizuku `setApplicationHidden` 均被 `OplusForbidHideOrDisableManager` 拒绝 |

对应时刻的真机 `logcat` 关键记录：

```text
21:33:18.198  W OplusForbidUninstallAppManager: Cannot uninstall customize protect app com.oplus.olc
21:33:18.744  W OplusForbidUninstallAppManager: Cannot uninstall customize protect app com.oplus.olc
21:33:22.938  I ActivityManager: Force stopping com.oplus.olc appid=1000 user=0: deletePackageX
21:33:41.642  W OplusForbidHideOrDisableManager: Cannot disable protect app com.oplus.olc
21:33:41.674  W OplusForbidHideOrDisableManager: Cannot hiding protect app com.oplus.olc
```

限制：Device Owner 作用域仅限主空间（用户 0），系统分身（用户 10 `system_clone`）无法经由 Dhizuku 处理，如需卸载分身实例仍需 Root。

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

1. **保留 `com.oplus.olc` 在 `PackageDirectory.kt` 的 `COLOR_OS` 分组中**：2026-11-19 因纯 Shizuku 不可卸载曾将其移除；2026-11-26 集成并真机验证 Dhizuku 增强通道后已重新收录，未 Root 用户可在设置中启用「Dhizuku 增强」完成主空间卸载与恢复。
2. **保留 `DELETE_SYSTEM_APP` 兜底，并增加可选的 Dhizuku 增强通道**：见 [DhizukuBridge.kt](../../app/src/main/java/com/eynnzerr/telemetrycleaner/data/packagemanager/DhizukuBridge.kt)。
3. **移除「卸载失败时尝试冻结」设置与自动回落**：无论对 ColorOS 的 `com.oplus.olc` 还是对 vivo 的 `com.vivo.devicereg`，卸载失败后自动尝试 `disable-user` 都会被系统同样拒绝，反而掩盖卸载失败本身；现已删除该选项，仅保留操作确认页里的手动「仅冻结」切换。
4. **不将 OLC 放进自动清理名单**：保持不在 `autoCleanupNames` 中，仅允许手动操作。

## 设备状态

两次测试结束后，均已将 `com.oplus.olc` 恢复为已安装状态，未对系统设置或用户数据造成破坏。
