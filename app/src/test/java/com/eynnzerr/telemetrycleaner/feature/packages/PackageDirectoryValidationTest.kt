package com.eynnzerr.telemetrycleaner.feature.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageDirectoryValidationTest {

    @Test
    fun rejectsBlankMalformedAndCoreFrameworkPackage() {
        assertEquals(AddCustomPackageStatus.InvalidFormat, validateCustomPackageInput("", emptySet()))
        assertEquals(AddCustomPackageStatus.InvalidFormat, validateCustomPackageInput("   ", emptySet()))
        assertEquals(AddCustomPackageStatus.InvalidFormat, validateCustomPackageInput("android", emptySet()))
        assertEquals(AddCustomPackageStatus.InvalidFormat, validateCustomPackageInput("singleword", emptySet()))
        assertEquals(AddCustomPackageStatus.InvalidFormat, validateCustomPackageInput("com.bad package", emptySet()))
        assertEquals(AddCustomPackageStatus.InvalidFormat, validateCustomPackageInput("com.bad;rm", emptySet()))
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
    fun acceptsValidUnlistedPackageName() {
        assertNull(validateCustomPackageInput("  com.vendor.newtelemetry  ", setOf("com.other.pkg")))
    }
}
