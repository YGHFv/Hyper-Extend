/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.mishare

import org.junit.Assert.*
import org.junit.Test

class MiShareTargetsTest {
    class Allocator {
        companion object {
            @JvmStatic fun pick(directory: String, name: String): String = "$directory/$name"
        }
    }
    class WrongAllocator {
        fun pick(directory: String, name: String): String = "$directory/$name"
    }
    class WrongReturn {
        companion object {
            @JvmStatic fun pick(directory: String, name: String): Int = directory.length + name.length
        }
    }
    class Receiver {
        private fun d() = Unit
    }
    class OldReceiver {
        private fun d(callback: String, packet: Int) = Unit
    }
    class PublicReceiver {
        fun d() = Unit
    }

    private val shape = MiShareTargets.Shape("allocator", "pick", "receiver", emptyList())
    private fun locate(allocator: Class<*> = Allocator::class.java, receiver: Class<*> = Receiver::class.java) =
        MiShareTargets.locate({ if (it == "allocator") allocator else receiver }, listOf(shape))

    @Test fun acceptsMatchingPairWithoutReadingPackageVersion() {
        val match = locate()!!
        assertEquals("pick", match.allocator.name)
        assertEquals(Receiver::class.java, match.receiver.declaringClass)
    }

    @Test fun rejectsMissingClasses() {
        assertNull(MiShareTargets.locate({ throw ClassNotFoundException(it) }, listOf(shape)))
    }

    @Test fun rejectsWrongAllocatorShape() {
        assertNull(locate(allocator = WrongAllocator::class.java))
        assertNull(locate(allocator = WrongReturn::class.java))
    }

    @Test fun rejectsWrongReceiverShape() {
        assertNull(locate(receiver = PublicReceiver::class.java))
        assertNull(locate(receiver = OldReceiver::class.java))
    }

    @Test fun acceptsOldReceiverParametersOnlyWhenExact() {
        val old = shape.copy(receiverParameters = listOf("java.lang.String", "int"))
        val match = MiShareTargets.locate(
            { if (it == "allocator") Allocator::class.java else OldReceiver::class.java }, listOf(old),
        )
        assertNotNull(match)
    }

    @Test fun rejectsMultipleMatchingPairs() {
        val classes = mapOf(
            "allocator" to Allocator::class.java, "second" to Allocator::class.java,
            "receiver" to Receiver::class.java,
        )
        assertNull(MiShareTargets.locate(classes::get, listOf(shape, shape.copy(allocatorClass = "second"))))
    }

    @Test fun missingCandidateDoesNotPreventOtherPairFromMatching() {
        val classes = mapOf("allocator" to Allocator::class.java, "receiver" to Receiver::class.java)
        assertNotNull(MiShareTargets.locate(classes::get, listOf(shape.copy(allocatorClass = "missing"), shape)))
    }

    @Test fun linkageFailureIsContained() {
        assertNull(MiShareTargets.locate({ throw NoClassDefFoundError(it) }, listOf(shape)))
    }
}
