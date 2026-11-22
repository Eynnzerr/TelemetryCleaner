package com.eynnzerr.telemetrycleaner.feature.packages

import com.eynnzerr.telemetrycleaner.BuildConfig
import com.eynnzerr.telemetrycleaner.data.CriticalSystemPackages
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageDirectoryValidationTest {

    @Test
    fun guardlistHasNoDuplicatesAndDoesNotOverlapBundledDirectory() {
        assertEquals(
            "CriticalSystemPackages.entries contains duplicate package names",
            CriticalSystemPackages.entries.size,
            CriticalSystemPackages.names.size,
        )
        val overlap = CriticalSystemPackages.names intersect PackageDirectory.variants.keys
        assertTrue("Conflicting package names: $overlap", overlap.isEmpty())
    }

    @Test
    fun rejectsBlankAndMalformedPackageNames() {
        assertEquals(AddCustomPackageStatus.InvalidFormat, validateCustomPackageInput("", emptySet()))
        assertEquals(AddCustomPackageStatus.InvalidFormat, validateCustomPackageInput("   ", emptySet()))
        assertEquals(AddCustomPackageStatus.InvalidFormat, validateCustomPackageInput("singleword", emptySet()))
        assertEquals(AddCustomPackageStatus.InvalidFormat, validateCustomPackageInput("com.bad package", emptySet()))
        assertEquals(AddCustomPackageStatus.InvalidFormat, validateCustomPackageInput("com.bad;rm", emptySet()))
    }

    @Test
    fun rejectsCriticalSystemAndRecoveryPackages() {
        val criticalSamples = listOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.permissioncontroller",
            "com.google.android.networkstack",
            "com.google.android.documentsui",
            "com.google.android.providers.media.module",
            "com.google.android.webview",
            "com.miui.securitycenter",
            "com.xiaomi.finddevice",
            "com.android.launcher",
            "com.bbk.launcher2",
            "com.huawei.android.launcher",
            "cn.nubia.launcher",
            "com.android.shell",
            "moe.shizuku.privileged.api",
            BuildConfig.APPLICATION_ID,
        )
        for (name in criticalSamples) {
            assertEquals(name, AddCustomPackageStatus.CriticalPackage, validateCustomPackageInput(name, emptySet()))
        }
    }

    @Test
    fun rejectsAlreadyBundledOrAlreadyAddedPackages() {
        assertEquals(
            AddCustomPackageStatus.AlreadyBundled,
            validateCustomPackageInput("com.mobiletools.systemhelper", emptySet()),
        )
        assertEquals(
            AddCustomPackageStatus.AlreadyAdded,
            validateCustomPackageInput(" com.vendor.custom ", setOf("com.vendor.custom")),
        )
    }

    @Test
    fun contestedVendorAndTelemetryPackagesRemainEligibleForTesting() {
        val contested = listOf(
            "com.miui.guardprovider",
            "com.miui.daemon",
            "com.miui.msa.global",
            "com.xiaomi.metoknlp",
            "com.xiaomi.xmsf",
            "com.coloros.phonemanager",
            "com.oplus.safecenter",
            "com.oplus.statistics.rom",
            "com.coloros.activation",
            "com.oplus.olc",
            "com.iqoo.secure",
            "com.huawei.systemmanager",
            "com.huawei.hwid",
            "com.google.android.gms",
            "  com.vendor.newtelemetry  ",
        )
        for (name in contested) {
            assertNull(name, validateCustomPackageInput(name, setOf("com.other.pkg")))
        }
    }
}
