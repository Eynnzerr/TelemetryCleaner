package com.eynnzerr.telemetrycleaner

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import org.junit.Assert.assertNotNull
import org.junit.Test

class DestinationSerializationTest {
    @OptIn(InternalSerializationApi::class)
    @Test
    fun allNavKeys_resolveSerializersReflectivelyForNavKeySerializer() {
        val destinations = listOf(
            Destination.Cleaner,
            Destination.Settings,
            Destination.AutoCleanupHistory,
            Destination.PackageDirectory,
            Destination.PackageSearch,
        )
        destinations.forEach { destination ->
            assertNotNull(
                "Missing serializer for ${destination::class.qualifiedName}",
                destination::class.serializer(),
            )
        }
    }
}
