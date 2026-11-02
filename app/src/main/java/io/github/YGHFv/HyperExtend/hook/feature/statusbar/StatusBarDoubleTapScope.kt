/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import java.util.WeakHashMap

/** 同步拦截双击；按检测器和按下时间保留点击归属，不持有可回收的宿主事件。 */
internal class StatusBarDoubleTapScope {
    private data class Touch(val detector: Any, val event: Any)
    private data class ConsumedPair(val firstDown: Long, val secondDown: Long)
    private val current = ThreadLocal<Touch>()
    private val consumed = WeakHashMap<Any, ConsumedPair>()
    private val reserved = WeakHashMap<Any, ArrayDeque<Long>>()

    @Synchronized
    fun reserveTap(detector: Any?, downTime: Long) {
        if (detector == null || downTime < 0) return
        val times = reserved.getOrPut(detector) { ArrayDeque() }
        if (downTime !in times) {
            times.addLast(downTime)
            // 只保存数值；即使休眠后才收到 TAP 消息，也不因墙钟超时而放出旧滚动。
            if (times.size > 16) times.removeFirst()
        }
    }

    fun <T> during(detector: Any?, event: Any?, eligible: Boolean, native: () -> T): T {
        val previous = current.get()
        if (eligible && detector != null && event != null) current.set(Touch(detector, event))
        else current.remove()
        return try {
            native()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun suppresses(detector: Any?, event: Any?, enabled: Boolean, blocked: Boolean): Boolean {
        val touch = current.get() ?: return false
        return enabled && !blocked && touch.detector === detector && touch.event === event
    }

    @Synchronized
    fun consumePair(detector: Any?, firstDown: Long, secondDown: Long) {
        if (detector != null && firstDown >= 0 && secondDown > firstDown)
            consumed[detector] = ConsumedPair(firstDown, secondDown)
    }

    @Synchronized
    fun suppressesConsumedTap(detector: Any?, downTime: Long, enabled: Boolean, blocked: Boolean): Boolean {
        if (!enabled || blocked) {
            consumed.clear()
            reserved.clear()
            return false
        }
        if (downTime in reserved[detector].orEmpty()) return true
        val pair = consumed[detector] ?: return false
        // GestureDetector 延迟回调传入的是事件副本，不能沿用同步分发的引用身份判断。
        return downTime == pair.firstDown || downTime == pair.secondDown
    }
}
