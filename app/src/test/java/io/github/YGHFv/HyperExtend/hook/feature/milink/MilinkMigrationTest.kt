/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.milink

import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.MilinkSettings
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MilinkMigrationTest {
    @Test fun hooksUseOnlyTheExactCachedHostMembers() {
        val signatures = javaClass.getResourceAsStream("/milink-cached-host-members.txt")!!
            .bufferedReader().use { it.readLines().filter(String::isNotBlank).toSet() }
        fun descriptor(type: String): String = when {
            type == "boolean" -> "Z"
            type == "void" -> "V"
            type.startsWith("[") -> "[" + descriptor(type.substring(1))
            else -> "L${type.replace('.', '/')};"
        }
        for (target in MilinkTargets.all) {
            val signature = descriptor(target.owner) + "->" + target.name +
                "(" + target.parameters.joinToString("") { descriptor(it) } + ")" +
                descriptor(target.returns)
            assertTrue(signature, signature in signatures)
        }
        assertEquals(5, signatures.size)
        assertTrue(MilinkTargets.regionTargets.all { it.isStatic })
        assertFalse(MilinkTargets.hpplayDirectoryTarget.isStatic)
    }

    @Test fun regionReplacementsKeepTheTwoExpectedValues() {
        assertEquals(true, MilinkRegionCheck.replacementFor("isSameRegionWithLocal"))
        assertEquals("cn", MilinkRegionCheck.replacementFor("getDeviceRegion"))
        assertNull(MilinkRegionCheck.replacementFor("isAuthorised"))
    }

    @Test fun hpplayPolicyOnlyOwnsTheExpectedSharedStorageSubtrees() {
        val root = "/storage/emulated/0"
        assertTrue(MilinkHpplayPolicy.shouldBlock(root,
            "$root/Xiaomi/com.milink.service/sink/sdk"))
        assertTrue(MilinkHpplayPolicy.shouldBlock(root,
            "$root/Xiaomi/com.milink.service/sink/sdk/"))
        assertTrue(MilinkHpplayPolicy.shouldBlock(root,
            "$root/com.milink.service/sink/sdk"))
        assertFalse(MilinkHpplayPolicy.shouldBlock(root,
            "$root/Xiaomi/com.milink.service.old/sink/sdk"))
        assertFalse(MilinkHpplayPolicy.shouldBlock(root,
            "$root/Xiaomi/other/sink/sdk"))
        assertFalse(MilinkHpplayPolicy.shouldBlock(root,
            "/data/user/0/com.milink.service/cache"))
        assertFalse(MilinkHpplayPolicy.shouldBlock(root, null))
        assertFalse(MilinkHpplayPolicy.shouldBlock("", "$root/com.milink.service"))
    }

    @Test fun newMilinkFeaturesStayDisabledAndUseTheDedicatedPanel() {
        val region = featureById(MilinkSettings.REGION_CHECK)!!
        val hpplay = featureById(MilinkSettings.BLOCK_HPPLAY)!!
        for (feature in listOf(region, hpplay)) {
            assertEquals(listOf("milink"), feature.scopes)
            assertFalse(feature.defaultEnabled)
            assertEquals(FeaturePanel.MILINK, feature.panel)
        }
    }
}
