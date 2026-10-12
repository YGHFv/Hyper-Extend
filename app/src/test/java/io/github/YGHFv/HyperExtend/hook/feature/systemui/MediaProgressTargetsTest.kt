/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class MediaProgressTargetsTest {
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "boolean" -> "Z"; "int" -> "I"
        else -> "L${type.replace('.', '/')};"
    }
    private val signatures get() = javaClass.getResourceAsStream("/media-progress-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }
    @Test fun everyResolvedMethodAndFieldHasCurrentHostEvidence() {
        for (spec in MediaProgressTargets.methods) {
            val signature = descriptor(spec.owner) + "->" + spec.name + "(" +
                spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in signatures)
        }
        for ((owner, name, type) in MediaProgressTargets.fields) {
            val signature = descriptor(owner) + "->" + name + ":" + descriptor(type)
            assertTrue(signature, signature in signatures)
        }
    }
    @Test fun currentOwnerIsNotTheObsoleteControllerObserver() {
        assertEquals("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaSeekBarProgressOwner",
            MediaProgressTargets.OWNER)
        assertTrue(MediaProgressTargets.changed.owner.startsWith(MediaProgressTargets.OWNER))
    }
}
