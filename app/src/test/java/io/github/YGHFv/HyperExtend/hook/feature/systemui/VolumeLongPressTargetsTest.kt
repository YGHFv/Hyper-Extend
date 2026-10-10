/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class VolumeLongPressTargetsTest {
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    private val signatures get() = javaClass.getResourceAsStream("/volume-long-press-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }

    @Test fun exactMethodsAndLifecycleCallersAreVerified() {
        assertEquals(15, VolumeLongPressTargets.methods.size)
        for (spec in VolumeLongPressTargets.methods) {
            val signature = descriptor(spec.owner) + "->" + spec.name + "(" +
                spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in signatures)
        }
    }
    @Test fun fieldsHaveExactHostTypes() {
        assertEquals(12, VolumeLongPressTargets.fields.size)
        for ((owner, name, type) in VolumeLongPressTargets.fields) {
            val signature = descriptor(owner) + "->" + name + ":" + descriptor(type)
            assertTrue(signature, signature in signatures)
        }
    }
    @Test fun nativeTouchAndExpandPolicyAreIncludedInEvidence() {
        assertTrue(descriptor(VolumeLongPressTargets.SEEK) + "->onTouchEvent(Landroid/view/MotionEvent;)Z" in signatures)
        assertTrue("Lmiui/systemui/widget/RelativeSeekBarInjector;->transformTouchEvent(Landroid/view/MotionEvent;)V" in signatures)
        assertTrue("Lcom/android/systemui/miui/volume/VolumePanelViewController;->onExpandClicked()V" in signatures)
        assertTrue("Lcom/android/systemui/miui/volume/MiuiVolumeDialogMotion;->isAnimating()Z" in signatures)
    }
}
