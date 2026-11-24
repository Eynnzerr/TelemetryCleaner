package com.eynnzerr.telemetrycleaner.data

/** Grouping keys for vendor firmware components and built-in browsers in the bundled directory. */
internal enum class PackageFamily {
    CROSS_VENDOR,
    COLOR_OS,
    XIAOMI,
    VIVO,
    HUAWEI,
    HONOR,
    FLYME,
    NUBIA,
    SAMSUNG,
    MOTOROLA,
    ZTE,
    BROWSER,
}

/**
 * Exact component identities for scans. A carrier component may serve multiple carriers.
 * Each physical package is listed once.
 */
internal object PackageDirectory {
    /** Known package variants grouped by their documented firmware family or browser category. */
    val groups: Map<PackageFamily, List<PackageEntry>> = mapOf(
        PackageFamily.CROSS_VENDOR to listOf(
            PackageEntry("com.mobiletools.systemhelper", "SystemHelper", setOf(Carrier.UNICOM)),
        ),
        PackageFamily.COLOR_OS to listOf(
            PackageEntry("com.oppo.ctautoregist", "CTAutoRegist", setOf(Carrier.TELECOM)),
            PackageEntry("com.coloros.regservice", "RegService", setOf(Carrier.MOBILE)),
            PackageEntry("com.oplus.statistics.rom", "User Experience Program", emptySet()),
            PackageEntry("com.nearme.statistics.rom", "User Experience Program (legacy)", emptySet()),
            PackageEntry("com.nearme.deamon", "Statistics Daemon", emptySet()),
            PackageEntry("com.oplus.locationproxy", "Carrier Location Services", emptySet()),
        ),
        PackageFamily.XIAOMI to listOf(
            PackageEntry("com.xiaomi.registration", "AutoRegistration", setOf(Carrier.TELECOM)),
            PackageEntry("com.miui.dmregservice", "RegService", setOf(Carrier.MOBILE)),
            PackageEntry("com.xiaomi.security.onetrack", "SecurityOnetrackService", emptySet()),
            PackageEntry("com.miui.misightservice", "MiSightService", emptySet()),
            PackageEntry("com.miui.analytics", "Analytics", emptySet()),
            PackageEntry("com.miui.spock", "Spock Analytics", emptySet()),
            PackageEntry("android.romstats", "ROM Stats", emptySet()),
            PackageEntry("com.xiaomi.aon", "AON Service", emptySet()),
            PackageEntry("com.xiaomi.touchservice", "Touch Service", emptySet()),
            PackageEntry("com.xiaomi.location.fused", "Fused Location", emptySet()),
        ),
        PackageFamily.VIVO to listOf(
            PackageEntry("com.vivo.devicereg", "Device Management", setOf(Carrier.MOBILE, Carrier.TELECOM)),
        ),
        PackageFamily.HUAWEI to listOf(
            PackageEntry("com.huawei.android.UEInfoCheck", "UEInfoCheck", setOf(Carrier.TELECOM)),
            PackageEntry("com.huawei.android.AutoRegSms", "AutoRegSms", setOf(Carrier.TELECOM)),
            PackageEntry("com.huawei.regservice", "RegService", setOf(Carrier.MOBILE)),
            PackageEntry("com.huawei.bd", "HwUE", emptySet()),
            PackageEntry("com.huawei.hiview", "HiView", emptySet()),
            PackageEntry("com.huawei.hwdiagnosis", "HwDiagnosis", emptySet()),
        ),
        PackageFamily.HONOR to listOf(
            PackageEntry("com.hihonor.android.UEInfoCheck", "UEInfoCheck", setOf(Carrier.TELECOM)),
            PackageEntry("com.hihonor.android.AutoRegSms", "AutoRegSms", setOf(Carrier.TELECOM)),
            PackageEntry("com.hihonor.regservice", "RegService", setOf(Carrier.MOBILE)),
            PackageEntry("com.hihonor.detectrepair", "Smart Diagnosis", emptySet()),
        ),
        PackageFamily.FLYME to listOf(
            PackageEntry("com.meizu.regservice", "RegService", setOf(Carrier.MOBILE)),
            PackageEntry("com.flyme.telecom.usagedata.service", "Telecom Usage Data", emptySet()),
        ),
        PackageFamily.NUBIA to listOf(
            PackageEntry("cn.nubia.register", "RegisterApplication", setOf(Carrier.TELECOM)),
            PackageEntry("cn.nubia.woodpecker", "Woodpecker", emptySet()),
        ),
        PackageFamily.SAMSUNG to listOf(
            PackageEntry("com.samsung.android.rubin.app", "Customization Service", emptySet()),
            PackageEntry("com.samsung.android.knox.analytics.uploader", "Knox Analytics Uploader", emptySet()),
            PackageEntry("com.samsung.android.svcagent", "SVC Agent", emptySet()),
            PackageEntry("com.samsung.carrier.logcollector", "Carrier Log Collector", emptySet()),
        ),
        PackageFamily.MOTOROLA to listOf(
            PackageEntry("com.motorola.regservice", "RegService", emptySet()),
            PackageEntry("com.motorola.ctdm.SmsAutoRegistrationService", "SmsAutoRegistrationService", emptySet()),
            PackageEntry("com.motorola.iqimotmetrics", "IQI Metrics", emptySet()),
        ),
        PackageFamily.ZTE to listOf(
            PackageEntry("com.zte.zdmdaemon", "ZDM Daemon", emptySet()),
            PackageEntry("com.zte.zdmdaemon.install", "ZDM Daemon Installer", emptySet()),
        ),
        PackageFamily.BROWSER to listOf(
            PackageEntry("com.android.browser", "Mi Browser / System Browser", emptySet()),
            PackageEntry("com.heytap.browser", "HeyTap Browser", emptySet()),
            PackageEntry("com.nearme.browser", "NearMe Browser", emptySet()),
            PackageEntry("com.vivo.browser", "vivo Browser", emptySet()),
            PackageEntry("com.huawei.browser", "Huawei Browser", emptySet()),
            PackageEntry("com.hihonor.browser", "Honor Browser", emptySet()),
            PackageEntry("com.sec.android.app.sbrowser", "Samsung Internet", emptySet()),
            PackageEntry("cn.nubia.browser", "Nubia Browser", emptySet()),
            PackageEntry("com.meizu.flyme.browser", "Flyme Browser", emptySet()),
            PackageEntry("com.motorola.browser", "Moto Browser", emptySet()),
            PackageEntry("com.zte.browser", "ZTE Browser", emptySet()),
        ),
    )

    /** Indexes every directory entry by the exact package name used for scanning. */
    val variants = groups.values.flatten().associateBy(PackageEntry::packageName)

    /** Packages eligible for automatic cleanup after the on-device system package checks. */
    val autoCleanupNames = setOf(
        "com.mobiletools.systemhelper",
        "com.oppo.ctautoregist",
        "com.coloros.regservice",
        "com.miui.dmregservice",
    )
}
