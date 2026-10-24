/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.mishare

import org.junit.Assert.*
import org.junit.Test

class MiShareAutoTurnOffTargetsTest {
    class Service
    class Timer

    /** 观察到的宿主形状：一个 (MiShareService) 构造器 + 四种字段 + 私有 无参 void 靶点。 */
    class Owner(private val service: Service) {
        @Suppress("unused") private val delay = 600_000
        @Suppress("unused") private val context: android.content.Context? = null
        @Suppress("unused") private val timer: Timer? = null
        @Suppress("unused") private val armed = false
        @Suppress("unused") private fun i() = Unit
    }

    /** 同名的其它类在宿主里另有用途（3.4.1 的 s1.b、5.4.2 的 b2.c），必须靠形状排除。 */
    class BluetoothOwner {
        @Suppress("unused") private val context: android.content.Context? = null
        @Suppress("unused") private val armed = false
        @Suppress("unused") private fun i() = Unit
    }

    class JsonParams(private val params: String) {
        @Suppress("unused") private val delay = 600_000
        @Suppress("unused") private val context: android.content.Context? = null
        @Suppress("unused") private val armed = false
        @Suppress("unused") private fun i() = Unit
    }

    /** 四种字段各有恰一个：多出一个 boolean 就不算观察到的布局。 */
    class DuplicatedFlag(private val service: Service) {
        @Suppress("unused") private val delay = 600_000
        @Suppress("unused") private val context: android.content.Context? = null
        @Suppress("unused") private val timer: Timer? = null
        @Suppress("unused") private val armed = false
        @Suppress("unused") private val visible = true
        @Suppress("unused") private fun i() = Unit
    }

    class MissingDelay(private val service: Service) {
        @Suppress("unused") private val context: android.content.Context? = null
        @Suppress("unused") private val timer: Timer? = null
        @Suppress("unused") private val armed = false
        @Suppress("unused") private fun i() = Unit
    }

    class NoServiceConstructor {
        @Suppress("unused") private val delay = 600_000
        @Suppress("unused") private val context: android.content.Context? = null
        @Suppress("unused") private fun i() = Unit
    }

    class PublicArm(private val service: Service) {
        @Suppress("unused") private val delay = 600_000
        @Suppress("unused") private val context: android.content.Context? = null
        @Suppress("unused") private val timer: Timer? = null
        @Suppress("unused") private val armed = false
        @Suppress("unused") fun i() = Unit
    }

    class ArgArm(private val service: Service) {
        @Suppress("unused") private val delay = 600_000
        @Suppress("unused") private val context: android.content.Context? = null
        @Suppress("unused") private val timer: Timer? = null
        @Suppress("unused") private val armed = false
        @Suppress("unused") private fun i(value: Int) = Unit
    }

    class ValueArm(private val service: Service) {
        @Suppress("unused") private val delay = 600_000
        @Suppress("unused") private val context: android.content.Context? = null
        @Suppress("unused") private val timer: Timer? = null
        @Suppress("unused") private val armed = false
        @Suppress("unused") private fun i() = 0
    }

    private val shape = MiShareAutoTurnOffTargets.Shape("owner", "i", serviceClass = "service")

    private fun locate(owner: Class<*> = Owner::class.java, service: Class<*> = Service::class.java) =
        MiShareAutoTurnOffTargets.locate({ if (it == "owner") owner else service }, listOf(shape))

    @Test fun acceptsObservedShapeWithoutReadingPackageVersion() {
        val match = locate()!!
        assertEquals("i", match.name)
        assertEquals(Owner::class.java, match.declaringClass)
    }

    @Test fun rejectsMissingClasses() {
        assertNull(MiShareAutoTurnOffTargets.locate({ throw ClassNotFoundException(it) }, listOf(shape)))
    }

    @Test fun rejectsOtherClassesSharingTheCandidateName() {
        assertNull(locate(owner = BluetoothOwner::class.java))
        assertNull(locate(owner = JsonParams::class.java))
    }

    @Test fun rejectsIncompleteFieldLayout() {
        assertNull(locate(owner = DuplicatedFlag::class.java))
        assertNull(locate(owner = MissingDelay::class.java))
        assertNull(locate(owner = NoServiceConstructor::class.java))
    }

    @Test fun rejectsTargetsThatAreNotPrivateNoArgVoid() {
        assertNull(locate(owner = PublicArm::class.java))
        assertNull(locate(owner = ArgArm::class.java))
        assertNull(locate(owner = ValueArm::class.java))
    }

    @Test fun rejectsMultipleMatchingPairs() {
        val classes = mapOf(
            "owner" to Owner::class.java, "second" to Owner::class.java, "service" to Service::class.java,
        )
        assertNull(MiShareAutoTurnOffTargets.locate(classes::get, listOf(shape, shape.copy(ownerClass = "second"))))
    }

    @Test fun missingCandidateDoesNotPreventOtherPairFromMatching() {
        val classes = mapOf("owner" to Owner::class.java, "service" to Service::class.java)
        assertNotNull(MiShareAutoTurnOffTargets.locate(classes::get, listOf(shape.copy(ownerClass = "missing"), shape)))
    }

    @Test fun linkageFailureIsContained() {
        assertNull(MiShareAutoTurnOffTargets.locate({ throw NoClassDefFoundError(it) }, listOf(shape)))
    }
}
