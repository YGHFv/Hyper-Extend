/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

/** Keep automatic filtering and the current menu's eligibility query out of unrelated callers. */
internal class NotificationFoldScope<T : Any> {
    private val automatic = ThreadLocal<Boolean>()
    private val menuNotification = ThreadLocal<T>()

    val ignoreAutomatic: Boolean get() = automatic.get() == true

    fun suppressMenuFor(notification: T?): Boolean = notification != null && notification === menuNotification.get()

    fun <R> inAutomatic(block: () -> R): R = within(automatic, true, block)

    fun <R> inMenu(notification: T?, folded: Boolean?, block: () -> R): R =
        within(menuNotification, notification.takeIf { folded == false }, block)

    private fun <V, R> within(local: ThreadLocal<V>, value: V?, block: () -> R): R {
        val previous = local.get()
        if (value == null) local.remove() else local.set(value)
        return try {
            block()
        } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }
}
