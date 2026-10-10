/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.TileColorSettings
import io.github.YGHFv.HyperExtend.core.featureById
import org.junit.Assert.*
import org.junit.Test

class TileIconColorTest {
    @Test fun specialAndThirdPartySpecsRemainNative() {
        for (spec in listOf(null, "", " ", "quietmode", "papermode", "mute", "cell", "autobrightness",
                "flashlight", "batterysaver", "custom(com.example/.Tile)")) assertFalse(TileColorPolicy.genericIcon(spec))
        assertTrue(TileColorPolicy.genericIcon("reduce_brightness"))
        assertTrue(TileColorPolicy.genericIcon("rotation"))
    }
    @Test fun scopedColorRestoresAfterNativeCallWithoutChangingItsResult() {
        var value = 1
        val result = withTileIconColor(2, { value }, { value = it }) { assertEquals(2, value); "native" }
        assertEquals("native", result)
        assertEquals(1, value)
    }
    @Test fun scopedColorRestoresOnExceptionAndPreservesNewerWrites() {
        var value = 1
        val failure = IllegalStateException("native")
        val result = runCatching { withTileIconColor(2, { value }, { value = it }) { throw failure } }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, value)
        withTileIconColor(2, { value }, { value = it }) { value = 3 }
        assertEquals(3, value)
    }
    @Test fun nestedInputScopesRestoreTheirOwnBaselines() {
        var value = 1
        withTileIconColor(2, { value }, { value = it }) {
            withTileIconColor(3, { value }, { value = it }) { assertEquals(3, value) }
            assertEquals(2, value)
        }
        assertEquals(1, value)
    }
    @Test fun catalogExplicitlyDocumentsCachedTintAndPartialCoverage() {
        val feature = featureById(TileColorSettings.FEATURE)!!
        assertTrue(feature.requirement!!.contains("相同状态早返回"))
        assertTrue(feature.requirement.contains("不替换矢量动画"))
        assertTrue(feature.requirement.contains("待真机验收"))
    }
}
