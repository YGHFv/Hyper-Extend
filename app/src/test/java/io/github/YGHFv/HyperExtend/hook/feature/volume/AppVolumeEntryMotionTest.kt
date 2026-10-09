/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVolumeEntryMotionTest {
    private val epsilon = 0.0001f

    @Test fun idleLayoutOffsetsDoNotMoveTheEntry() {
        val motion = AppVolumeEntryMotion()
        motion.include(12f, 7f, 30f, 70f, 1f, 1f, 0f, 0f, 1f)
        motion.include(5f, 80f, 100f, 150f, 1f, 1f, 0f, 0f, 1f)
        assertEquals(0f, motion.translationX(40f), epsilon)
        assertEquals(0f, motion.translationY(20f), epsilon)
        assertEquals(1f, motion.scaleX, epsilon)
        assertEquals(1f, motion.scaleY, epsilon)
        assertEquals(1f, motion.alpha, epsilon)
    }

    @Test fun volumeLimitBounceIncludesTranslationAndScaleAboutTheNativePivot() {
        val motion = AppVolumeEntryMotion()
        motion.include(0f, 60f, 30f, 100f, 0.95f, 0.95f, 0f, 12f, 1f)
        assertEquals(0.95f, motion.scaleY, epsilon)
        assertEquals(19f, motion.translationY(20f), epsilon)
        assertEquals(0f, motion.translationX(30f), epsilon)
        // The capsule-to-slider gap contracts with the same native scale.
        val entryBottom = 20f + motion.translationY(20f) + 20f * motion.scaleY
        val sliderTop = 60f + 12f + 100f * (1f - 0.95f)
        assertEquals(20f * 0.95f, sliderTop - entryBottom, epsilon)
    }

    @Test fun translationOnlyFramesAreNotLost() {
        val motion = AppVolumeEntryMotion()
        motion.include(0f, 60f, 30f, 100f, 1f, 1f, -3f, 9f, 1f)
        assertEquals(-3f, motion.translationX(30f), epsilon)
        assertEquals(9f, motion.translationY(20f), epsilon)
    }

    @Test fun showHideAndKeyMotionComposeAcrossNestedContainers() {
        val motion = AppVolumeEntryMotion()
        motion.include(12f, 7f, 30f, 70f, 1.05f, 0.95f, 3f, -6f, 0.8f)
        motion.include(5f, 80f, 100f, 150f, 0.9f, 0.8f, 20f, 30f, 0.75f)
        assertEquals(0.945f, motion.scaleX, epsilon)
        assertEquals(0.76f, motion.scaleY, epsilon)
        assertEquals(0.6f, motion.alpha, epsilon)
        // Map the entry center through the content and parent independently.
        val x = 40f - 17f
        val y = 20f - 87f
        val contentX = 12f + 3f + 30f + (x - 30f) * 1.05f
        val contentY = 7f - 6f + 70f + (y - 70f) * 0.95f
        val dialogX = 5f + 20f + 100f + (contentX - 100f) * 0.9f
        val dialogY = 80f + 30f + 150f + (contentY - 150f) * 0.8f
        assertEquals(dialogX - 40f, motion.translationX(40f), epsilon)
        assertEquals(dialogY - 20f, motion.translationY(20f), epsilon)
    }

    @Test fun capsuleCenterAndNonDefaultPivotsArePreserved() {
        val motion = AppVolumeEntryMotion()
        motion.include(10f, 60f, 0f, 0f, 0.8f, 1.1f, 0f, 0f, 1f)
        assertEquals(-6f, motion.translationX(40f), epsilon)
        assertEquals(-4f, motion.translationY(20f), epsilon)
    }

    @Test fun resetAndRepeatedSamplingNeverAccumulateTransforms() {
        val motion = AppVolumeEntryMotion()
        repeat(20) {
            motion.reset()
            motion.include(0f, 60f, 30f, 100f, 0.95f, 0.95f, 0f, 12f, 0.5f)
            assertEquals(19f, motion.translationY(20f), epsilon)
            assertEquals(0.5f, motion.alpha, epsilon)
        }
        motion.reset()
        assertEquals(0f, motion.translationX(40f), epsilon)
        assertEquals(0f, motion.translationY(20f), epsilon)
        assertEquals(1f, motion.scaleX, epsilon)
        assertEquals(1f, motion.scaleY, epsilon)
        assertEquals(1f, motion.alpha, epsilon)
    }
}
