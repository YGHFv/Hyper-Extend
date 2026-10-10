/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.hook.feature.systemui.MediaArtworkRequestState.Action.*
import org.junit.Assert.*
import org.junit.Test

class MediaArtworkStateTest {
    @Test fun unchangedBindDoesNotRetryFailedOrPendingSnapshot() {
        val state = MediaArtworkRequestState(); val source = Any()
        assertEquals(PROCESS, state.next(source, true, true))
        repeat(50) { assertEquals(KEEP, state.next(source, true, true)) }
        assertEquals(PROCESS, state.next(source, true, true, force = true))
    }

    @Test fun sourceChangeInvalidatesOldRequestEvenWhenDrawablesCompareEqual() {
        data class Source(val id: Int)
        val state = MediaArtworkRequestState(); val first = Source(1)
        assertEquals(PROCESS, state.next(first, true, true))
        assertEquals(PROCESS, state.next(Source(1), true, true))
    }

    @Test fun excludedSurfacesNeverRequestPixelsAndResumeWithoutAnotherBind() {
        val state = MediaArtworkRequestState(); val source = Any()
        assertEquals(CLEAR, state.next(source, true, false))
        repeat(50) { assertEquals(KEEP, state.next(source, true, false)) }
        assertEquals(PROCESS, state.next(source, true, true))
        assertEquals(CLEAR, state.next(source, true, false))
        assertEquals(PROCESS, state.next(source, true, true))
    }

    @Test fun clearingOrDetachingDoesNotRepublishRetainedHostMediaData() {
        val state = MediaArtworkRequestState(); val source = Any()
        state.next(source, true, true)
        assertEquals(CLEAR, state.next(source, false, true))
        repeat(50) { assertEquals(KEEP, state.next(source, false, true)) }
        assertEquals(PROCESS, state.next(source, true, true))
        assertEquals(CLEAR, state.next(null, true, true))
        assertEquals(KEEP, state.next(null, true, true))
    }

    @Test fun holderOrConfigurationInvalidationReprocessesSameSourceOnce() {
        val state = MediaArtworkRequestState(); val source = Any()
        state.next(source, true, true); state.invalidate()
        assertEquals(PROCESS, state.next(source, true, true))
        assertEquals(KEEP, state.next(source, true, true))
    }

    @Test fun forcedNullBindAlwaysClearsEvenAfterAnInvalidation() {
        val state = MediaArtworkRequestState()
        assertEquals(CLEAR, state.next(null, false, true, force = true))
    }

    @Test fun initialFrameAndDisabledAnimationsSnapWithoutKeepingPreviousImage() {
        val first = intArrayOf(0xff001122.toInt()); val next = intArrayOf(0xff221100.toInt())
        val frames = MediaArtworkTransition(first)
        assertEquals(255, frames.alpha(0)); assertNull(frames.previous)
        frames.update(next, 100, false)
        assertNull(frames.previous); assertSame(next, frames.snapshot(100))
        assertArrayEquals(intArrayOf(0xff001122.toInt()), first)
    }

    @Test fun fadeStartsAtCurrentFrameAndEndsAt333Milliseconds() {
        val first = intArrayOf(0xff000000.toInt()); val next = intArrayOf(0xff2e2e2e.toInt())
        val frames = MediaArtworkTransition(first)
        frames.update(next, 1000, true)
        assertEquals(0, frames.alpha(999)); assertSame(first, frames.snapshot(1000))
        assertEquals(127, frames.alpha(1166)); assertEquals(255, frames.alpha(1333))
        assertSame(next, frames.snapshot(1333))
        frames.finish(); assertNull(frames.previous)
    }

    @Test fun rapidSongChangesFlattenTheVisibleFrameRatherThanJumpingToPreviousTarget() {
        val frames = MediaArtworkTransition(intArrayOf(0xff000000.toInt()))
        frames.update(intArrayOf(0xff2e2e2e.toInt()), 0, true)
        val visible = frames.snapshot(166)
        frames.update(intArrayOf(0xff002e00.toInt()), 166, true)
        assertArrayEquals(visible, frames.snapshot(166))
        assertNotEquals(0xff2e2e2e.toInt(), frames.previous!![0])
        assertEquals(2, listOfNotNull(frames.previous, frames.target).size)
    }

    @Test fun everyInterpolatedFrameRetainsDarkContrastBoundAndOpacity() {
        val frames = MediaArtworkTransition(intArrayOf(0xff2e0000.toInt(), 0xff002e2e.toInt()))
        frames.update(intArrayOf(0xff002e2e.toInt(), 0xff2e0000.toInt()), 0, true)
        for (time in 0L..333L) for (pixel in frames.snapshot(time)) {
            assertEquals(255, pixel ushr 24)
            for (shift in listOf(0, 8, 16)) assertTrue((pixel ushr shift and 255) <= 46)
        }
    }

    @Test fun detachOrExcludedStateDropsTransitionReferencesImmediately() {
        val target = intArrayOf(0xff2e2e2e.toInt())
        val frames = MediaArtworkTransition(intArrayOf(0xff000000.toInt()))
        frames.update(target, 0, true); frames.finish()
        assertNull(frames.previous); assertSame(target, frames.snapshot(0))
    }

    @Test fun invalidFrameShapeDoesNotCorruptExistingTransition() {
        val frames = MediaArtworkTransition(intArrayOf(0xff000000.toInt()))
        assertTrue(runCatching { frames.update(IntArray(2), 0, true) }.isFailure)
        assertEquals(1, frames.target.size); assertNull(frames.previous)
    }
}
