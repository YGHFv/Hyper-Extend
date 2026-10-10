/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class VolumeLongPressPolicyTest {
    private fun eligible(enabled: Boolean = true, systemUi: Long? = 202602260L, plugin: Long? = 183022200L,
                         main: Boolean = true, attached: Boolean = true, shown: Boolean = true,
                         expanded: Boolean = false, animating: Boolean = false, cc: Boolean = false,
                         accessibility: Boolean = false, app: Boolean = false, active: Boolean = true,
                         button: Boolean = false) = VolumeLongPressPolicy.eligible(enabled, systemUi, plugin,
        main, attached, shown, expanded, animating, cc, accessibility, app, active, button)

    @Test fun ordinaryCollapsedActiveSliderIsEligible() { assertTrue(eligible()) }
    @Test fun exactVersionsAndOptInAreRequired() {
        assertFalse(eligible(enabled = false))
        assertFalse(eligible(systemUi = null)); assertFalse(eligible(plugin = null))
        assertFalse(eligible(systemUi = 202602261L)); assertFalse(eligible(plugin = 183022201L))
    }
    @Test fun hiddenDetachedOrSecondaryDisplayIsExcluded() {
        assertFalse(eligible(main = false)); assertFalse(eligible(attached = false)); assertFalse(eligible(shown = false))
    }
    @Test fun expandedAnimatingAndInactiveSlidersAreExcluded() {
        assertFalse(eligible(expanded = true)); assertFalse(eligible(animating = true)); assertFalse(eligible(active = false))
    }
    @Test fun accessibilityControlCenterAppPanelAndButtonKeepNativeBehavior() {
        assertFalse(eligible(accessibility = true)); assertFalse(eligible(cc = true))
        assertFalse(eligible(app = true)); assertFalse(eligible(button = true))
    }
}
