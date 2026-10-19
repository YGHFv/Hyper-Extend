/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.CardTileListSettings
import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.panel
import org.junit.Assert.*
import org.junit.Test

class CardTileListTest {
    @Test fun listCodecDoesNotCreateUnknownOrDuplicateTiles() {
        assertEquals(listOf("bt", "wifi"), CardTileListSettings.parse("bt,wifi,bt"))
        assertEquals(emptyList<String>(), CardTileListSettings.parse("none"))
        for (raw in listOf("", "wifi,", "vowifi1", "custom(pkg)", "unknown", "List_wifi|", "a".repeat(257)))
            assertNull(raw, CardTileListSettings.parse(raw))
    }

    @Test fun featureIsOptInAndHasRealPersistedEditor() {
        val feature = featureById(CardTileListSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(FeaturePanel.TILES, feature.panel)
        assertEquals(CardTileListSettings.ORDER, feature.config.single().key)
    }

    @Test fun displayAndMainGatesDoNotUseVersions() {
        assertTrue(CardTileListPolicy.eligible(true, 0, true))
        assertFalse(CardTileListPolicy.eligible(false, 0, true))
        assertFalse(CardTileListPolicy.eligible(true, null, true))
        assertFalse(CardTileListPolicy.eligible(true, 0, false))
    }

    @Test fun detailScopeSeparatesOwnerNestedInvocationsAndFailure() {
        val scope = CardDetailCreationScope()
        val first = Any()
        val second = Any()
        scope.within {
            assertFalse(scope.contains(first, "bt"))
            scope.created(first, "bt")
            assertTrue(scope.contains(first, "bt"))
            assertFalse(scope.contains(second, "bt"))
            runCatching { scope.within {
                assertFalse(scope.contains(first, "bt"))
                scope.created(second, "wifi")
                error("native failure")
            } }
            assertTrue(scope.contains(first, "bt"))
            assertFalse(scope.contains(second, "wifi"))
        }
        assertFalse(scope.active)
        assertFalse(scope.contains(first, "bt"))
    }

    @Test fun detailScopeIsThreadLocal() {
        val scope = CardDetailCreationScope()
        val owner = Any()
        var isolated = false
        scope.within {
            scope.created(owner, "bt")
            Thread { isolated = !scope.active && !scope.contains(owner, "bt") }.apply { start(); join() }
        }
        assertTrue(isolated)
    }

    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    @Test fun completeSignaturesIncludeBothCovariantInvokeMethodsAndConstructor() {
        val fixtures = javaClass.getResourceAsStream("/card-tile-list-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val t = CardTileListTargets
        for (method in t.methods) {
            val sig = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(sig, sig in fixtures)
        }
        assertTrue(descriptor(t.CONTROLLER) + "-><init>(" +
            t.constructor.joinToString("") { descriptor(it) } + ")V" in fixtures)
        for ((owner, name, type) in t.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in fixtures)
        assertFalse(t.methods.any { it.name == "createVoWifiTiles" })
    }
}
