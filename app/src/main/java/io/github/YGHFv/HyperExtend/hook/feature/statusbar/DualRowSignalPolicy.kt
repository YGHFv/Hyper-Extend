/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object DualRowSignalPolicy {
    private val ordinarySignal = Regex("stat_sys_signal_([0-4])(?:_darkmode|_tint)?")
    data class Sim(val id: Int, val slot: Int)
    data class Signal(val id: Int, val visible: Boolean, val level: Int?, val satellite: Boolean?)
    data class Rows(val upper: Int, val lower: Int, val upperLevel: Int, val lowerLevel: Int)

    // Only the audited ordinary cellular icons are safe to replace. In particular, preserve
    // no-voice, flight, emergency and satellite artwork instead of inventing a signal level.
    fun level(name: String): Int? = ordinarySignal
        .matchEntire(name)?.groupValues?.get(1)?.toInt()

    fun rows(sims: List<Sim>, signals: List<Signal>, defaultData: Int, airplane: Boolean?): Rows? {
        if (airplane != false || sims.size != 2 || signals.size != 2) return null
        if (sims.any { it.id < 0 || it.slot < 0 } || sims.map { it.id }.distinct().size != 2 ||
            sims.map { it.slot }.distinct().size != 2) return null
        if (signals.map { it.id }.toSet() != sims.map { it.id }.toSet()) return null
        if (signals.any { !it.visible || it.level !in 0..4 || it.satellite != false }) return null
        val order = sims.sortedWith(compareBy<Sim> { if (it.id == defaultData) 0 else 1 }.thenBy { it.slot })
        return Rows(order[0].id, order[1].id,
            signals.single { it.id == order[0].id }.level!!, signals.single { it.id == order[1].id }.level!!)
    }

    // HyperCeiler's six assets contain a duplicate one-bar step at index 2.
    fun assetLevel(level: Int): Int = if (level >= 2) level + 1 else level
}

/** Own only one padding edge; theme/density updates replace its native baseline. */
internal class DualRowSignalPaddingEdge {
    private var baseline: Int? = null
    private var owned: Int? = null

    fun apply(current: Int, offset: Int): Int {
        if (current != owned) baseline = current
        return ((baseline ?: current) + offset).also { owned = it }
    }

    fun restore(current: Int): Int {
        val result = if (current == owned) baseline ?: current else current
        baseline = null
        owned = null
        return result
    }
}
