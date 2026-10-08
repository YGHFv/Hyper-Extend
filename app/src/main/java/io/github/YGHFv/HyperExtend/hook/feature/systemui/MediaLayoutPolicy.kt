/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import kotlin.math.roundToInt

internal data class MediaLayoutOptions(
    val album: Int = 0,
    val order: Int = 0,
    val leftAligned: Boolean = false,
    val hideSeamless: Boolean = false,
    val titleMargin: Int = 210,
    val artistMargin: Int = 40,
) {
    val changed: Boolean get() = this != MediaLayoutOptions()
}

internal data class MediaConstraintEdit(val method: String, val arguments: List<Int>)

/** Host ConstraintSet constants, not module-side AndroidX objects. */
internal object MediaLayoutPolicy {
    const val LEFT = 1
    const val RIGHT = 2
    const val TOP = 3
    const val START = 6
    const val END = 7
    const val GONE = 8
    val resourceNames = listOf("header_title", "header_artist", "icon", "album_art", "media_seamless", "actions") +
        (0..4).map { "action$it" }

    fun order(mode: Int): List<Int> = when (mode) {
        1 -> listOf(1, 2, 3, 0, 4)
        2 -> listOf(2, 1, 3, 0, 4)
        else -> (0..4).toList()
    }

    fun edits(options: MediaLayoutOptions, ids: Map<String, Int>, density: Float, albumLayer: Boolean = false): List<MediaConstraintEdit> {
        require(density.isFinite() && density > 0)
        require(resourceNames.all { (ids[it] ?: 0) > 0 })
        require(resourceNames.map { ids.getValue(it) }.distinct().size == resourceNames.size)
        fun id(name: String) = ids.getValue(name)
        fun dp(value: Float) = (value * density).roundToInt()
        return buildList {
            fun edit(method: String, vararg args: Int) { add(MediaConstraintEdit(method, args.toList())) }
            if (albumLayer) {
                if (options.album != 0) edit("setVisibility", id("icon"), GONE)
                return@buildList
            }
            if (options.album == 2) {
                edit("setVisibility", id("album_art"), GONE)
                edit("setGoneMargin", id("header_title"), START, dp(26f))
                edit("setGoneMargin", id("header_artist"), START, dp(26f))
                edit("setGoneMargin", id("actions"), TOP, dp(67.5f))
                edit("setGoneMargin", id("action0"), TOP, dp(78.5f))
            }
            if (options.titleMargin != 210) {
                edit("setMargin", id("header_title"), TOP, dp(options.titleMargin / 10f))
                edit("setGoneMargin", id("header_title"), TOP, dp(options.titleMargin / 10f))
            }
            if (options.artistMargin != 40) edit("setMargin", id("header_artist"), TOP, dp(options.artistMargin / 10f))
            if (options.order in 1..2) {
                val buttons = order(options.order).map { id("action$it") }
                buttons.forEachIndexed { index, button ->
                    edit("connect", button, LEFT, buttons.getOrNull(index - 1) ?: id("actions"), if (index == 0) LEFT else RIGHT)
                    edit("connect", button, RIGHT, buttons.getOrNull(index + 1) ?: id("actions"), if (index == 4) RIGHT else LEFT)
                }
                // The chain style belongs to its new head, not the original action0.
                edit("setHorizontalChainStyle", id("action0"), 0)
                edit("setHorizontalChainStyle", buttons.first(), 1)
                edit("setMargin", id("action0"), START, 0)
                edit("setMargin", buttons.first(), START, dp(6f))
            }
            if (options.leftAligned) edit("clear", id("action4"), RIGHT)
            if (options.hideSeamless) {
                edit("setVisibility", id("media_seamless"), GONE)
                edit("setGoneMargin", id("header_title"), END, dp(26f))
                edit("setGoneMargin", id("header_artist"), END, dp(26f))
            }
        }
    }
}
