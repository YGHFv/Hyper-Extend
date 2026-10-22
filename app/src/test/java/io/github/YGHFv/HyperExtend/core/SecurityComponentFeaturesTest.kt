/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityComponentFeaturesTest {
    @Test fun securityComponentFeaturesHaveUniqueHostsAndGroups() {
        assertEquals(18, SECURITY_COMPONENT_FEATURES.size)
        assertEquals(
            SECURITY_COMPONENT_FEATURES.size,
            SECURITY_COMPONENT_FEATURES.map { it.id }.distinct().size,
        )
        for (feature in SECURITY_COMPONENT_FEATURES) {
            val scope = feature.entryScope
            assertNotNull(scope)
            assertEquals(listOf(scope?.id), feature.scopes)
            assertEquals(scope?.id, feature.entryGroup?.scopeId)
            assertTrue(!feature.hasDetailPage)
        }
    }

    @Test fun securityComponentHostsAreVisibleOnHome() {
        val ids = entryScopes().map { it.id }.toSet()
        assertTrue(
            ids.containsAll(
                setOf(
                    "securitycenter",
                    "packageinstaller",
                    "guardprovider",
                    "joyose",
                    "market",
                ),
            ),
        )
    }
}
