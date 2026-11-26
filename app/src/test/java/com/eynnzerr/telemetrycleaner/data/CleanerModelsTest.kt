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

        // Default uninstall (both manual and auto-cleanup) excludes disabled packages
        val defaultUninstall = snapshot.targetsFor(
            action = PackageAction.UNINSTALL,
            selectedUserIds = setOf(0),
            requireVerifiedSystemIdentity = false,
        )
        assertEquals(listOf(ChangeTarget(pkgInstalled.packageName, 0)), defaultUninstall)

        // Explicit includeDisabledForUninstall = true includes disabled packages when requested
        val uninstallIncludingDisabled = snapshot.targetsFor(
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
            uninstallIncludingDisabled,
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

    @Test
    fun targetsFor_bindsUserSerialNumberForNonCurrentProfiles() {
        val owner = AndroidUser(0, "Owner", serialNumber = 0L)
        val clone = AndroidUser(10, "system_clone", serialNumber = 99L)
        val pkg = PackageEntry("com.example.pkg", "Pkg", emptySet())
        val snapshot = ScanSnapshot(
            currentUserId = owner.id,
            users = listOf(owner, clone),
            packages = listOf(
                DetectedPackage(
                    entry = pkg,
                    versionName = "1.0",
                    versionCode = 1L,
                    systemPath = "/system/app/Pkg",
                    users = mapOf(owner.id to PackageState.INSTALLED, clone.id to PackageState.INSTALLED),
                    hasVerifiedSystemIdentity = true,
                ),
            ),
        )

        val targets = snapshot.targetsFor(
            action = PackageAction.UNINSTALL,
            selectedUserIds = setOf(owner.id, clone.id),
            requireVerifiedSystemIdentity = false,
        )
        assertEquals(
            listOf(
                ChangeTarget(pkg.packageName, owner.id, userSerialNumber = null),
                ChangeTarget(pkg.packageName, clone.id, userSerialNumber = 99L),
            ),
            targets,
        )
    }

    @Test
    fun losesApkOnUninstall_detectsLastUserAppCopyAccurately() {
        val entry = PackageEntry("com.example.userapp", "UserApp", emptySet())
        val multiSpaceUserApp = DetectedPackage(
            entry = entry,
            versionName = "1.0",
            versionCode = 1L,
            systemPath = "/data/app/base.apk",
            users = mapOf(0 to PackageState.INSTALLED, 10 to PackageState.INSTALLED),
            hasVerifiedSystemIdentity = false,
            hasSystemCopy = false,
        )
        val systemApp = multiSpaceUserApp.copy(hasSystemCopy = true)

        assertFalse(systemApp.losesApkOnUninstall(setOf(0, 10)))
        assertFalse(multiSpaceUserApp.losesApkOnUninstall(setOf(0)))
        assertTrue(multiSpaceUserApp.losesApkOnUninstall(setOf(0, 10)))

        val disabledOtherSpace = multiSpaceUserApp.copy(
            users = mapOf(0 to PackageState.INSTALLED, 10 to PackageState.DISABLED),
        )
        assertFalse(disabledOtherSpace.losesApkOnUninstall(setOf(0, 10)))

        val hiddenOtherSpace = multiSpaceUserApp.copy(
            users = mapOf(0 to PackageState.INSTALLED, 10 to PackageState.HIDDEN),
        )
        assertFalse(hiddenOtherSpace.losesApkOnUninstall(setOf(0, 10)))
    }

    @Test
    fun targetsFor_handlesHiddenPackagesForRestoreAndExcludesFromDefaultUninstall() {
        val hiddenEntry = PackageEntry("com.vivo.devicereg", "Device Management", emptySet())
        val detected = DetectedPackage(
            entry = hiddenEntry,
            versionName = "5.4.0.0",
            versionCode = 54000L,
            systemPath = "/system/app/DeviceReg",
            users = mapOf(0 to PackageState.HIDDEN),
            hasVerifiedSystemIdentity = true,
        )

        assertTrue(detected.hasVisibleRecord())
        assertTrue(detected.hasActionIn(setOf(0)))
        assertTrue(detected.targetsFor(PackageAction.UNINSTALL, setOf(0), requireVerifiedSystemIdentity = false).isEmpty())
        assertTrue(detected.targetsFor(PackageAction.DISABLE, setOf(0), requireVerifiedSystemIdentity = false).isEmpty())
        assertEquals(
            listOf(ChangeTarget(hiddenEntry.packageName, 0)),
            detected.targetsFor(PackageAction.RESTORE, setOf(0), requireVerifiedSystemIdentity = false),
        )
    }
}
