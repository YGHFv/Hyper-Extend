/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal interface ImportanceChannelAccess<C : Any> {
    fun read(): C?
    fun matches(channel: C): Boolean
    fun editable(channel: C): Boolean
    fun importance(channel: C): Int
    fun changedCopy(channel: C, level: Int): C
    fun write(channel: C)
}

internal data class ImportanceUpdateResult<C>(val confirmed: Boolean, val actual: C?)

/** A void backend call can swallow Binder failures. Only server read-back confirms a save. */
internal fun <C : Any> updateImportance(level: Int, access: ImportanceChannelAccess<C>): ImportanceUpdateResult<C> {
    if (level !in 1..4) return ImportanceUpdateResult(false, null)
    val current = access.read()?.takeIf(access::matches) ?: return ImportanceUpdateResult(false, null)
    if (!access.editable(current)) return ImportanceUpdateResult(false, current)
    if (access.importance(current) == level) return ImportanceUpdateResult(true, current)
    access.write(access.changedCopy(current, level))
    val actual = access.read()?.takeIf(access::matches)
    return ImportanceUpdateResult(actual != null && access.importance(actual) == level, actual)
}
