/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.screenshot

import io.github.YGHFv.HyperExtend.core.ScreenshotEditorSettings
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import io.github.YGHFv.HyperExtend.config.HyperSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotEditorPolicyTest {
    @Test fun shellQueryRemapsOnlyTheCurrentDeviceAndPreservesVariantSuffix() {
        assertEquals("custom_BG", ScreenshotEditorPolicy.remapShellQuery(
            "actual_BG", "actual", "custom",
        ))
        assertEquals("custom", ScreenshotEditorPolicy.remapShellQuery(
            "actual", "actual", "custom",
        ))
        assertEquals("other_BG", ScreenshotEditorPolicy.remapShellQuery(
            "other_BG", "actual", "custom",
        ))
        assertEquals("actual_BG", ScreenshotEditorPolicy.remapShellQuery(
            "actual_BG", "actual", null,
        ))
    }

    @Test fun shellDeviceAcceptsOnlyBoundedIdentifierCharacters() {
        assertEquals("Example_01", ScreenshotEditorSettings.device(" Example_01 "))
        assertNull(ScreenshotEditorSettings.device(""))
        assertNull(ScreenshotEditorSettings.device("bad/value"))
        assertNull(ScreenshotEditorSettings.device("a".repeat(65)))
    }

    @Test fun catalogAddsBothScreenshotEditorFeaturesWithoutEnablingThem() {
        val privacy = featureById(ScreenshotEditorSettings.PRIVACY_FEATURE)!!
        val device = featureById(ScreenshotEditorSettings.DEVICE_FEATURE)!!
        assertEquals(listOf("screenshot"), privacy.scopes)
        assertEquals(listOf("screenshot"), device.scopes)
        assertFalse(privacy.defaultEnabled)
        assertFalse(device.defaultEnabled)
        assertFalse(privacy.hasDetailPage)
        assertTrue(device.hasDetailPage)
        assertEquals(ScreenshotEditorSettings.DEVICE, device.config.single().key)
        assertTrue(ScreenshotEditorSettings.DEVICE in HyperSettings.STRING_KEYS)
    }

    @Test fun cropFloorKeepsHostMeaningAndNeverReachesZero() {
        val floor = ScreenshotEditorSettings.CROP_MIN_SIZE_PX
        // 只放宽，不收紧：宿主自己算出来的值更小或已为 0 时原样回传。
        assertEquals(floor, ScreenshotEditorPolicy.relaxMinCropSize(100, floor))
        assertEquals(8, ScreenshotEditorPolicy.relaxMinCropSize(8, floor))
        assertEquals(0, ScreenshotEditorPolicy.relaxMinCropSize(0, floor))
        // 归一是本功能与上游实现唯一的、也是刻意的差别。
        assertTrue(ScreenshotEditorPolicy.relaxMinCropSize(100, floor) > 0)
        // 下限本身不可信（0 或负）时不改宿主。
        assertEquals(100, ScreenshotEditorPolicy.relaxMinCropSize(100, 0))
        assertEquals(100, ScreenshotEditorPolicy.relaxMinCropSize(100, -1))
    }

    @Test fun cropFeatureStaysSimpleAndDefaultsOff() {
        val crop = featureById(ScreenshotEditorSettings.CROP_FEATURE)!!
        assertEquals(listOf("screenshot"), crop.scopes)
        assertFalse(crop.defaultEnabled)
        assertFalse(crop.hasDetailPage)
    }

    @Test fun hooksUseOnlyTheExactCurrentHostMembers() {
        val signatures = javaClass.getResourceAsStream("/screenshot-editor-cached-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        fun descriptor(type: String): String = when {
            type == "int" -> "I"
            type == "long" -> "J"
            type == "boolean" -> "Z"
            type == "void" -> "V"
            type.startsWith("[") -> type.replace('.', '/')
            else -> "L${type.replace('.', '/')};"
        }
        for (method in ScreenshotEditorTargets.all) {
            val signature = descriptor(method.owner) + "->" + method.name +
                "(" + method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
            // 裁剪靶点是宿主内部类的 private 实例方法，其余三个都是静态方法。
            assertEquals(method != ScreenshotEditorTargets.cropMinSize, method.isStatic)
        }
        assertEquals(4, signatures.size)
    }
}
