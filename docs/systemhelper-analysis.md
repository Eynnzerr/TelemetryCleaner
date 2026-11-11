# SystemHelper 的权限和数据上报分析

OPPO 预置的 `com.mobiletools.systemhelper` 包含设备注册、信号采样和应用使用统计三条路径。信号采样会把定位结果与设备、SIM 和基站信息放在同一条记录里。应用统计会读取有使用记录的应用，汇总前台时长和按应用计算的网络流量。

## 申请了哪些权限

`AndroidManifest.xml` 共声明 17 项 Android 权限。表中权限名省略 `android.permission.` 前缀。 [Android 权限参考](https://developer.android.com/reference/android/Manifest.permission)

| 用途 | 权限 |
| --- | --- |
| 定位 | `ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`、`ACCESS_BACKGROUND_LOCATION`、`ACCESS_LOCATION_EXTRA_COMMANDS` |
| 电话与 SIM | `READ_PHONE_STATE`、`READ_PRECISE_PHONE_STATE`、`READ_PRIVILEGED_PHONE_STATE` |
| 应用列表和统计 | `QUERY_ALL_PACKAGES`、`PACKAGE_USAGE_STATS` |
| 联网与 Wi-Fi | `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE` |
| 开机与后台任务 | `RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM`、`FOREGROUND_SERVICE` |
| 共享存储 | `READ_EXTERNAL_STORAGE` |

真机包管理器记录显示，粗略、精确及后台定位，电话状态和共享存储读取权限均为默认授予。安装权限里，`READ_PRECISE_PHONE_STATE`、`READ_PRIVILEGED_PHONE_STATE`、`QUERY_ALL_PACKAGES` 和 `PACKAGE_USAGE_STATS` 也显示已授予。实际访问还受系统服务和 AppOps 控制。同一台手机的 AppOps 中，`FINE_LOCATION` 和 `WIFI_SCAN` 有访问记录，`GET_USAGE_STATS` 有拒绝记录。 [Android 应用使用统计接口](https://developer.android.com/reference/android/app/usage/UsageStatsManager)

## 后台任务怎样启动

清单里没有桌面启动入口。`OnBootReceiver` 处理开机事件，初始化 SIM 和网络监听，并安排注册任务。`SIMStateReceiver` 检测 SIM 状态，`ScheduleRegisterReceiver` 和 `AppInfoRegisterReceiver` 分别进入定期注册和应用统计分支。`GetSignalInfoReceiver` 采样并保存信号记录，`SignalInfoRegisterReceiver` 触发批量上报。`c.d.a.b.a` 使用精确闹钟安排这些任务。真机的系统闹钟和耗电记录中可以看到信号采样及上报接收器的唤醒条目。

`GetSignalInfoReceiver.onReceive()` 在该 APK 的 OPPO 路径上以系统的 `device_provisioned` 标志作为定位采样开关，即设备是否已完成初始化。代码为 vivo 单独增加了运营商改进计划的设置检查。联网发送还有条件判断，`control.g` 按连接状态、SIM 和网络类型选择发送或暂存。

## 记录中有哪些数据

下文 `control.*` 指 `com.mobiletools.systemhelper.control` 下的类。

| 记录 | 样本中的字段 | 代码入口 |
| --- | --- | --- |
| 设备注册 | 厂商、型号、系统版本、双卡 IMEI、IMSI、ICCID、网络类型和基站信息 | `c.d.a.a.n`、`control.b.a()` |
| 信号采样 | 经纬度、海拔、采样时间、设备及 SIM 标识、基站标识、RSRP、RSRQ、SINR | `GetSignalInfoReceiver`、`control.d`、`c.d.a.b.n.a()` |
| 应用统计 | 应用名称、包名、统计时间、前台时长、蜂窝数据和 Wi-Fi 流量 | `control.f`、`c.d.a.b.b.a()` |

**位置和信号。** `GetSignalInfoReceiver` 启动百度定位 SDK，并把定位回调里的经纬度和海拔填入信号记录。`control.d` 按 SIM 卡槽读取设备和 SIM 标识、网络类型以及 LTE 或 NR 基站信息。信号记录随后进入本地批次，供信号上报任务读取。

**软件列表、时长和流量。** `control.f` 通过 `getInstalledApplications()` 读取应用名称及 UID，以包名与 `queryAndAggregateUsageStats()` 的结果匹配。进入 `AppInfo` 的是有使用统计的应用记录，序列化前还会过滤以 `com.android` 开头的包名。`NetworkStatsManager.querySummary()` 汇总蜂窝数据和 Wi-Fi 的发送、接收字节数，代码按 UID 将统计关联回应用。这里的流量字段是用量计数。 [Android 网络使用统计接口](https://developer.android.com/reference/android/app/usage/NetworkStatsManager)

## 怎样上报

`control.b.a()` 生成注册 JSON，按任务附带 `SigInfo` 或 `AppInfo`。`control.g` 在满足网络和 SIM 条件时加密载荷，再调用 `c.d.a.b.f` 发起 POST。`c.d.a.b.d` 给出的默认地址是 `http://dm.wo.com.cn:18080/register`，`c.d.a.b.l.h()` 可读取本地配置覆盖地址。清单允许明文 HTTP，代码也实现了 HTTPS 分支。使用默认地址时，JSON 由应用加密，HTTP 请求元数据以明文传输。无法发送的部分记录会写入本地数据库，供后续重试。

设备和 SIM 标识可以把多次记录关联起来。将它们和定位、应用名称及使用时长一并收集，涉及用户活动位置和使用习惯。
