package com.eynnzerr.telemetrycleaner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanerModelsTest {
    @Test
    fun isExactPackageName_acceptsValidAndroidIdentifiersAndAndroidSystem() {
        assertTrue(isExactPackageName("android"))
        assertTrue(isExactPackageName("com.oppo.ctautoregist"))
        assertTrue(isExactPackageName("com.huawei.android.UEInfoCheck"))
        assertTrue(isExactPackageName("cn.nubia.register_v2"))
    }

    @Test
    fun isExactPackageName_rejectsInvalidIdentifiers() {
        assertFalse(isExactPackageName(""))
        assertFalse(isExactPackageName("singleword"))
        assertFalse(isExactPackageName("com.123invalid"))
        assertFalse(isExactPackageName("com.example."))
        assertFalse(isExactPackageName(".com.example"))
        assertFalse(isExactPackageName("com.example;rm -rf /"))
        assertFalse(isExactPackageName("com.example.app name"))
    }

    @Test
    fun targetsFor_respectsActionEligibilityAndUserDisabledSemantics() {
        val pkgInstalled = PackageEntry("com.example.installed", "Installed", emptySet())
        val pkgUserDisabled = PackageEntry("com.example.userdisabled", "UserDisabled", emptySet())
        val pkgSystemDisabled = PackageEntry("com.example.sysdisabled", "SysDisabled", emptySet())
        val pkgRestorable = PackageEntry("com.example.restorable", "Restorable", emptySet())

        val snapshot = ScanSnapshot(
            currentUserId = 0,
            users = listOf(AndroidUser(0, "Owner", serialNumber = 0L)),
            packages = listOf(
                DetectedPackage(
                    entry = pkgInstalled,
                    versionName = "1.0",
                    versionCode = 1L,
                    systemPath = "/system/app/A",
                    users = mapOf(0 to PackageState.INSTALLED),
                    hasVerifiedSystemIdentity = true,
                ),
                DetectedPackage(
                    entry = pkgUserDisabled,
                    versionName = "1.0",
                    versionCode = 1L,
                    systemPath = "/system/app/B",
                    users = mapOf(0 to PackageState.DISABLED),
                    hasVerifiedSystemIdentity = true,
                    userDisabledIds = setOf(0),
                ),
                DetectedPackage(
                    entry = pkgSystemDisabled,
                    versionName = "1.0",
                    versionCode = 1L,
                    systemPath = "/system/app/C",
                    users = mapOf(0 to PackageState.DISABLED),
                    hasVerifiedSystemIdentity = true,
                    userDisabledIds = emptySet(),
                ),
                DetectedPackage(
                    entry = pkgRestorable,
                    versionName = "1.0",
                    versionCode = 1L,
                    systemPath = "/system/app/D",
                    users = mapOf(0 to PackageState.RESTORABLE),
                    hasVerifiedSystemIdentity = false,
                ),
            ),
        )

        // Manual uninstall excludes disabled packages when includeDisabledForUninstall = false
        val manualUninstall = snapshot.targetsFor(
            action = PackageAction.UNINSTALL,
            selectedUserIds = setOf(0),
            requireVerifiedSystemIdentity = false,
            includeDisabledForUninstall = false,
        )
        assertEquals(listOf(ChangeTarget(pkgInstalled.packageName, 0)), manualUninstall)

        // Auto cleanup uninstall includes disabled packages when includeDisabledForUninstall = true
        val autoUninstall = snapshot.targetsFor(
            action = PackageAction.UNINSTALL,
            selectedUserIds = setOf(0),
            requireVerifiedSystemIdentity = true,
            includeDisabledForUninstall = true,
        )
        assertEquals(
            listOf(
                ChangeTarget(pkgInstalled.packageName, 0),
                ChangeTarget(pkgUserDisabled.packageName, 0),
                ChangeTarget(pkgSystemDisabled.packageName, 0),
            ),
            autoUninstall,
        )

        // Disable targets only INSTALLED packages
        val disableTargets = snapshot.targetsFor(
            action = PackageAction.DISABLE,
            selectedUserIds = setOf(0),
            requireVerifiedSystemIdentity = false,
        )
        assertEquals(listOf(ChangeTarget(pkgInstalled.packageName, 0)), disableTargets)

        // Restore targets RESTORABLE and user-disabled packages, excluding system-disabled ones
        val restoreTargets = snapshot.targetsFor(
            action = PackageAction.RESTORE,
            selectedUserIds = setOf(0),
            requireVerifiedSystemIdentity = false,
        )
        assertEquals(
            listOf(
                ChangeTarget(pkgUserDisabled.packageName, 0),
                ChangeTarget(pkgRestorable.packageName, 0),
            ),
            restoreTargets,
        )
    }
}
