/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class ScopeCatalogTest {
    @Test fun scopePriorityStartsWithFrameworkAndSystemUi() {
        assertEquals(
            listOf("system_server", "systemui", "settings", "securitycenter", "screenshot",
                "misound", "milink", "mishare", "tsmclient", "scanner"),
            SCOPES.map { it.id },
        )
        assertEquals(SCOPES.size, SCOPES.map { it.id }.distinct().size)
    }

    @Test fun homeKeepsPriorityWhileExcludingSecondaryOnlyHosts() {
        assertEquals(
            listOf("system_server", "systemui", "screenshot", "milink", "mishare", "tsmclient"),
            entryScopes().map { it.id },
        )
        assertTrue(entryScopes().all { featuresOfScope(it.id).isNotEmpty() })
    }

    @Test fun displayPriorityNeverChangesFeatureOwnershipOrDuplicatesEntries() {
        FEATURES.forEach { feature ->
            assertEquals(feature.scopes.first(), feature.entryScope?.id)
            assertEquals(feature.scopes, scopesOfFeature(feature).map { it.id })
        }
        val displayed = entryScopes().flatMap { featuresOfScope(it.id) }
        assertEquals(FEATURES.size, displayed.size)
        assertEquals(FEATURES.map { it.id }.toSet(), displayed.map { it.id }.toSet())
    }

    @Test fun hostProcessesAndRestartPoliciesDoNotFollowListPositions() {
        val framework = scopeById("system_server")!!
        assertEquals("system", framework.process)
        assertEquals("android", framework.iconPackage)
        assertEquals(RestartKind.REBOOT, framework.restartKind)
        val systemUi = scopeById("systemui")!!
        assertEquals("com.android.systemui", systemUi.process)
        assertEquals(RestartKind.KILL, systemUi.restartKind)
        assertEquals(RestartKind.FORCE_STOP, scopeById("tsmclient")!!.restartKind)
        assertEquals("systemui", APP_VOLUME_FEATURE.entryScope?.id)
        assertEquals(listOf("systemui", "misound"), APP_VOLUME_FEATURE.scopes)
    }
}
