/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

internal object SafeModeKeys {
    fun disabled(scope: String) = "safety.$scope.disabled"
    fun reset(scope: String) = "safety.$scope.reset"
    fun incident(scope: String) = "safety.$scope.incident"
    fun acknowledged(scope: String) = "safety.$scope.acknowledged"
    fun nextReset(previous: Long, now: Long): Long {
        require(previous in 0 until Long.MAX_VALUE)
        return maxOf(previous + 1, now.coerceAtLeast(1))
    }
}

internal object SafeModeReportPolicy {
    fun accepts(resetToken: Long, incident: SafeModeIncident): Boolean = incident.resetToken == resetToken
    fun duplicate(existing: SafeModeIncident?, incident: SafeModeIncident): Boolean =
        existing != null && existing.resetToken == incident.resetToken
}

internal data class SafeModeIncident(val id: String, val resetToken: Long, val occurredAt: Long, val reason: String) {
    fun encode(): String = "$id\n$resetToken\n$occurredAt\n${reason.replace('\n', ' ').take(240)}"

    companion object {
        fun decode(text: String): SafeModeIncident? {
            val parts = text.split('\n', limit = 4)
            if (parts.size != 4 || !parts[0].matches(Regex("[a-zA-Z0-9-]{1,80}"))) return null
            val token = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val time = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            if (parts[3].isBlank() || parts[3].length > 240) return null
            return SafeModeIncident(parts[0], token, time, parts[3])
        }
    }
}

/** A disabled marker is never silently discarded when malformed or unreadable. */
internal class SafeModeStore(private val file: File) {
    fun read(): Result<SafeModeIncident?> = runCatching {
        val attributes = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        } catch (_: NoSuchFileException) { return@runCatching null }
        require(attributes.isRegularFile && attributes.size() <= 2048) { "invalid safety marker" }
        requireNotNull(SafeModeIncident.decode(file.readText())) { "invalid safety marker" }
    }

    fun write(incident: SafeModeIncident): Boolean = runCatching {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val temp = File(parent, file.name + ".tmp")
        FileOutputStream(temp).use { it.write(incident.encode().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        temp.renameTo(file)
    }.getOrDefault(false)

    fun clear(): Boolean = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
}
