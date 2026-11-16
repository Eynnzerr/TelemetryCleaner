报告作者 [@Siu-Leung](https://github.com/Siu-Leung) · [原文 Issue #7](https://github.com/Eynnzerr/TelemetryCleaner/issues/7)

# vivo (OriginOS 6 / Android 16) 预装运营商组件与原生遥测组件实测分析报告

本报告基于 **vivo X200 Ultra (`V2454DA`)**，系统版本 **OriginOS 6 / Android 16 (`PD2454C_A_16.1.17.2.W10`)** 的真机固件提取、DEX 反编译及运行时数据库实测整理，旨在补充本项目在 **vivo 系运营商组件架构、免 Root / Root 卸载与通话录音实测反馈、以及 vivo 原生系统级遥测组件清单** 三方面的资料。

---

## 一、测试环境（仅保留公开型号与系统版本号）

| 项目 | 信息 |
| --- | --- |
| 机型型号 | `V2454DA` (vivo X200 Ultra) |
| Android 版本 | Android 16 (SDK 36) |
| 系统版本 | OriginOS 6 (`PD2454C_A_16.1.17.2.W10`) |

---

## 二、vivo 固件三大运营商 DM 组件的分工架构

在 vivo OriginOS 6 固件中，系统属性明确定义了三大运营商终端管理（DM）组件的映射关系：

```text
[ro.vivo.dm.config]: [CMCC-CTCC-UNICOM]
[ro.vivo.cmcc.dm.name]: [com.vivo.devicereg]
[ro.vivo.ctcc.dm.name]: [com.vivo.devicereg]
[ro.vivo.unicom.dm.name]: [com.mobiletools.systemhelper]
```

### 1. 联通组件：`com.mobiletools.systemhelper` (`/system/app/SystemHelper/SystemHelper.apk`)
- **版本**：`3.0.1-20250515`
- **本机特殊现象**：固件 `/system/app/SystemHelper/SystemHelper.apk` 物理文件存在（4.1 MB，内含 `liblocSDK8b.so`、`libindoor.so` 及 `http://dm.wo.com.cn:18080/register`），但在包管理服务（`pm list packages` / `dumpsys package`）中**默认未注册安装**，无运行进程。
- **代码差异补充**：与仓库 [`docs/reports/oppo-telemetry-components.md`](oppo-telemetry-components.md) 一致，vivo 分支额外检查了 `operator_service_improve_plan` 设置项。

### 2. 移动 + 电信三合一组件：`com.vivo.devicereg` (`/system/app/DeviceReg/DeviceReg.apk`)
- **应用名称**：`Device management`
- **版本**：`5.4.0.0` (`versionCode=54000`, `targetSdk=36`)
- **运行身份**：独立系统应用 `appId=10107`（注：不同于华为使用 `android.uid.phone` 共享 UID，vivo 为独立系统 UID，由平台签名授予 `signature|privileged` 权限，并在 `/system/etc/vivo-default-permissions/default-permissions-vivo.xml` 中默认强制授予精确/后台定位、发短信、读写存储、电话状态等运行时权限）。

---

## 三、`com.vivo.devicereg` (5.4.0.0) 深度代码与运行实测分析

反编译 `DeviceReg.apk`（共 665 个非框架类）确认，该包实际上是**「中国移动 `cn.richinfo.dm` + 中国电信自注册（数据/IMS/天通卫星）+ vivo 远控与 VCode 埋点」的复合体**：

### 1. 内部四大功能模块

| 模块 | 核心类 / 包前缀 | 职责与上报端点 |
| --- | --- | --- |
| **① 中国移动 DM (彩讯 SDK)** | `cn.richinfo.dm.*`、`com.dmyk.android.telephony.*` | 周期心跳（1天）、全量应用列表与前台使用时长（7天周期）、基站/Wi-Fi列表/蓝牙/充电状态采集。<br>端点：`https://a.fxltsbl.com`、`https://b.fxltsbl.com`（`/sdkService?func=tsdk:postreglog`、`posthblog`、`postapplylog`、`configlog`）、`https://auc.fxltsbl.com/AuthCenter/datareport?func=tsdk:photoReg`。 |
| **② 动态热补丁加载** | `cn.richinfo.dm.service.DMService` | 与华为 `HwCmccDm.apk` 同源，含 `dalvik.system.DexClassLoader` 动态加载 `patch.jar`（入口 `cn.richinfo.dmpatch.business.DMController`）逻辑，支持热替换 `DMFuncImpl` 业务实现。 |
| **③ 中国电信自注册（含卫星）** | `com.vivo.devicereg.receiver.RegReceiver`、`DmAlarmReceiver` | 包含电信数据自注册（`https://zzhc.vnet.cn/`）、双卡 IMS 注册（`IMS_RETRY_ALARM_SIM1/2`）以及**天通卫星短信自注册**（硬编码目标号码 `10659401`，日志：`"Start service to send satellite reg, address: 10659401"`）。 |
| **④ vivo 统一远控与自升级** | `com.vivo.devicereg.ctrl.*`、`com.vivo.upgrade.*`、`com.vivo.vcode.*` | 监听 `com.vivo.daemonService.unifiedconfig.update_finish_broadcast_DeviceManager` 拉取云端开关（`content://com.vivo.abe.unifiedconfig.provider/standard_config`），具备 `INSTALL_SELF_UPDATES` 权限与 `UpgradeServive`，并内嵌 vivo VCode (`https://vcodevisual.vivo.xyz`) 与 VDC (`VDCJobService`) 统计。 |

### 2. 申请与默认授予的高危权限（共 40 项）
- **应用与行为监控**：`QUERY_ALL_PACKAGES`、`PACKAGE_USAGE_STATS`、`READ_LOGS`（均 `granted=true`）
- **设备与 SIM 标识**：`READ_PRIVILEGED_PHONE_STATE`、`READ_PHONE_STATE`、`MODIFY_PHONE_STATE`、`LOCAL_MAC_ADDRESS`（均 `granted=true`）
- **定位与周边环境**：`ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`、`ACCESS_BACKGROUND_LOCATION`、`BLUETOOTH_CONNECT`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`
- **通信与系统控制**：`SEND_SMS`、`WRITE_APN_SETTINGS`、`WRITE_SETTINGS`、`WRITE_SECURE_SETTINGS`、`INSTALL_SELF_UPDATES`、`START_ACTIVITIES_FROM_BACKGROUND`

### 3. 真机运行态落库实锤（卸载前只读审计）
在卸载前对 `/data/user/0/com.vivo.devicereg/databases/cmcc_dm.db` 进行只读聚合统计，确认**即便在 AppOps 中 `GET_USAGE_STATS` 存在历史 reject 记录，该组件依然在本地成功采集并落库了全量应用与使用统计**：
- **本地数据库表结构**：
  - `app_info_<timestamp>`（周期表）：实测单周期内已记录本机 **562 条应用记录**，其中包含非零前台使用时长（`_useTime > 0`）**100 条**、非零启动次数（`_launchCount > 0`）**65 条**；
  - `table_app_collect_list`、`table_name_battery_charge`、`table_of_time_related_traffic`、`table_name_network`、`table_name_wifi_list`、`table_name_bluetooth`。
- **SharedPreferences 默认采集开关**：
  - `APP_REPORT_SWITCH = Y`（应用列表上报开启，周期 7 天，留存 15 天）
  - `network / heartbeat / preinstall / battery / wifi / roaming / time-related = Y`（心跳周期 1 天，且 `last_success_heartbeat_time` 已记录服务端返回 `resultCode=1` 的成功心跳时间戳）。

---

## 四、卸载限制原因与「通话录音」实测辟谣

### 1. 为什么免 Root (Shizuku `UID 2000`) 在部分 OriginOS 6 机型上卸载/停用 `com.vivo.devicereg` 会失败？
- 在 vivo OriginOS 6 的包管理服务（PMS）底层中，`com.vivo.devicereg` 被列入了 vivo 系统保护名单（同时在 `/system/etc/uepp/plugins/.../PD2454.xml` 的 `FrozenPlugin` / `AlarmAlign` 等系统名单中）。
- 当调用方 UID 为 `2000 (shell)` 时，PMS 会对 `com.vivo.devicereg` 拦截 `deletePackageAsUser`（返回 `DELETE_FAILED_USER_RESTRICTED`）及 `setApplicationEnabledSetting`（抛出 `SecurityException: Cannot disable com.vivo.devicereg no root permission`）。
- **Root 环境实测 (`UID 0`)**：在 KernelSU Root (`uid=0`) 权限下执行 `pm uninstall -k --user 0 com.vivo.devicereg`（或不加 `-k` 完全卸载），可正常返回 `Success`；主空间状态变为 `installed=false`，进程彻底消失，且可通过 `cmd package install-existing --user 0 com.vivo.devicereg` 随时恢复。

### 2. 关于 README 中「卸载 Device Management 后通话录音失效」的实测核验
- **真机实测结果**：在本机（`V2454DA`，`PD2454C_A_16.1.17.2.W10`）将 `com.vivo.devicereg` 从 `user 0` 卸载并清空残留数据后，**系统自带通话与通话录音功能完全正常，无任何失效或报错**。
- **代码层交叉验证**：vivo 的通话录音由 `com.android.incallui`、`com.android.phone`、`com.vivo.telephonyapp` 与 `com.android.bbksoundrecorder` 协同完成；反编译 `DeviceReg.apk` 全量 665 个类，未发现任何与通话录音相关的 AIDL、ContentProvider 或 Broadcast 交互。评论区反馈的录音失效大概率是用户在其他列表工具中误删了录音机或电话组件所致。

---

## 五、补充线索：vivo 原生系统遥测与高通遥测组件清单（供 WIP 选装检测参考）

针对 README `WIP` 中提到的「扩充更多组件检测（包括厂商自己的敏感数据收集组件）作为可选项」，以下为本机（`V2454DA` / `PD2454C_A_16.1.17.2.W10`）实测检出的 vivo 原生遥测与芯片级遥测组件：

### 1. 可安全停用/卸载的独立遥测组件

| 包名 | APK 路径与版本 | 实测行为与上报端点 | 停用安全性 |
| --- | --- | --- | --- |
| `com.bbk.iqoo.logsystem` | `/system/app/LogSystem/LogSystem.apk` | **BBKLOG 系统日志与应用时长上报**。拥有 `PACKAGE_USAGE_STATS`、`READ_PRIVILEGED_PHONE_STATE`；本地生成 `appanalysis_database.db`、`log_info_v4.db`；DEX 内含 `f-up.vivo.com.cn/clientRequest/reportApptime`、`ro-up.vivo.com.cn`、`err-up.vivo.com.cn`。<br>⚠️ **注意**：Manifest 声明了 `android:persistent="true"`，执行 `pm disable-user --user 0` 后需**重启一次手机**才会彻底退出内存。 | ✅ 安全（不影响日常使用） |
| `com.vivo.networkimprove` | `/system/app/NetworkImprove/NetworkImprove.apk` (`16.0.1`) | **无线基站与前台应用关联采样**。常驻运行，本地 `/data/data/com.vivo.networkimprove/databases/cld.db` 实测达 **56 MB**（`CldInfo` 1.1万+条、`SignalInfo` 1.9万+条），高频记录 `plmn/cellid/tac/pci/earfcn/rsrp` 及采样瞬间的前台 `package_name`；上报 `moni-*stsdk.vivo.com.cn`。 | ✅ 安全 |
| `com.vivo.nps` | `/system/app/NPS` | **用户体验调查与反馈遥测**。无桌面入口，后台轮询调查配置。 | ✅ 安全 |
| `com.vivo.epm` | `/system/app/EPM` | **Exception Policy Manager 异常策略与性能监控上报**。 | ✅ 安全 |
| `com.vivo.magazine` | `/system/app/Magazine` | **锁屏画报及曝光追踪**。 | ✅ 安全 |
| `com.qti.qcc` | `/system_ext/app/QCC/QCC.apk` (`QCC16.0`) | **高通芯片级遥测 (Qualcomm Connected Compute / QDMA)**。本地 `SMQService.db`、`qdma_rapdb_5.1`、`statusReport.db`，向 `https://qcc.qualcomm.cn` / `https://qcc.qualcomm.com` 上报携带机型与固件特征的数据。 | ✅ 安全 |
| `com.qualcomm.qti.devicestatisticsservice` | `/product/app/DeviceStatisticsService` | **高通设备统计服务**，拥有 `READ_PRIVILEGED_PHONE_STATE`。 | ✅ 安全 |

### 2. 深度耦合系统、不建议直接卸载/停用包（建议仅网络层拦截）的核心遥测枢纽
以下三个组件均以 `sharedUserId="android.uid.system"` (`UID 1000`) 运行，直接禁用包会导致系统设置、推送或智能功能异常，但它们是 vivo 本机数据采集量最大的模块（适合在文档中标注「仅建议防火墙/DNS 拦截，勿直接卸载」）：

1. **`com.vivo.aiengine` (`4.1.3.1`)**：包含 `:vcode` 子进程，拥有后台定位、短信、通话记录、通讯录、应用统计全套权限。本地 `aware_collect.db` 达 **100 MB**、`comvivoaiengine_VCodeSingleEvent.db` **9.1 MB**、`user_profiler.db` **4.3 MB**，并按日生成 `com_vivo_aiengine_vcode_q_YYYYMMDD.db`；在 `LbsInfo.xml` 中会直接落盘推断出的家庭与办公精确地址。
2. **`com.vivo.abe` (`5.19.0.1`, App Behavior Engine)**：`persistent=true` 常驻，承载 `unifiedconfig` 权限与配置分发；同时在 `AppInstall.db` 中记录全量应用安装/更新/卸载流水（实测 500+ 条），通过 `abeacdataort-stsdk.vivo.com.cn` 上报。
3. **`com.vivo.vms` (`1.1.2.8`)**：系统 OAID/VAID/AAID 标识符分发中心（`identifierid.db`）与 VCode 汇总上报服务，连接 `vcode-od.vivo.com.cn`、`tdc.vivo.com.cn`、`unified-id-api.vivo.com.cn`（且在 `http-dns-config.xml` 内置了硬编码直连 IP 策略）。

> **附：vivo 遥测域名拦截提示**
> vivo 的 `stsdk` 埋点大量采用 `<业务前缀>ort-stsdk.vivo.com.cn` 的连字符命名（如 `abeacdataort-stsdk.vivo.com.cn`、`vpushort-stsdk.vivo.com.cn`、`ecprivacyort-stsdk.vivo.com.cn`、`appstoreort-stsdk.vivo.com.cn`），常规广告规则中的 `*.stsdk.vivo.com.cn` 无法匹配连字符前缀，需使用正则 `(^|\.|-)stsdk\.vivo\.com\.cn$` 加上 `vcode-od.vivo.com.cn`、`vcode-api.vivo.com.cn`、`dsplog.vivo.com.cn`、`tdc.vivo.com.cn` 才能完整覆盖。
