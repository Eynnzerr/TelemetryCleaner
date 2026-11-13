报告作者 [@Yuzgaf33tref6](https://github.com/Yuzgaf33tref6) · [原文 Issue #4](https://github.com/Eynnzerr/TelemetryCleaner/issues/4)

# 华为预装三款运营商遥测组件分析报告

 **省流：本项目app可正常禁用（user 0卸载）华为的安卓设备三个遥测组件且不会自行恢复（老荣耀应一致），不会导致设备故障和功能异常。不过未核实卸载后系统会不会执行代码（只能确认设备包管理器内是卸载状态，无法进入数据目录核实）。本人用AI对三个包进行了分析，并附分析结果**

系统版本：HarmonyOS 4.2.0.196 (C00E182R8P6) | 该机型当下最新版本，且华为已基本不对4.2及以下系统补丁

## 一、概览

| | ① RegService | ② UEInfoCheck | ③ AutoRegSms |
|---|---|---|---|
| 包名 | `com.huawei.regservice` | `com.huawei.android.UEInfoCheck` | `com.huawei.android.AutoRegSms` |
| APK 文件名 | `HwCmccDm.apk` | `UEInfoCheck.apk` | `ChnTelecomAutoReg.apk` |
| 版本 | 40.0.0.107 | 40.2.0.113 | 40.2.0.104 |
| 服务运营商 | 中国移动 | 电信 + 联通（同包双实现） | 中国电信 |
| 运行身份 | `android.uid.phone`，`coreApp=true` | `android.uid.phone` | `android.uid.phone` |
| 传输通道 | HTTPS | **明文 HTTP** | **短信（SMS）** |
| 是否具备 INTERNET 权限 | ✅ | ✅ | ❌ **无** |
| 数据规模 | 290 类 / 385 KB dex | 27 类 | 17 类 / 79 KB |
| 远程代码下发 | ✅ **有** | ❌ | ❌ |
| 上传应用列表 | ✅ | ❌ | ❌ |
| 采集界面/录屏 | ❌ | ❌ | ❌ |

三者签名证书相同（SHA-256 `603ac6a5…`），即厂商平台密钥，因此都获得了 `android.uid.phone` 这一电话进程级 UID。

---

## 二、① RegService（`com.huawei.regservice` / `HwCmccDm.apk`）

### 架构

实质是一个壳：`com.huawei.regservice` 负责调用华为私有接口（`HwTelephonyManager`、`SystemPropertiesEx`、`SubscriptionManagerEx`）读取数据，业务逻辑全在第三方 SDK **`cn.richinfo.dm`**（DM = Device Management）内，SDK 版本 `4.0.2`，协议 `v2`，平台标识 `dmkey=M100000034`。附带两个 native 库 `libaes-jni.so`、`libmt-jni.so`，用于加解密。

无桌面入口，唯一 Activity 是许可声明页 `EmptyLicenseActivity`，指向 `/hw_product/etc/noticeFile/HwCmccDm.html`。

### 触发点

| 组件 | Action | 导出 |
|---|---|---|
| `SimStateChangeReceiver` | `SIM_STATE_CHANGED`、`BOOT_COMPLETED` | 是 |
| `BluetoothChangeReceiver` | 耳机 / A2DP 连接状态变化 | 是 |
| `BatteryChangeReceiver` | 充电插入 / 拔出 | 是 |
| `VolteAnd5GStateReceiver` | `VOLTE_STATE_SETTING`、`5G_STATE_SETTING` | 否 |
| `DMBroadCastReceiver` | 注册 / 心跳 / APN / 网络采集 / 第三方请求等 14 个 action | 是（受自建 `com.dm.permission.THIRD_SEND` 保护） |

`DMService` 内部有 **240 秒心跳** 与 60 秒调度，并支持用户/平台配置的「休眠时段」（`getSleepStartTime`/`getSleepEndTime`）。运行前置条件：`BusinessUtils.checkIfHaveYDSim()` 为真（存在移动卡），否则直接返回不上报。

### 采集数据（`DMDeviceInfoCollection`，84 个方法）

- **设备标识**：IMEI1/IMEI2、MEID、WLAN MAC（反射 `getFactoryMacAddresses`）、蓝牙 MAC、路由器 BSSID、eUICC ID
- **SIM**：IMSI1/2、ICCID1/2、本机号码1/2（`getLine1Number`）、卡槽数、各槽卡状态、运营商类型（按 IMSI 前缀 46000/46002/46007/46008 判移动）
- **网络**：CELL ID / LAC（双卡各一组）、信号强度 dBm、网络制式、**扫描到的 WiFi 列表**、WLAN 状态、数据卡/语音卡/上网卡归属
- **流量**：移动网络累计流量、WiFi 累计流量（支持时间区间）
- **应用**：**已安装应用列表、预装应用列表**
- **硬件**：CPU 型号（读 `/proc/cpuinfo` 的 Hardware 字段）、RAM、ROM、电池容量/电量/温度、品牌、型号、系统版本、固件版本
- **无线能力**：VoLTE、VoNR、5G 状态与网络模式、R16 5G、载波聚合、SA/700M、国际漫游
- **事件**：充电、蓝牙外设、国际漫游、异常日志、日志文件

另有 `secretCpu`/`secretVer`/`secretName`/`secretVersion` 四项混淆字段，配合 `IS_HIDE_PRODUCT_INFO` 开关，可隐藏真实产品信息后上报。

### 上报端点

基础域名：正式 `https://a.fxltsbl.com`，调试 `https://b.fxltsbl.com`，另有一个 `https://auc.fxltsbl.com`。

| 路径 | 用途 |
|---|---|
| `/sdkService?func=tsdk:postreglog` | 注册 |
| `/sdkService?func=tsdk:posthblog` | 心跳 |
| `/sdkService?func=tsdk:postapplylog` | 应用上报 |
| `/sdkService?func=tsdk:configlog` | 配置日志 |
| `/network`、`/wifiInfo`、`/bluetooth`、`/batteryCharging`、`/internationalRoaming` | 各类采集 |
| `/flowCollectService?func=tsdk:flowcollect` | 流量 |
| `/preInstalledApp` | 预装应用 |
| `/logReport`、`/errorDiagnosis` | 日志 / 异常诊断 |
| `TerminalConfigService/terminalConf?func=tsdk:termimalfullconfig` | 拉取全量配置 |
| `TerminalConfigService/auth?tsdk:sdkAuth` | 鉴权 |
| `https://a.fxltsbl.com/getIpAddr` | 获取出口 IP |
| `https://auc.fxltsbl.com/AuthCenter/datareport?func=tsdk:photoReg` | 拍照采集 |

请求体 AES 加密后 GZIP 压缩，附签名校验。

### 服务端下发能力

可远程配置的项目包括：`autoRegister`、`keepAliveConfig`、`flowcollect`、`network`、`logConfig`、`preinstall`、`switchConfig`、`systemParams`、`urlConfig`、`whitelistconfig`。

`setSwitch()` 会以「控制功能」调用扩展接口改写 **VoLTE / 5G 开关**（含可见性、通知文案）；`FlashlightController` 则通过 `CameraManager` 控制 **闪光灯**。

### ⚠️ 最高风险项：动态代码加载

`DMService.b()` 的实现：

```java
File jar = Q.b(this);                                    // /dynamic/patch.jar
Class c = new DexClassLoader(Q.c(this), getCacheDir(), null,
                             DMService.class.getClassLoader())
             .loadClass("cn.richinfo.dmpatch.business.DMController");
String ver = (String) c.getMethod("getPatchVersion").invoke(null);
if (ver.startsWith("4.0.2"))
    this.b = (DMFuncImpl) c.getMethod("getInstance", Context.class)
                          .invoke(null, this);           // 整体替换实现
```

`DMFuncImpl` 是包含 13 个回调的接口（`onAppReport`、`onHeartbeat`、`onSnapshotRetry` 等），动态包一旦加载成功，**全部业务行为都由下载的 jar 决定**。这意味着静态反编译看到的采集范围不构成上限。

### 合规声明原文（`operators_statement_content`，84 个语言变体）

> 为提供更好的服务，您的设备可能会通过 WLAN、移动数据网络或免费短信，读取并传送您的设备版本信息、设备标识符 (包含 IMEI、MEID、WLAN MAC 地址、eUICC ID)、SIM 卡标识符 (包含 IMSI、MSISDN、ICCID)、网络信息 (包含 IP 地址) 和粗略位置信息给电信运营商……

---

## 三、② UEInfoCheck（`com.huawei.android.UEInfoCheck` / `UEInfoCheck.apk`）

### 架构：一包两运营商

`r` 类依据系统属性做分发：

```java
if (SystemPropertiesEx.getInt("ro.config.hw_optb", 0) != 156) return null;  // 非定制机直接禁用
if ("service_type_ct".equals(type)) return new p(ctx);   // 电信
if ("service_type_cu".equals(type)) return new q(ctx);   // 联通
```

因此，**联通并没有独立 APK**，它复用同一颗组件内的 CU 分支。这一点解释了为何按包名并不能看出联通。

### 端点（全部为明文 HTTP）

| 资源名 | 值 |
|---|---|
| `china_telecom_ctei_reg_address` | `http://typdm.tydevice.com/` |
| `china_telecom_phone_reg_address` | `http://zzhc.vnet.cn/` |
| `china_telecom_tablet_reg_address` | `http://zzhc.vnet.cn:9998/` |
| `china_unicom_reg_address` | `http://dm.wo.com.cn:18080/register` |

电信区分手机与平板，另有独立的 **CTEI**（电信终端设备标识）注册流程，走 GET 请求。

### 触发点与优先级

`UeInfoCheckReceiver`（`directBootAware`）监听开机、`ACTION_SUBINFO_RECORD_UPDATED`、卡槽切换、**配件插拔**（`ACCESSORY_STATUS_CHAGNED`）；`UeInfoCheckNetWorkStateReceiver` 监听 5G 开关变化。

内部 action 及优先级：`data_sub_change` / `new_sim` = 3，`boot` / `5g_state_change` / `accessory_state_change` = 2，`volte_state_change` = 1。

失败重试由 `UeInfoCheckDaemonService` 用 `AlarmManager` 驱动：最多重试 2 次（间隔 3 或 57 分钟），彻底失败后再按 10 分钟或 24 小时重排。守护进程另持 10 分钟定时器与 `WakeLock("UEInfocheck Connectivity")`，通过 `PhoneStateListener`（订阅 `LISTEN_SERVICE_STATE`）监听业务态变化。

### 采集数据（`m` 类，30+ JSON 字段）

```
REGVER, MEID, MODEL, SWVER, UETYPE
IMEI1, IMEI2, MACID
SIM1CDMAIMSI, SIM1ICCID, SIM1LTEIMSI, SIM1TYPE, SIM1CELLID,
SIM1VoLTESW, 5GSW1, VONRSW1, NWTYPE1
SIM2 同上全套
MLPLVER, MSPLVER          ← CDMA 协议版本
NETWORKTYPE, ACCESSTYPE, REGDATE
OSVER, RAM, ROM, DATASIM
```

当某个卡槽无效或信息不可取得，会用占位串填充：`111111111111111`、`222222222222222`、`333333333333333`、`000000000000000`、`FF`。ICCID 不可得时回填 `"0"`。

请求体 `Content-Type: application/encrypted-json`，但**承载于明文 HTTP 之上**，与运营商的通信未启用 TLS。

### 权限与对外能力

申请 `READ_PHONE_STATE`、`ACCESS_COARSE_LOCATION`、`INTERNET`、`WAKE_LOCK`、`ACCESS_WIFI_STATE`、`ACCESS_NETWORK_STATE`，以及一个拼写错误的 `READ_PRIVILELEGED_PHONE_STATE`（原文即如此），和华为私有的 `com.huawei.phone.permission.READ_5G_CONFIG`。

对外暴露 `UeInfoCheckHelpService`（`exported=true`，受自建 `com.huawei.ueinfocheck.permission.AIDTEST` 保护），action 为 `com.huawei.ueinfocheck.action.HELPSERVICE`。

权限说明文案：位置 —— “按运营商要求获取基站信息”；电话 —— “获取 SIM 卡信息与网络连接状态”。

---

## 四、③ AutoRegSms（`com.huawei.android.AutoRegSms` / `ChnTelecomAutoReg.apk`）

### 架构：走短信，不走网络

这是三者中最特殊的一个：**没有 `INTERNET` 权限**，全 dex 对 `java/net`、`PackageManager`、`getInstalledPackages`、`Landroid/content/pm` 的搜索**全部零命中**。它的唯一输出通道是短信。

声明权限仅 6 项：`RECEIVE_BOOT_COMPLETED`、`READ_PHONE_STATE`、`MODIFY_PHONE_STATE`、`SEND_SMS`、`RECEIVE_SMS`、`READ_SMS`。

触发：`AutoRegReceiver` 监听 `BOOT_COMPLETED` 与 `ACTION_HW_SWITCH_SLOT_DONE`（卡槽切换）。仅当网络为**中国电信且不在漫游**时发送（日志原文：`"The network is China Telecom and the MS is not roaming!"`）。

### 报文格式（`l` 类，TLV）

目标号码：**`10659401`**（硬编码）。

三种协议变体：
- **CDMA**（版本 2）：`<a><b>机型前缀</b><c>MEID</c><d>软件版本</d><e>IMEI</e></a>` + CRC32
- **IMS**（版本 4）：额外附带 LTE IMSI
- **卫星**（版本 3）：`<a1><b1>…<b4>…</a1>` 结构

长度硬限制（超限即放弃发送）：

```
机型前缀 ≤ 20 字节      软件版本 ≤ 60 字节
CDMA 总长 ≤ 128         IMS 总长 ≤ 136
缓冲区   new ByteArrayOutputStream(140)
```

运营商码映射（拼接为「运营商+卡槽」两位，如 `"11"`）：
`46000/46002/46004/46007/46008` → 1（移动）；`46001/46006/46009` → 2（联通）；`46003/46005/46011/46012` → 3（电信）；`46015` → 5（卫星）；其余 → 4。

### 采集数据

字段集远小于前两者，仅有：机型前缀（读 `Settings.System` 的 `hw_mc.telecom_dm_manuf_prefix`）、MEID、IMSI、IMEI1/IMEI2、软件版本、LTE IMSI、运营商码、CRC32。另有 `getPesn`（CDMA 设备序列号）与 `isUimSupportMeid` / `setCTUIMDeviceID`（向 UIM 卡写入 MEID）。

本地状态存于 `SharedPreferences("autoreg_para")`：`ct_autoreg_flag`、`ct_autoreg_flag_ims`、`ct_autoreg_svn`、`ct_autoreg_imsi`、`ct_autoreg_lte_imsi`、`send_msg_net_type` 等十余项，用于判断是否已注册、避免重复发送。

**明确结论：不上传应用列表，不采集任何界面内容。**

### 权限说明文案

短信 —— “is used to send SMS messages to your carrier for the purpose of activating services.”

---

## 五、横向对比

### 隐私侵入度排序

| 维度 | ① RegService | ② UEInfoCheck | ③ AutoRegSms |
|---|---|---|---|
| 标识符覆盖 | ★★★★★ | ★★★★☆ | ★★★☆☆ |
| 网络环境（WiFi 列表 / 基站） | ★★★★★ | ★★★☆☆ | ✗ |
| 应用清单 | ★★★★★ | ✗ | ✗ |
| 硬件指纹 | ★★★★★ | ★★★☆☆ | ★☆☆☆☆ |
| 远程控制能力 | ★★★★★（含代码下发） | ★★☆☆☆（配置开关） | ✗ |
| 传输保密性 | ★★★★☆（HTTPS） | ★☆☆☆☆（明文 HTTP） | ★★☆☆☆（短信明文） |
| 透明度（能否审计） | ★★☆☆☆（可被 jar 替换） | ★★★★☆ | ★★★★★ |

### 三点值得注意的差异

**1. 加密并不等于保密。** ② UEInfoCheck 用了 `application/encrypted-json` 加密请求体，但承载于 `http://`。加密只保护报文内容，不保护通信元数据；且服务端地址、请求时序全部可被链路上旁路观测。

**2. 三者从不同时激活。** ① 要求存在移动卡；② 由系统属性 `ro.config.hw_optb` 是否为 156 决定，且内部再分 CT / CU；③ 要求电信卡且非漫游。它们共同构成「全网通机型按实际插卡分派给对应运营商」的机制，并非三套并行采集。

**3. 遮挡程度不同。** ① 的核心逻辑可被 `/dynamic/patch.jar` 整体替换，静态分析的结果可能不是全貌；②③ 行为完全固化在 dex 内，审计结论即最终结论。

---

## 六、共性与深层问题

### 以电话进程身份运行

三者均为 `sharedUserId="android.uid.phone"`，① 另加 `coreApp="true"`。这意味着它们不申请常规权限也能落到电话进程的权限域内，用户侧无法通过权限管理界面切断其数据访问——**权限模型对它们形同虚设**。

### 合规声明是「告知」而非「开关」

① 内嵌 84 种语言的运营商声明，② 有 81 种语言的权限用途说明。但代码中**不存在读取用户同意状态以控制采集开关的逻辑**。真正的开关在服务端下发的配置里（`switchConfig` 的 `isOpen`、`whitelistconfig`），由运营商平台决定。用户无法单方面切断。

### 签名策略使权限边界失效

三者的平台密钥与系统内其他系统组件一致。这带来一个结构性后果：任何 signature 级权限（包括用于守护网络栈的 `MAINLINE_NETWORK_STACK`）对这些组件都是开放的。这是厂商 ROM 的整体签名策略问题，非单个组件的缺陷。

---

## 七、观察结论

1. **应用列表上报是本组内唯一由 ① RegService 承担的能力**，②③ 均不具备。
2. **② UEInfoCheck 的联通分支（`dm.wo.com.cn`）在功能上与电信分支对等**，只是共用同一 APK——按包名检索无法识别出联通。
3. **③ AutoRegSms 的数据面极小（约 130 字节）**，但其持有 `SEND_SMS` + `READ_SMS` + `RECEIVE_SMS`，能力边界大于实际上报内容边界。
4. **三者均不具备界面采集 / 录屏能力**：无 `MediaProjection`、无 `READ_FRAME_BUFFER`、无无障碍服务注册。此类传言与本组件的实测代码不符。
5. **① RegService 的 `DexClassLoader` 动态加载是唯一能让静态分析结论失效的设计**，评估其风险时应以「能力上限」而非「当前代码」为准。
6. **可被安全禁用**：使用uninstall --user 0即可卸载，本项目app经测试正常工作，确认不会自行恢复和导致设备使用问题。
