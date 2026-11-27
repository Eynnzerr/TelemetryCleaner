# 机型实测与使用反馈

整理自 [GitHub Issues](https://github.com/Eynnzerr/TelemetryCleaner/issues?q=is%3Aissue) 的社区真机反馈（截至 2026-11-22，涉及 v1.3–v1.4）。表中「—」表示原反馈未提供，欢迎在 [Discussions](https://github.com/Eynnzerr/TelemetryCleaner/discussions) 继续提交新机型实测。

## 各厂商实测速览

| 厂商 | 实测结论 | 注意点与分析报告 |
| --- | --- | --- |
| **小米 / REDMI** | 免 Root 可正常卸载运营商三项及小米三项组件 | `com.miui.analytics` 卸载后可能复活；部分 HyperOS 4 机型无 `SecurityOnetrackService` |
| **华为 / 荣耀** | 免 Root 可正常卸载电信、移动三项组件 | 部分机型 `UEInfoCheck` 同包内置联通分支；[华为报告](reports/huawei-telemetry-components.md) |
| **OPPO / 一加 / realme** | 联通、电信、移动组件可卸载；部分机型未预装联通组件 | realme 有 1 例反馈卸载移动组件重启后流量变卡（改用 Root 停用正常）；[OPPO 报告](reports/oppo-telemetry-components.md) |
| **三星（国行）** | One UI 8.5 可免 Root 跨空间卸载 `SystemHelper` | **One UI 9.0 / Android 17** 在 v1.3 扫描阶段会触发 Watchdog 整机重启（[#5](https://github.com/Eynnzerr/TelemetryCleaner/issues/5)） |
| **vivo / iQOO** | 联通 `SystemHelper` 可免 Root 卸载；`com.vivo.devicereg` 需 Root 卸载 | Root 实测卸载后通话录音正常；[vivo 报告](reports/vivo-telemetry-components.md) |

## 小米 / REDMI

- 运营商三项：联通 `SystemHelper`（`com.mobiletools.systemhelper`）、电信 `AutoRegistration`（`com.xiaomi.registration`）、移动 `RegService`（`com.miui.dmregservice`）
- 小米三项（v1.4 新增）：`SecurityOnetrackService`（`com.xiaomi.security.onetrack`）、`MiSightService`（`com.miui.misightservice`）、`Analytics`（`com.miui.analytics`）

| 机型 | 系统版本 | 工具 / 权限 | 卸载范围 | 实测结果 | 来源 |
| --- | --- | --- | --- | --- | --- |
| 小米 14 Pro | HyperOS `1.0.5.0.UNBCNXM` | v1.4 · Shizuku | 全部六项 | 主空间均卸载成功 | [#31 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/31#issuecomment-7004983516) |
| 小米 14 Pro | HyperOS 3 | Shizuku | 全部六项 | 自述均已卸载，使用正常（附卸载前截图） | [#40](https://github.com/Eynnzerr/TelemetryCleaner/issues/40) |
| 小米 14 Ultra | HyperOS `3.0.306.0` | v1.3 · Shizuku | 运营商三项 | 均卸载成功，日常使用正常 | [#18](https://github.com/Eynnzerr/TelemetryCleaner/issues/18) |
| 小米（未说明型号） | HyperOS `3.0.306.0` | ADB（`uninstall --user 0`） | 电信、移动两项 | 重启后通话、短信与流量均正常 | [#26](https://github.com/Eynnzerr/TelemetryCleaner/issues/26) · [#21 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/21#issuecomment-6988262763) |
| 小米 15 Pro | HyperOS `3.0.309.0.WOBCNXM.C11` | v1.4 · Shizuku | 全部六项 | 均卸载成功 | [#31 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/31#issuecomment-7011264238) |
| 小米 17 | HyperOS `4.0.6.0` | Shizuku | 运营商及小米组件 | 自述全部清除，使用正常 | [#41](https://github.com/Eynnzerr/TelemetryCleaner/issues/41) |
| 小米 17 Pro Max | HyperOS `4.0.5.0.XPBCNXM.D01` | v1.4 · Shizuku | 运营商三项、`MiSightService`、`Analytics` | 5 项均卸载成功（未检出 `SecurityOnetrackService`） | [#31](https://github.com/Eynnzerr/TelemetryCleaner/issues/31) |
| REDMI K90 Pro Max | HyperOS `4.0.6.0.XPMCNXM.D01` | — | 三项（未逐一列名） | 卸载后正常重启，未卡开机 | [#12 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/12#issuecomment-6905915536) |
| REDMI K100 Pro | — | Shizuku | 运营商三项 | 主空间与 XSpace 均已卸载，使用正常 | [#12](https://github.com/Eynnzerr/TelemetryCleaner/issues/12) |
| REDMI K100 Pro Max | HyperOS `4.0.8.0.XGNCNXM.D02` | v1.4 · Shizuku | 运营商三项、`MiSightService`、`Analytics` | 截图确认 5 项卸载成功、剩余 0 个已安装（原文字沿用 6 项模板），重启不卡 Logo | [#31 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/31#issuecomment-7062126228) · [重启反馈](https://github.com/Eynnzerr/TelemetryCleaner/issues/31#issuecomment-7072634316) |

> 补充：社区反馈 MIUI 14 卸载后不卡米、`com.miui.analytics` 卸载后可能复活（疑与应用商店联动）、HyperOS 3 上 `pm suspend` 无效（[#2 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/2#issuecomment-6943223760)、[suspend 测试](https://github.com/Eynnzerr/TelemetryCleaner/issues/2#issuecomment-6958290836)、[小米三项反编译分析](https://github.com/Eynnzerr/TelemetryCleaner/issues/2#issuecomment-7067375637)）。

## 华为 / 荣耀

| 机型 | 系统版本 | 卸载组件 | 实测结果 | 来源 |
| --- | --- | --- | --- | --- |
| 华为（未说明型号） | HarmonyOS `4.2.0.196 (C00E182R8P6)` | `com.huawei.regservice`、`com.huawei.android.UEInfoCheck`、`com.huawei.android.AutoRegSms` | 主空间均卸载成功，未自行恢复，无功能异常 | [#4](https://github.com/Eynnzerr/TelemetryCleaner/issues/4) · [分析报告](reports/huawei-telemetry-components.md) |
| 荣耀 50 Pro | — | `com.hihonor.regservice`、`com.hihonor.android.UEInfoCheck`、`com.hihonor.android.AutoRegSms` | 自述识别出的 3 项已全部卸载，暂未遇到异常（附卸载前截图） | [#23](https://github.com/Eynnzerr/TelemetryCleaner/issues/23) |

## OPPO / 一加 / realme

| 机型 | 系统版本 | 实测结果 | 来源 |
| --- | --- | --- | --- |
| OPPO（作者自测） | ColorOS | `SystemHelper`、`CTAutoRegist`、`RegService` 卸载及重启均正常 | [分析报告](reports/oppo-telemetry-components.md) · [#21 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/21#issuecomment-6944764017) |
| 一加 12 | ColorOS 16 | 仅检出电信 `CTAutoRegist` 与移动 `RegService`，查国行固件确认本身未预装 `SystemHelper` | [#6](https://github.com/Eynnzerr/TelemetryCleaner/issues/6) |
| realme（未说明型号） | realme UI 7.0 | 联通、电信已卸载；移动 `com.coloros.regservice` 卸载重启后反馈流量变卡（孤例），改用 Root `pm disable-user` 停用后显示「已冻结」且可恢复 | [#21](https://github.com/Eynnzerr/TelemetryCleaner/issues/21) · [停用截图](https://github.com/Eynnzerr/TelemetryCleaner/issues/21#issuecomment-6945151632) · [重启反馈](https://github.com/Eynnzerr/TelemetryCleaner/issues/21#issuecomment-6970161717) |

## 三星（国行）

| 机型 | 系统版本 | 工具 / 权限 | 实测结果 | 来源 |
| --- | --- | --- | --- | --- |
| Galaxy S24 Ultra（`SM-S9280`） | One UI 8.5 / Android 16 | Shizuku | 主空间、应用分身（`DUAL_APP`）与安全文件夹（`Secure Folder`）内的 `SystemHelper` 均卸载成功，重启及连扫两日未恢复（分身/安全文件夹需在设置勾选检测范围） | [#29](https://github.com/Eynnzerr/TelemetryCleaner/issues/29) · [后续](https://github.com/Eynnzerr/TelemetryCleaner/issues/29#issuecomment-7046241686) · [#8 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/8#issuecomment-6914128036) |
| Galaxy Z Fold8（`SM-F9710`） | One UI 9.0 / Android 17（`F9710ZCU3AZJ8`） | v1.3 · Shizuku | **严重异常**：扫描阶段因 `pm dump` 触发 AMS 全量 dump 持锁，多次引发 `system_server` Watchdog 整机重启；未卡死时单轮扫描耗时约 2 分钟（原帖附批量 `DUMP_TRANSACTION` 修复方案） | [#5](https://github.com/Eynnzerr/TelemetryCleaner/issues/5) |

## vivo / iQOO

| 机型 | 系统版本 | 权限 / 方式 | 实测结果 | 来源 |
| --- | --- | --- | --- | --- |
| vivo X200 Ultra（`V2454DA`） | OriginOS 6 / Android 16 · `PD2454C_A_16.1.17.2.W10` | KernelSU Root | `com.vivo.devicereg`（`5.4.0.0`）卸载成功且可恢复，通话与通话录音正常；`SystemHelper` 固件有包但默认未注册 | [#7](https://github.com/Eynnzerr/TelemetryCleaner/issues/7) · [分析报告](reports/vivo-telemetry-components.md) |
| iQOO 15 / vivo S30 Pro mini / iQOO 11 | Android 13 / 16 | Shizuku | 联通 `SystemHelper` 卸载成功（`com.vivo.devicereg` 失败） | [#3 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/3#issuecomment-6857652051) · [#3 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/3#issuecomment-6888108641) · [#25 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/25#issuecomment-6970639283) |
| vivo / iQOO 多机型（21 份样本） | Android 11–17 | Shizuku / ADB | `com.vivo.devicereg`（`5.1.5.1`–`5.5.0.0`）卸载与停用均被 PMS 保护名单拦截：Android 11–16 报 `DELETE_FAILED_USER_RESTRICTED`，Android 17 报 `only root can delete system app for a particular user`，停用报 `no root permission`，ADB `suspend` 亦失败 | 见下方清单 · [#30 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/30#issuecomment-7007767143) |

<details>
<summary>展开查看 21 份免 Root（Shizuku）卸载 <code>com.vivo.devicereg</code> 失败的完整机型清单</summary>

「用户受限」对应 `DELETE_FAILED_USER_RESTRICTED`，「需 Root」对应 `only root can delete system app for a particular user`；以下样本均在主空间执行，回退停用亦失败。

| 机型（代号） | 系统 / 构建号 | App | 组件版本 | 卸载错误 | 来源 |
| --- | --- | --- | --- | --- | --- |
| iQOO 11（`V2243A`） | OriginOS 3 / Android 13 · `TP1A.220624.014` | v1.4 | `5.1.5.1` | 用户受限 | [#25](https://github.com/Eynnzerr/TelemetryCleaner/issues/25) |
| iQOO 11S（`V2304A`） | Android 14 · `PD2304_A_14.1.14.2.W10.V000L1` | v1.4 | `5.2.2.3` | 用户受限 | [#3 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/3#issuecomment-7031975942) |
| iQOO 12（`V2307A`） | Android 14 · `UP1A.231005.007` | v1.4 | `5.2.2.0` | 用户受限 | [#33](https://github.com/Eynnzerr/TelemetryCleaner/issues/33) |
| iQOO 12（`V2307A`） | Android 16 · `PD2307C_A_16.2.21.1.W10` | v1.4 | `5.5.0.0` | 用户受限 | [#32](https://github.com/Eynnzerr/TelemetryCleaner/issues/32) |
| iQOO 13（`V2408A`） | Android 16 · `PD2408E_A_16.1.19.11.W10` | v1.3 | `5.4.0.0` | 用户受限 | [#10](https://github.com/Eynnzerr/TelemetryCleaner/issues/10) |
| iQOO 15（`V2505A`） | Android 16 · `PD2505E_A_16.0.30.14.W10` | v1.3 | `5.4.0.0` | 用户受限 | [#3 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/3#issuecomment-6857652051) |
| iQOO 15（`V2505A`） | Android 17 · `PD2505E_A_17.1.11.4.W10` | v1.3 | `5.5.0.0` | 需 Root | [#7 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/7#issuecomment-6896961948) |
| iQOO 15（`V2505A`） | Android 17 · `PD2505E_A_17.1.11.4.W10` | v1.4 | `5.5.0.0` | 需 Root | [#30](https://github.com/Eynnzerr/TelemetryCleaner/issues/30) |
| iQOO 15 Ultra（`V2546A`） | Android 17 · `PD2546C_A_17.1.10.17.W10` | v1.4 | `5.5.0.0` | 需 Root | [#38](https://github.com/Eynnzerr/TelemetryCleaner/issues/38) |
| iQOO Neo9 Pro（`V2339A`） | Android 16 · `PD2339D_A_16.2.13.1.W10` | v1.3 | `5.4.0.0` | 用户受限 | [#13](https://github.com/Eynnzerr/TelemetryCleaner/issues/13) |
| iQOO Neo10（`V2425A`） | Android 16 · `PD2425_A_16.1.17.10.W10` | v1.4 | `5.4.0.0` | 用户受限 | [#7 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/7#issuecomment-7004260545) |
| iQOO Neo10 Pro+（`V2463A`） | Android 16 · `PD2463D_A_16.1.21.6.W10` | v1.4 | `5.4.0.0` | 用户受限 | [#36](https://github.com/Eynnzerr/TelemetryCleaner/issues/36) |
| iQOO Z6x（`V2164KA`） | Android 11 · `RP1A.200720.012` | v1.4 | `5.1.5.1` | 用户受限 | [#39](https://github.com/Eynnzerr/TelemetryCleaner/issues/39) |
| iQOO Z9（`V2361A`） | Android 16 · `PD2361B_A_16.2.12.2.W10` | v1.3 | `5.4.0.0` | 用户受限 | [#9](https://github.com/Eynnzerr/TelemetryCleaner/issues/9) |
| iQOO Z9（`V2361A`） | Android 16 · `PD2361_A_16.2.12.2.W10` | v1.4 | `5.4.0.0` | 用户受限 | [#35](https://github.com/Eynnzerr/TelemetryCleaner/issues/35) |
| iQOO Z9x（`V2353A`） | Android 14 · `PD2353_A_14.0.14.16.W10.V000L1` | v1.4 | `5.3.0.6` | 用户受限 | [#37](https://github.com/Eynnzerr/TelemetryCleaner/issues/37) |
| iQOO Z10 Turbo Pro（`V2453A`） | Android 16 · `PD2453_A_16.1.11.2.W10` | v1.3 | `5.4.0.0` | 用户受限 | [#16](https://github.com/Eynnzerr/TelemetryCleaner/issues/16) |
| iQOO Z10 Turbo+（`V2507A`） | Android 16 · `PD2507_A_16.1.16.3.W10` | v1.4 | `5.4.0.0` | 用户受限 | [#3 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/3#issuecomment-7034905329) |
| vivo S30 Pro mini（`V2465A`） | Android 16 · `PD2465B_A_16.1.13.5.W10` | v1.3 | `5.4.0.0` | 用户受限 | [#3 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/3#issuecomment-6888108641) |
| vivo Y100t（`V2314DA`） | Android 13 · `TP1A.220624.014` | v1.4 | `5.2.2.2` | 用户受限 | [#30 评论](https://github.com/Eynnzerr/TelemetryCleaner/issues/30#issuecomment-7002735856) |
| vivo Y500（`V2506A`） | Android 16 · `PD2506B_A_16.1.15.0.W10` | v1.3 | `5.4.0.0` | 用户受限 | [#15](https://github.com/Eynnzerr/TelemetryCleaner/issues/15) |

</details>
