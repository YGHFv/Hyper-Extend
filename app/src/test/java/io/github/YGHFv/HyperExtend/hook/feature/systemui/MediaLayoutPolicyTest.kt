/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class MediaLayoutPolicyTest {
    private val ids = MediaLayoutPolicy.resourceNames.mapIndexed { index, name -> name to index + 100 }.toMap()
    private fun edits(options: MediaLayoutOptions, album: Boolean = false) = MediaLayoutPolicy.edits(options, ids, 2f, album)
    private fun edit(name: String, vararg args: Int) = MediaConstraintEdit(name, args.toList())

    @Test fun defaultsLeaveBothHostConstraintLayersUntouched() {
        assertFalse(MediaLayoutOptions().changed)
        assertTrue(edits(MediaLayoutOptions()).isEmpty())
        assertTrue(edits(MediaLayoutOptions(), album = true).isEmpty())
    }

    @Test fun albumOnlyModeWorksWithoutUnrelatedLayoutChanges() {
        val options = MediaLayoutOptions(album = 1)
        assertTrue(options.changed)
        assertTrue(edits(options).isEmpty())
        assertEquals(listOf(edit("setVisibility", ids.getValue("icon"), 8)), edits(options, album = true))
    }

    @Test fun hidingTheCoverRepairsAnchorsButPreservesControls() {
        val result = edits(MediaLayoutOptions(album = 2))
        assertTrue(result.contains(edit("setGoneMargin", ids.getValue("header_title"), 6, 52)))
        assertTrue(result.contains(edit("setGoneMargin", ids.getValue("actions"), 3, 135)))
        assertTrue(result.contains(edit("setGoneMargin", ids.getValue("action0"), 3, 157)))
        assertEquals(listOf(edit("setVisibility", ids.getValue("album_art"), 8)), result.filter { it.method == "setVisibility" })
    }

    @Test fun permutationsUseEveryButtonExactlyOnceWithReciprocalConnections() {
        for (mode in 1..2) {
            val order = MediaLayoutPolicy.order(mode)
            assertEquals((0..4).toSet(), order.toSet())
            val result = edits(MediaLayoutOptions(order = mode))
            val links = result.filter { it.method == "connect" }
            assertEquals(10, links.size)
            val buttons = order.map { ids.getValue("action$it") }
            for (index in 0..3) {
                assertTrue(links.contains(edit("connect", buttons[index], 2, buttons[index + 1], 1)))
                assertTrue(links.contains(edit("connect", buttons[index + 1], 1, buttons[index], 2)))
            }
            assertTrue(links.contains(edit("connect", buttons.first(), 1, ids.getValue("actions"), 1)))
            assertTrue(links.contains(edit("connect", buttons.last(), 2, ids.getValue("actions"), 2)))
            assertTrue(result.contains(edit("setHorizontalChainStyle", buttons.first(), 1)))
            assertFalse(result.any { it.method == "setVisibility" })
        }
    }

    @Test fun leftAlignmentClearsOnlyTheLastHorizontalAnchor() {
        val result = edits(MediaLayoutOptions(order = 2, leftAligned = true))
        assertEquals(listOf(edit("clear", ids.getValue("action4"), 2)), result.filter { it.method == "clear" })
        assertTrue(result.filter { it.method == "connect" }.all { it.arguments[1] in 1..2 })
    }

    @Test fun marginsUseDensityAndTenthsWithoutAccumulatingOffsets() {
        val options = MediaLayoutOptions(titleMargin = 225, artistMargin = 35, hideSeamless = true)
        val result = edits(options)
        assertTrue(result.contains(edit("setMargin", ids.getValue("header_title"), 3, 45)))
        assertTrue(result.contains(edit("setMargin", ids.getValue("header_artist"), 3, 7)))
        assertTrue(result.contains(edit("setVisibility", ids.getValue("media_seamless"), 8)))
        assertTrue(result.contains(edit("setGoneMargin", ids.getValue("header_artist"), 7, 52)))
        assertEquals(result, edits(options))
    }

    @Test fun invalidResourcesOrDensityFailBeforeProducingEdits() {
        for (invalid in listOf(ids - "actions", ids + ("icon" to 0), ids + ("icon" to ids.getValue("actions")))) {
            assertThrows(IllegalArgumentException::class.java) { MediaLayoutPolicy.edits(MediaLayoutOptions(album = 2), invalid, 2f) }
        }
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { MediaLayoutPolicy.edits(MediaLayoutOptions(), ids, density) }
        }
    }

    @Test fun mediaTargetsUseTheVerifiedOs4RefreshChain() {
        assertEquals("updateLayout\$1", SystemUiTargets.mediaUpdateLayout.name)
        assertEquals(listOf(SystemUiTargets.MEDIA_HOLDER), SystemUiTargets.mediaAttach.parameters)
        assertTrue(SystemUiTargets.flipTinyScreen.isStatic)
        assertEquals("reinflateViews", SystemUiTargets.mediaReinflate.name)
        assertFalse(SystemUiTargets.all.any { it.owner == SystemUiTargets.MEDIA_HOLDER && it.name == "<init>" })
    }
}
