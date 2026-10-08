/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook

import android.content.SharedPreferences
import io.github.YGHFv.HyperExtend.core.FEATURES
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class HookSettingsSafetyTest {
    private fun settings(values: Map<String, Any> = emptyMap()): HookSettings = HookSettings(
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            val value = values[args!![0]] ?: args.getOrNull(1)
            when (method.name) {
                "getBoolean" -> value as Boolean
                "getString" -> value as String
                "getLong" -> value as Long
                else -> error("unexpected method")
            }
        } as SharedPreferences,
    )
    @Test fun missingPreferencesDisableAllSwitches() {
        val settings = HookSettings(null)
        assertFalse(settings.isAvailable)
        FEATURES.forEach { f -> assertFalse(settings.isOn(f.id)); f.options.forEach { assertFalse(settings.isOn(it.id)) } }
    }
    @Test fun defaultBooleanFeaturesAndOwnedOptionsStayOff() {
        val settings = settings()
        FEATURES.filter { it.configKey == null }.forEach { f ->
            assertFalse(f.id, settings.isOn(f.id))
            f.options.forEach { assertFalse(it.id, settings.isOn(it.id)) }
        }
    }
    @Test fun childSwitchCannotBypassParentAndUnknownIsOff() {
        assertFalse(settings(mapOf("passkey_fix.system_server" to true)).isOn("passkey_fix.system_server"))
        assertTrue(settings(mapOf("passkey_fix" to true)).isOn("passkey_fix.system_server"))
        assertFalse(settings(mapOf("unknown" to true)).isOn("unknown"))
    }
    @Test fun malformedPreferencesFailClosed() {
        val settings = settings(mapOf("passkey_fix" to "true", "test" to true, "token" to "1"))
        assertFalse(settings.isOn("passkey_fix"))
        assertEquals("", settings.string("test"))
        assertEquals(0, settings.number("test", 0))
        assertEquals(0L, settings.long("token"))
    }
}
