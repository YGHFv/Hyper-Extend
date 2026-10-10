/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class LockscreenWallpaperPolicyTest {
    private fun eligible(version: Long? = 202602260L, mainThread: Boolean = true, enabled: Boolean = true,
                         needAnimation: Boolean = true, systemAnimations: Boolean = true, attached: Boolean = true,
                         displayId: Int? = 0, defaultTheme: Boolean = true, showing: Boolean = true,
                         fullAod: Boolean = false, depth: Boolean = false, video: Boolean = false,
                         flip: Boolean = false, occluded: Boolean = false, fromGone: Boolean = false,
                         bouncer: Boolean = false, dismissing: Boolean = false, superSave: Boolean = false) =
        LockscreenWallpaperPolicy.eligible(version, mainThread, enabled, needAnimation, systemAnimations,
            attached, displayId, defaultTheme, showing, fullAod, depth, video, flip, occluded,
            fromGone, bouncer, dismissing, superSave)

    @Test fun ordinaryMainDisplayKeyguardIsEligible() { assertTrue(eligible()) }

    @Test fun versionThreadAndFeatureAreMandatory() {
        assertFalse(eligible(version = null))
        assertFalse(eligible(version = 202602261L))
        assertFalse(eligible(mainThread = false))
        assertFalse(eligible(enabled = false))
    }

    @Test fun neverForcesNativeDisabledAnimationOrUnknownDisplay() {
        assertFalse(eligible(needAnimation = false))
        assertFalse(eligible(systemAnimations = false))
        assertFalse(eligible(attached = false))
        assertFalse(eligible(displayId = null))
        assertFalse(eligible(displayId = 1))
    }

    @Test fun specialWallpaperAndAodModesRemainNative() {
        assertFalse(eligible(defaultTheme = false))
        assertFalse(eligible(fullAod = true))
        assertFalse(eligible(depth = true))
        assertFalse(eligible(video = true))
        assertFalse(eligible(flip = true))
        assertFalse(eligible(superSave = true))
    }

    @Test fun unlockOcclusionAndAuthenticationAreNotReplaced() {
        assertFalse(eligible(showing = false))
        assertFalse(eligible(occluded = true))
        assertFalse(eligible(fromGone = true))
        assertFalse(eligible(bouncer = true))
        assertFalse(eligible(dismissing = true))
    }
}
