package com.eynnzerr.telemetrycleaner.data

/** Firmware families represented by the bundled package directory. */
internal enum class PackageFamily { CROSS_VENDOR, COLOR_OS, XIAOMI, VIVO, HUAWEI, HONOR, FLYME, NUBIA }

/**
 * Exact carrier component identities. Several ROMs use different package names, and one
 * component may handle registration for two carriers. Each physical package is listed once.
 */
internal object PackageDirectory {
    /** Known package variants grouped by their documented firmware family. */
    val groups: Map<PackageFamily, List<PackageEntry>> = mapOf(
        PackageFamily.CROSS_VENDOR to listOf(
            PackageEntry("com.mobiletools.systemhelper", "SystemHelper", setOf(Carrier.UNICOM)),
        ),
        PackageFamily.COLOR_OS to listOf(
            PackageEntry("com.oppo.ctautoregist", "CTAutoRegist", setOf(Carrier.TELECOM)),
            PackageEntry("com.coloros.regservice", "RegService", setOf(Carrier.MOBILE)),
        ),
        PackageFamily.XIAOMI to listOf(
            PackageEntry("com.xiaomi.registration", "AutoRegistration", setOf(Carrier.TELECOM)),
            PackageEntry("com.miui.dmregservice", "RegService", setOf(Carrier.MOBILE)),
        ),
        PackageFamily.VIVO to listOf(
            PackageEntry("com.vivo.devicereg", "Device Management", setOf(Carrier.MOBILE, Carrier.TELECOM)),
        ),
        PackageFamily.HUAWEI to listOf(
            PackageEntry("com.huawei.android.UEInfoCheck", "UEInfoCheck", setOf(Carrier.TELECOM)),
            PackageEntry("com.huawei.android.AutoRegSms", "AutoRegSms", setOf(Carrier.TELECOM)),
            PackageEntry("com.huawei.regservice", "RegService", setOf(Carrier.MOBILE)),
        ),
        PackageFamily.HONOR to listOf(
            PackageEntry("com.hihonor.android.UEInfoCheck", "UEInfoCheck", setOf(Carrier.TELECOM)),
            PackageEntry("com.hihonor.android.AutoRegSms", "AutoRegSms", setOf(Carrier.TELECOM)),
            PackageEntry("com.hihonor.regservice", "RegService", setOf(Carrier.MOBILE)),
        ),
        PackageFamily.FLYME to listOf(
            PackageEntry("com.meizu.regservice", "RegService", setOf(Carrier.MOBILE)),
        ),
        PackageFamily.NUBIA to listOf(
            PackageEntry("cn.nubia.register", "RegisterApplication", setOf(Carrier.TELECOM)),
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
