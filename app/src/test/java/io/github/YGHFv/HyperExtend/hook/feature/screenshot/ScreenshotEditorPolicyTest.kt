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

    @Test fun catalogAddsScreenshotEditorFeaturesWithoutEnablingThem() {
        val privacy = featureById(ScreenshotEditorSettings.PRIVACY_FEATURE)!!
        val device = featureById(ScreenshotEditorSettings.DEVICE_FEATURE)!!
        val saveToPictures = featureById(ScreenshotEditorSettings.SAVE_TO_PICTURES_FEATURE)!!
        assertEquals(listOf("screenshot"), privacy.scopes)
        assertEquals(listOf("screenshot"), device.scopes)
        assertEquals(listOf("screenshot"), saveToPictures.scopes)
        assertFalse(privacy.defaultEnabled)
        assertFalse(device.defaultEnabled)
        assertFalse(saveToPictures.defaultEnabled)
        assertFalse(privacy.hasDetailPage)
        assertTrue(device.hasDetailPage)
        assertFalse(saveToPictures.hasDetailPage)
        assertEquals(ScreenshotEditorSettings.DEVICE, device.config.single().key)
        assertTrue(ScreenshotEditorSettings.DEVICE in HyperSettings.STRING_KEYS)
    }

    @Test fun cropRelaxOnlyLoosensAndNeverReachesZero() {
        val divisor = ScreenshotEditorSettings.CROP_RELAX_DIVISOR
        assertEquals(12, ScreenshotEditorPolicy.relaxMinCropSize(100, divisor))
        // 绝不收紧：结果永远不大于宿主自己算出来的值。
        for (original in 1..400) {
            val relaxed = ScreenshotEditorPolicy.relaxMinCropSize(original, divisor)
            assertTrue("$original -> $relaxed", relaxed in 1..original)
        }
        // 上游的 returnConstant(0) 会抹掉钳位，让保存路径上的零宽位图成为可能。
        // 小宿主值是最容易复现这一点的一档：4 / 8 整除就是 0。
        assertEquals(1, ScreenshotEditorPolicy.relaxMinCropSize(4, divisor))
        assertEquals(1, ScreenshotEditorPolicy.relaxMinCropSize(1, divisor))
        // 宿主自己已不设限（0 或负）时原样回传，不替宿主编一个下限。
        assertEquals(0, ScreenshotEditorPolicy.relaxMinCropSize(0, divisor))
        assertEquals(-1, ScreenshotEditorPolicy.relaxMinCropSize(-1, divisor))
        // 分母不可信时不改宿主。
        assertEquals(100, ScreenshotEditorPolicy.relaxMinCropSize(100, 1))
        assertEquals(100, ScreenshotEditorPolicy.relaxMinCropSize(100, 0))
        assertEquals(100, ScreenshotEditorPolicy.relaxMinCropSize(100, -8))
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
            // 裁剪靶点是宿主内部类的 private 实例方法，其余四个都是静态方法。
            assertEquals(method != ScreenshotEditorTargets.cropMinSize, method.isStatic)
        }
        assertEquals(5, signatures.size)
    }
}
