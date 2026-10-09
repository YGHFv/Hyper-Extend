/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import io.github.YGHFv.HyperExtend.core.*
import org.junit.Assert.*
import org.junit.Test

class AppVolumePolicyTest {
    @Test fun mediaRequiresStartedAppAndKnownPackage() {
        assertTrue(AppVolumePolicy.activeMedia(10001, 2, 1, -1, listOf("com.example")))
        assertTrue(AppVolumePolicy.activeMedia(1010001, 2, 0, 3, listOf("com.example")))
        assertFalse(AppVolumePolicy.activeMedia(1000, 2, 1, 3, listOf("android")))
        assertFalse(AppVolumePolicy.activeMedia(101000, 2, 1, 3, listOf("android")))
        assertFalse(AppVolumePolicy.activeMedia(-1, 2, 1, 3, emptyList()))
        assertFalse(AppVolumePolicy.activeMedia(10001, 1, 1, 3, listOf("com.example")))
        assertFalse(AppVolumePolicy.activeMedia(10001, 2, 4, 4, listOf("com.example")))
        assertFalse(AppVolumePolicy.activeMedia(10001, 2, 1, 3, emptyList()))
    }
    @Test fun wallpaperIsExcludedEvenWithSharedUid() {
        assertFalse(AppVolumePolicy.activeMedia(10001, 2, 1, 3, listOf("com.miui.miwallpaper", "com.other")))
    }
    @Test fun entryNeverAppearsOnLockscreenOrExpandedPanel() {
        assertTrue(AppVolumePolicy.visible(true, false, false, true, false))
        assertFalse(AppVolumePolicy.visible(false, false, false, true, false))
        assertFalse(AppVolumePolicy.visible(true, true, false, true, false))
        assertFalse(AppVolumePolicy.visible(true, false, true, true, false))
        assertFalse(AppVolumePolicy.visible(true, false, false, false, false))
        assertFalse(AppVolumePolicy.visible(true, false, false, true, true))
    }
    @Test fun noRoomMeansNoRowRatherThanMovingSlidersDown() {
        assertEquals(0, AppVolumePolicy.offset(49, 50, true))
        assertEquals(50, AppVolumePolicy.offset(50, 50, true))
        assertEquals(0, AppVolumePolicy.offset(100, 50, false))
        assertEquals(0, AppVolumePolicy.offset(-1, 50, true))
    }
    @Test fun repeatedRefreshDoesNotAccumulateMarginChanges() {
        var margin = 200
        margin = AppVolumePolicy.margin(margin, 0, 50)
        repeat(10) { margin = AppVolumePolicy.margin(margin, 50, 50) }
        assertEquals(150, margin)
        assertEquals(200, AppVolumePolicy.margin(margin, 50, 0))
    }
    @Test fun featureIsOptInCrossScopeAndReportsLimitedDeviceAcceptance() {
        val feature = featureById(AppVolumeSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(listOf("systemui", "misound"), feature.scopes)
        assertEquals("systemui", feature.entryScope!!.id)
        assertEquals("com.miui.misound", scopeById("misound")!!.process)
        assertTrue(feature.requirement!!.contains("当前适配设备已确认使用正常"))
        assertTrue(feature.requirement.contains("仍需完整回归"))
        assertTrue(feature.requirement.contains("不含降噪"))
        assertTrue(feature.requirement.contains("直接使用官方展开/关闭动画"))
        assertTrue(feature.requirement.contains("隐藏原生悬浮球"))
        assertTrue(feature.requirement.contains("不自动解除总静音"))
        assertTrue(feature.requirement.contains("保留原生媒体总音量列"))
        assertTrue(feature.requirement.contains("隐藏静音、勿扰和定时快捷按钮"))
        assertTrue(feature.requirement.contains("共享 UID"))
        assertFalse(feature.requirement.contains("仅迁移入口"))
        assertEquals("Apache-2.0", feature.license)
    }

    @Test fun nativeStateWritesAndPlaybackRefreshDoNotAccumulateOffsets() {
        val geometry = AppVolumeGeometry()
        var margin = geometry.refresh(200, 50, true)
        repeat(10) { margin = geometry.refresh(margin, 50, true) }
        assertEquals(150, margin)
        assertEquals(200, geometry.restore(margin))
        assertEquals(0, geometry.offset)
        margin = geometry.refresh(300, 50, true)
        assertEquals(250, margin)
        assertEquals(300, geometry.refresh(margin, 50, false))
    }

    @Test fun rotationAndDensityChangeUseNewNativeGeometry() {
        val geometry = AppVolumeGeometry()
        assertEquals(150, geometry.refresh(200, 50, true))
        geometry.restore(150)
        assertEquals(30, geometry.refresh(30, 70, true))
        assertEquals(0, geometry.offset)
        assertEquals(230, geometry.refresh(300, 70, true))
        assertEquals(300, geometry.restore(230))
    }

    @Test fun landscapeCentersTheEntryAndNativeControlsTogether() {
        val geometry = AppVolumeGeometry()
        val nativeHeight = 650
        val displayHeight = 940
        val rowHeight = 120
        val nativeTop = (displayHeight - nativeHeight) / 2
        val top = geometry.refresh(nativeTop, rowHeight, true, centered = true)
        assertEquals(60, geometry.offset)
        assertEquals(displayHeight / 2, top + (nativeHeight + rowHeight) / 2)
        assertEquals(nativeTop, geometry.restore(top))
    }

    @Test fun landscapeRequiresRoomForHalfTheRowNotTheWholeRow() {
        assertEquals(25, AppVolumePolicy.offset(30, 50, true, centered = true))
        assertEquals(0, AppVolumePolicy.offset(24, 50, true, centered = true))
        assertEquals(0, AppVolumePolicy.offset(30, 50, true, centered = false))
        assertEquals(0, AppVolumePolicy.offset(30, 50, false, centered = true))
        assertEquals(0, AppVolumePolicy.offset(30, 0, true, centered = true))
        assertEquals(0, AppVolumePolicy.offset(30, -1, true, centered = true))
    }

    @Test fun centeredOddRowHeightsRoundUpAndCanRestore() {
        val geometry = AppVolumeGeometry()
        assertEquals(24, geometry.refresh(50, 51, true, centered = true))
        assertEquals(26, geometry.offset)
        assertEquals(50, geometry.restore(24))
    }

    @Test fun orientationChangesDoNotAccumulateFullAndHalfOffsets() {
        val geometry = AppVolumeGeometry()
        var margin = geometry.refresh(200, 50, true)
        assertEquals(150, margin)
        repeat(10) { margin = geometry.refresh(margin, 50, true, centered = true) }
        assertEquals(175, margin)
        assertEquals(200, geometry.restore(margin))
        margin = geometry.refresh(90, 70, true, centered = true)
        assertEquals(55, margin)
        assertEquals(90, geometry.refresh(margin, 70, false, centered = true))
        assertEquals(0, geometry.offset)
    }

    @Test fun dismissAndReshowRejectStaleBroadcastSuccess() {
        val state = AppVolumeLifecycle()
        assertNull(state.beginRequest())
        state.show()
        val token = state.beginRequest()!!
        assertTrue(state.accepts(token))
        assertNull(state.beginRequest())
        state.dismiss()
        assertTrue(state.dismissing)
        assertFalse(state.accepts(token))
        state.show()
        val next = state.beginRequest()!!
        assertFalse(state.accepts(token))
        assertTrue(state.accepts(next))
        state.detach()
        assertFalse(state.accepts(next))
        assertFalse(state.visible)
    }

    @Test fun requestTimeoutAllowsRetryButRejectsLateResult() {
        val state = AppVolumeLifecycle()
        state.show()
        val token = state.beginRequest()!!
        state.cancelRequest()
        assertFalse(state.accepts(token))
        assertFalse(state.opening)
        assertNotNull(state.beginRequest())
    }
}
