/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object StockTilesPolicy {
    const val FEATURE = "control_center_fix_tiles_list"
    val candidates = listOf("reduce_brightness", "inversion", "saver", "dark", "onehanded", "color_correction")

    /** 调用方只有在完整目标结构解析成功后才调用；这里不再重复做版本门禁。 */
    fun supported(): Boolean = true

    fun append(native: String?, includeFiveG: Boolean = false): String? {
        if (native.isNullOrBlank() || native.length > 16384) return native
        val present = native.split(',').map { it.trim() }.toSet()
        val available = if (includeFiveG) candidates + FiveGTilePolicy.SPEC else candidates
        val missing = available.filterNot { it in present }
        if (missing.isEmpty()) return native
        // Keep the native list byte-for-byte, including unknown and custom tile specs.
        return native + (if (native.endsWith(',')) "" else ",") + missing.joinToString(",")
    }
}

/** One synchronous editor read, matched by receiver identity; never a repository cache. */
internal class StockTilesScope {
    class Frame(val receiver: Any, val key: Int) { var consumed = false }
    private val current = ThreadLocal<Frame?>()

    fun take(receiver: Any?, key: Int): Boolean {
        val frame = current.get() ?: return false
        if (frame.consumed || receiver !== frame.receiver || key != frame.key) return false
        frame.consumed = true
        return true
    }

    fun <T> withFrame(frame: Frame?, block: () -> T): T {
        val previous = current.get()
        current.set(frame)
        return try { block() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
