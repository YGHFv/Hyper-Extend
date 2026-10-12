/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import io.github.YGHFv.HyperExtend.core.panel
import org.junit.Assert.*
import org.junit.Test

class SunlightTileTest {
    @Test fun featureIsOptInAndExplicitAboutNativePreferenceNotForcedBrightness() {
        val feature = featureById(SunlightTilePolicy.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        // 合并上游两行后，这张卡片带上了「亮度增强方式」与「自定义亮度」两个配置行。
        assertTrue(feature.hasDetailPage)
        assertEquals(FeaturePanel.TILES, feature.panel)
        assertTrue(feature.requirement!!.contains("不写背光节点"))
        assertTrue(feature.requirement.contains("screen_brightness"))
        assertTrue(feature.requirement.contains("快照恢复"))
    }
    @Test fun onlyKnownBooleanPreferenceValuesAreAccepted() {
        assertEquals(false, SunlightTilePolicy.decode(0))
        assertEquals(true, SunlightTilePolicy.decode(1))
        for (value in listOf(-1, 2, Int.MAX_VALUE)) assertNull(SunlightTilePolicy.decode(value))
        assertEquals("sunlight_mode", SunlightTilePolicy.KEY)
    }
    @Test fun independentTilesCoexistInEditorWithoutReplacingNativeCandidates() {
        val a = TaplusTilePolicy.SPEC
        val b = SunlightTilePolicy.SPEC
        assertNotEquals(a, b)
        val first = NativeTogglePolicy.append(NativeTogglePolicy.append("wifi,cell", a), b)
        assertEquals("wifi,cell,$a,$b", first)
        assertEquals(first, NativeTogglePolicy.append(NativeTogglePolicy.append(first, a), b))
        assertNull(NativeTogglePolicy.append(null, b))
        assertEquals("", NativeTogglePolicy.append("", b))
    }
    @Test fun nativeWriteFailureNeverRetriesOrInventsSuccessfulState() {
        var writes = 0
        val result = runCatching { NativeToggleTransaction.toggle(false, object : NativeToggleTransaction.Backend {
            override fun allowed() = true
            override fun read() = false
            override fun write(enabled: Boolean) { writes++; error("native permission revoked") }
        }) }
        assertTrue(result.isFailure)
        assertEquals(1, writes)
    }
}
