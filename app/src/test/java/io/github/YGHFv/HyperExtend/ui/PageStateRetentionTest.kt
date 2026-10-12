/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural guard only. Actual scroll/back/process-recreation still needs Compose/device QA. */
class PageStateRetentionTest {
    @Test fun removedPagesHaveSeparateSaveableStateScopes() {
        val source = File("src/main/java/io/github/YGHFv/HyperExtend/ui/SettingsActivity.kt").readText()
        assertTrue(source.indexOf("val pageStates = rememberSaveableStateHolder()") < source.indexOf("AppSettingsPage.fromId(openAppPage)"))
        for (route in listOf("app:", "feature:", "scope:", "search", "tab:"))
            assertTrue(source.contains("pageStates.SaveableStateProvider(\"$route"))
        assertFalse(source.contains("key(scope.id, group)"))
        assertFalse(source.contains("searching = false\n                    openFeatureId = hit.id"))
    }
}
