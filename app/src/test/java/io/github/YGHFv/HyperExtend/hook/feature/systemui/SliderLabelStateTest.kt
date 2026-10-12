package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class SliderLabelStateTest {
    private fun shown() = SliderLabelState().apply { capture("native", 8, "46%") }

    @Test fun ownedPlainTextRestoresExactlyOnce() {
        val state = shown()
        assertEquals(SliderLabelState.NativeText("native", 8), state.release("46%", true, false))
        assertFalse(state.ownsText)
        assertNull(state.release("46%", true, false))
    }

    @Test fun equalNativeRichTextIsNotOurPlainLabel() {
        val state = shown()
        assertNull(state.release("46%", true, true))
        assertFalse(state.ownsText)
        assertNull(state.release("46%", true, false))
    }

    @Test fun newNativeTextOrVisibilityWins() {
        assertNull(shown().release("Super volume", true, false))
        assertNull(shown().release("46%", false, false))
    }

    @Test fun disableSafetyAndHiddenViewsReleaseWithoutNativeUpdates() {
        val state = shown()
        assertFalse(state.shouldRelease(true, false, true, true))
        assertTrue(state.shouldRelease(false, false, true, true))
        assertTrue(state.shouldRelease(true, true, true, true))
        assertTrue(state.shouldRelease(true, false, false, true))
        assertTrue(state.shouldRelease(true, false, true, false))
        state.release("46%", true, false)
        assertFalse(state.shouldRelease(false, true, false, false))
    }

    @Test fun aNewSessionCapturesTheLatestNativeBaseline() {
        val state = shown()
        state.release("native update", true, false)
        state.capture("new baseline", 4, "80%")
        assertEquals(SliderLabelState.NativeText("new baseline", 4), state.release("80%", true, false))
    }
}
