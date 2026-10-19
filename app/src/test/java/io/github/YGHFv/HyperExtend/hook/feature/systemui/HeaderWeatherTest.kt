/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.HeaderWeatherSettings
import io.github.YGHFv.HyperExtend.core.featureById
import org.junit.Assert.*
import org.junit.Test

class HeaderWeatherTest {
    @Test fun contentUsesProviderUnitAndOptionalSanitizedCity() {
        assertEquals("Sunny 21\u00b0C", HeaderWeatherPolicy.text(true, 21, 1, "Sunny", "City", false, 100, 200))
        assertEquals("City Sunny 70\u00b0F", HeaderWeatherPolicy.text(true, 70, 0, "Sunny", "Ci\u202aty\n", true, 100, 200))
        assertFalse(featureById(HeaderWeatherSettings.FEATURE)!!.defaultEnabled)
        assertFalse(featureById(HeaderWeatherSettings.FEATURE)!!.options.single().defaultEnabled)
    }

    @Test fun missingInvalidFutureAndStaleDataDoesNotInventWeather() {
        assertNull(HeaderWeatherPolicy.text(false, 21, 1, null, null, false, 100, 200))
        assertNull(HeaderWeatherPolicy.text(true, 21, 3, null, null, false, 100, 200))
        assertNull(HeaderWeatherPolicy.text(true, 999, 1, null, null, false, 100, 200))
        assertNull(HeaderWeatherPolicy.text(true, 21, 1, null, null, false, 0, 200))
        assertNull(HeaderWeatherPolicy.text(true, 21, 1, null, null, false, 300, 200))
        assertNull(HeaderWeatherPolicy.text(true, 21, 1, null, null, false, 1, HeaderWeatherPolicy.MAX_AGE_MS + 2))
    }

    @Test fun noCrossUserOrDisplayFallback() {
        assertTrue(HeaderWeatherPolicy.eligible(true, 0, 0, true))
        assertTrue(HeaderWeatherPolicy.eligible(true, 0, 10, true))
        assertFalse(HeaderWeatherPolicy.eligible(true, 0, -1, true))
        assertFalse(HeaderWeatherPolicy.eligible(true, 0, null, true))
        assertFalse(HeaderWeatherPolicy.eligible(true, 0, 0, false))
        assertFalse(HeaderWeatherPolicy.eligible(true, 1, 0, true))
        assertFalse(HeaderWeatherPolicy.eligible(false, 0, 0, true))
    }

    @Test fun captionSpaceProtectsNativeContentAndRtl() {
        val obstacle = HeaderWeatherPolicy.Space(250, 300)
        assertEquals(HeaderWeatherPolicy.Space(108, 242),
            HeaderWeatherPolicy.space(400, 20, 100, false, 8, listOf(obstacle)))
        assertEquals(HeaderWeatherPolicy.Space(108, 192),
            HeaderWeatherPolicy.space(400, 200, 300, true, 8, listOf(HeaderWeatherPolicy.Space(0, 100))))
        assertNull(HeaderWeatherPolicy.space(100, 0, 100, false, 8, emptyList()))
        assertNull(HeaderWeatherPolicy.space(100, -1, 10, false, 8, emptyList()))
    }

    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "boolean" -> "Z"; "int" -> "I"; "long" -> "J"
        else -> "L${type.replace('.', '/')};"
    }
    @Test fun exactLifecycleProviderCallbackAndBeanSignaturesAreRecorded() {
        val fixtures = javaClass.getResourceAsStream("/header-weather-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        for (method in HeaderWeatherTargets.methods) {
            val sig = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(sig, sig in fixtures)
        }
        for ((owner, name, type) in HeaderWeatherTargets.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in fixtures)
        assertTrue(descriptor(HeaderWeatherTargets.FETCHER) + "-><init>()V" in fixtures)
    }
}
