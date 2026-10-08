/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.mishare

import java.io.File
import java.util.UUID

internal enum class PathPresence { PRESENT, ABSENT, UNKNOWN }

/** Read-only selection; the host remains responsible for opening and publishing the file. */
internal class MiShareDestinationPolicy(
    private val onDisk: (File) -> PathPresence,
    private val inMediaStore: (File) -> PathPresence,
    private val token: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
) {
    fun select(original: String, directory: String, root: File): String = try {
        selectChecked(original, directory, root)
    } catch (_: Exception) {
        original
    }

    private fun selectChecked(original: String, directory: String, root: File): String {
        val file = File(original)
        // Only direct children of the expected receive directory, never a custom destination,
        // traversal, or a symlink. The root supplied by the adapter is already absolute.
        if (!root.isAbsolute || root.canonicalPath != root.absolutePath ||
            File(directory).path != root.path || !safeChild(file, root)
        ) return original
        if (onDisk(file) != PathPresence.ABSENT ||
            inMediaStore(file) != PathPresence.PRESENT
        ) return original

        // Random suffixes avoid deterministic collisions between concurrent receives without
        // creating placeholder files. This is not an atomic reservation against other writers.
        repeat(8) {
            val name = fallbackName(file.name, token()) ?: return original
            val candidate = File(root, name)
            if (!safeChild(candidate, root)) return@repeat
            when (onDisk(candidate)) {
                PathPresence.UNKNOWN -> return original
                PathPresence.PRESENT -> return@repeat
                PathPresence.ABSENT -> Unit
            }
            when (inMediaStore(candidate)) {
                PathPresence.UNKNOWN -> return original
                PathPresence.PRESENT -> return@repeat
                PathPresence.ABSENT -> {
                    // Recheck after the resolver round trip; never knowingly reuse a real file.
                    if (safeChild(candidate, root) && onDisk(candidate) == PathPresence.ABSENT) {
                        return candidate.absolutePath
                    }
                }
            }
        }
        return original
    }

    private fun safeChild(file: File, root: File): Boolean =
        file.isAbsolute && file.parentFile == root && file.name !in listOf("", ".", "..") &&
            file.name.none { it == '\u0000' || it == '/' || it == '\\' } &&
            file.canonicalPath == file.absolutePath

    internal fun fallbackName(name: String, token: String): String? {
        if (!token.matches(Regex("[0-9a-f]{12}"))) return null
        val dot = name.lastIndexOf('.').takeIf { it > 0 && it < name.lastIndex }
        val stem = if (dot == null) name else name.substring(0, dot)
        val extension = if (dot == null) "" else name.substring(dot)
        val suffix = " (HE-$token)"
        val budget = 255 - (suffix + extension).toByteArray(Charsets.UTF_8).size
        if (budget < 1) return null
        var end = 0
        var bytes = 0
        while (end < stem.length) {
            val count = Character.charCount(stem.codePointAt(end))
            val width = stem.substring(end, end + count).toByteArray(Charsets.UTF_8).size
            if (bytes + width > budget) break
            bytes += width
            end += count
        }
        if (end == 0) return null
        return stem.substring(0, end) + suffix + extension
    }
}
