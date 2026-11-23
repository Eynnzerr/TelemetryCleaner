package com.eynnzerr.telemetrycleaner

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
class DestinationSerializationTest {

    @Test
    fun allNavKeys_resolveSerializersReflectivelyForNavKeySerializer() {
        val destinations = listOf(
            Destination.Cleaner,
            Destination.Settings,
            Destination.AutoCleanupHistory,
            Destination.PackageDirectory,
        )
        destinations.forEach { destination ->
            assertNotNull(
                "Missing serializer for ${destination::class.qualifiedName}",
                destination::class.serializer(),
            )
        }
    }

    @Test
    fun navBackStackSerializer_roundTripsMultiDestinationStackByClassName() {
        val serializer = NavBackStackSerializer(NavKeySerializer<NavKey>())
        val original = NavBackStack<NavKey>(
            Destination.Cleaner,
            Destination.Settings,
            Destination.PackageDirectory,
            Destination.AutoCleanupHistory,
        )

        val encodedTokens = mutableListOf<Any>()
        serializer.serialize(TokenListEncoder(encodedTokens), original)

        val restored = serializer.deserialize(TokenListDecoder(encodedTokens))
        assertEquals(
            listOf<NavKey>(
                Destination.Cleaner,
                Destination.Settings,
                Destination.PackageDirectory,
                Destination.AutoCleanupHistory,
            ),
            restored.toList(),
        )
        assertEquals(Destination.AutoCleanupHistory, restored.last())
        restored.removeAt(restored.lastIndex)
        assertEquals(Destination.PackageDirectory, restored.last())
        restored.removeAt(restored.lastIndex)
        assertEquals(Destination.Settings, restored.last())
        restored.removeAt(restored.lastIndex)
        assertEquals(Destination.Cleaner, restored.last())
    }

    /** Minimal pure-JVM serializer sink so NavKeySerializer's reflection path runs without Android Bundle. */
    private class TokenListEncoder(private val tokens: MutableList<Any>) : AbstractEncoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()

        override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int) = apply {
            tokens.add(collectionSize)
        }

        override fun encodeString(value: String) {
            tokens.add(value)
        }
    }

    private class TokenListDecoder(private val tokens: List<Any>) : AbstractDecoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
        private var cursor = 0
        private var elementIndices = ArrayDeque<Pair<Int, Int>>()

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
            val expectedCount = if (descriptor.kind == StructureKind.LIST) {
                tokens[cursor++] as Int
            } else {
                descriptor.elementsCount
            }
            elementIndices.addLast(0 to expectedCount)
            return this
        }

        override fun decodeSequentially(): Boolean = false

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            val (current, count) = elementIndices.last()
            if (current >= count) return CompositeDecoder.DECODE_DONE
            elementIndices[elementIndices.lastIndex] = (current + 1) to count
            return current
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            elementIndices.removeLast()
        }

        override fun decodeString(): String = tokens[cursor++] as String
    }
}
