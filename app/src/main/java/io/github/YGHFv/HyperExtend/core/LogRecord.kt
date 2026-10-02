package io.github.YGHFv.HyperExtend.core

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogRecord(val source: String, val timestamp: String, val level: LogLevel, val message: String) {
    fun asText(): String = "$timestamp ${level.name} [$source] $message".trim()
}

object LogRecords {
    private val header = Regex("^((?:\\d{4}-\\d{2}-\\d{2} )?\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?) ([DIWE]|DEBUG|INFO|WARN|ERROR) (.*)$")

    fun parse(source: String, lines: List<String>): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        for (line in lines.flatMap { it.lines() }) {
            if (line.isBlank()) continue
            val match = header.matchEntire(line)
            if (match == null) {
                if (records.isEmpty() || line.startsWith("====")) {
                    records += LogRecord(source, "", LogLevel.INFO, line)
                } else {
                    val previous = records.last()
                    records[records.lastIndex] = previous.copy(message = previous.message + "\n" + line)
                }
                continue
            }
            val level = when (match.groupValues[2].first()) {
                'D' -> LogLevel.DEBUG
                'W' -> LogLevel.WARN
                'E' -> LogLevel.ERROR
                else -> LogLevel.INFO
            }
            records += LogRecord(source, match.groupValues[1], level, match.groupValues[3])
        }
        return records
    }

    fun visible(records: List<LogRecord>, minimum: LogLevel, reversed: Boolean): List<LogRecord> {
        val filtered = records.filter { it.level.ordinal >= minimum.ordinal }
        return if (reversed) filtered.asReversed() else filtered
    }
}
