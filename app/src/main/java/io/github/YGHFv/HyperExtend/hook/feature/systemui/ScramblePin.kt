/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect

internal object ScramblePin {
    private data class Slot(val parent: ViewGroup, val index: Int, val params: ViewGroup.LayoutParams)

    fun install(loader: ClassLoader): Int {
        val method = NotificationHooks.resolve(loader, SystemUiTargets.pinInflate) ?: return 0
        val viewsField = Reflect.findField(method.declaringClass, "mViews") ?: return 0
        val numbersField = Reflect.findField(method.declaringClass, "mNumViews") ?: return 0
        return if (HookRuntime.hookAfter(method, "lockscreen_scramble_pin/onFinishInflate") { chain, original ->
                @Suppress("UNCHECKED_CAST")
                val views = viewsField.get(chain.thisObject) as? Array<Array<View?>>
                @Suppress("UNCHECKED_CAST")
                val numbers = numbersField.get(chain.thisObject) as? Array<Array<View?>>
                if (views != null && numbers != null) shuffle(views, numbers)
                original
            }) 1 else 0
    }

    private fun shuffle(views: Array<Array<View?>>, numbers: Array<Array<View?>>) {
        val digits = PinPermutation.digits(views.map { it.toList() }, numbers.map { it.toList() })
        if (digits == null) {
            ModuleLog.warn("lockscreen_scramble_pin: unexpected animation matrices; keeping original keypad")
            return
        }
        val slots = digits.map { digit ->
            val parent = digit.parent as? LinearLayout ?: return
            val index = parent.indexOfChild(digit).takeIf { it >= 0 } ?: return
            Slot(parent, index, digit.layoutParams ?: return)
        }
        val shuffled = PinPermutation.reorder(digits, PinPermutation.shuffled())
        fun place(order: List<View>) {
            digits.forEach { digit -> (digit.parent as? ViewGroup)?.removeView(digit) }
            // Keep every non-digit child and each destination's layout parameters intact.
            slots.indices.groupBy { slots[it].parent }.values.forEach { indices ->
                indices.sortedBy { slots[it].index }.forEach { i ->
                    val slot = slots[i]
                    slot.parent.addView(order[i], slot.index, slot.params)
                }
            }
        }
        try {
            place(shuffled)
        } catch (failure: Throwable) {
            // A failed layout mutation must not strand the user on an incomplete PIN pad.
            val restored = runCatching { place(digits) }.onFailure { ModuleLog.error("PIN layout rollback failed", it) }.isSuccess
            ModuleLog.error("PIN shuffle failed; original layout restored=$restored", failure)
            return
        }
        PinPermutation.slots.forEachIndexed { i, (row, column) ->
            views[row][column] = shuffled[i]
            numbers[row - 1][column] = shuffled[i]
        }
    }
}
