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

    @Test fun footerReplacementFollowsContentBounceInsteadOfResettingToIdentity() {
        val content = AppVolumeEntryMotion().apply { include(0f, 40f, 30f, 100f, 0.95f, 0.95f, 0f, 12f, 0.8f) }
        val footer = AppVolumeEntryMotion().apply { include(0f, 240f, 30f, 40f, 1f, 1f, 0f, 0f, 1f) }
        val relative = content.relativeTo(footer, 30f, 20f)!!
        assertEquals(0.95f, relative.scaleY, epsilon)
        assertEquals(content.translationY(260f), relative.translationY, epsilon)
        assertEquals(0.8f, relative.alpha, epsilon)
    }

    @Test fun sharedParentAnimationsAreNotAppliedTwiceInAnyFooterPosition() {
        val content = AppVolumeEntryMotion().apply {
            include(0f, 40f, 30f, 100f, 1f, 1f, 0f, 0f, 1f)
            include(10f, 15f, 50f, 60f, 0.8f, 0.7f, 8f, 9f, 0.5f)
        }
        val parent = AppVolumeEntryMotion().apply {
            include(0f, 240f, 30f, 40f, 1f, 1f, 0f, 0f, 1f)
            include(10f, 15f, 50f, 60f, 0.8f, 0.7f, 8f, 9f, 0.5f)
        }
        for (center in listOf(20f, 60f, 100f, 140f, 180f)) {
            val relative = content.relativeTo(parent, 30f, center)!!
            assertEquals(1f, relative.scaleX, epsilon)
            assertEquals(1f, relative.scaleY, epsilon)
            assertEquals(0f, relative.translationX, epsilon)
            assertEquals(0f, relative.translationY, epsilon)
            assertEquals(1f, relative.alpha, epsilon)
        }
    }

    @Test fun differentFooterTransformIsInvertedAboutItsOwnPivot() {
        val content = AppVolumeEntryMotion().apply { include(0f, 40f, 30f, 100f, 0.9f, 0.8f, 2f, 12f, 0.4f) }
        val parent = AppVolumeEntryMotion().apply { include(10f, 240f, 20f, 40f, 0.8f, 0.7f, 5f, 7f, 0.5f) }
        val r = content.relativeTo(parent, 30f, 20f)!!
        assertEquals(content.scaleY, parent.scaleY * r.scaleY, epsilon)
        assertEquals(260f + content.translationY(260f), 260f + parent.translationY(260f) + parent.scaleY * r.translationY, epsilon)
        assertEquals(content.alpha, parent.alpha * r.alpha, epsilon)
    }

    @Test fun invisibleAndDegenerateParentsNeverProduceInfinity() {
        val content = AppVolumeEntryMotion()
        for ((scale, alpha) in listOf(0f to 1f, 1f to 0f, Float.NaN to 1f)) {
            val parent = AppVolumeEntryMotion().apply { include(0f, 0f, 0f, 0f, scale, scale, 0f, 0f, alpha) }
            org.junit.Assert.assertNull(content.relativeTo(parent, 30f, 20f))
        }
    }
}
