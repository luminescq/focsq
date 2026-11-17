package com.csqtt.client.ui.v2.screens.logs

import com.csqtt.client.LogEntry
import com.csqtt.client.LogLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ANSI SGR handling plus tag/level helpers used by the V2 log terminal.
 *
 * Kept free of Compose and Android types so it can be unit-tested directly.
 */

internal enum class AnsiColor {
    DEFAULT,
    BLACK,
    RED,
    GREEN,
    YELLOW,
    BLUE,
    MAGENTA,
    CYAN,
    WHITE,
    BRIGHT_BLACK,
    BRIGHT_RED,
    BRIGHT_GREEN,
    BRIGHT_YELLOW,
    BRIGHT_BLUE,
    BRIGHT_MAGENTA,
    BRIGHT_CYAN,
    BRIGHT_WHITE,
}

internal data class AnsiSegment(
    val text: String,
    val color: AnsiColor = AnsiColor.DEFAULT,
    val bold: Boolean = false,
    /** Explicit 24-bit foreground from SGR 38;2;r;g;b or the xterm-256 palette. */
    val rgb: Int? = null,
)

private val ANSI_SEQUENCE = Regex(
    "\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|" +
        "\u001B\\[[0-9;?]*[ -/]*[@-~]",
)

private val ANSI_SGR_COLORS = mapOf(
    30 to AnsiColor.BLACK,
    31 to AnsiColor.RED,
    32 to AnsiColor.GREEN,
    33 to AnsiColor.YELLOW,
    34 to AnsiColor.BLUE,
    35 to AnsiColor.MAGENTA,
    36 to AnsiColor.CYAN,
    37 to AnsiColor.WHITE,
    90 to AnsiColor.BRIGHT_BLACK,
    91 to AnsiColor.BRIGHT_RED,
    92 to AnsiColor.BRIGHT_GREEN,
    93 to AnsiColor.BRIGHT_YELLOW,
    94 to AnsiColor.BRIGHT_BLUE,
    95 to AnsiColor.BRIGHT_MAGENTA,
    96 to AnsiColor.BRIGHT_CYAN,
    97 to AnsiColor.BRIGHT_WHITE,
)

private val ANSI_PALETTE = listOf(
    AnsiColor.BLACK,
    AnsiColor.RED,
    AnsiColor.GREEN,
    AnsiColor.YELLOW,
    AnsiColor.BLUE,
    AnsiColor.MAGENTA,
    AnsiColor.CYAN,
    AnsiColor.WHITE,
    AnsiColor.BRIGHT_BLACK,
    AnsiColor.BRIGHT_RED,
    AnsiColor.BRIGHT_GREEN,
    AnsiColor.BRIGHT_YELLOW,
    AnsiColor.BRIGHT_BLUE,
    AnsiColor.BRIGHT_MAGENTA,
    AnsiColor.BRIGHT_CYAN,
    AnsiColor.BRIGHT_WHITE,
)

private val ERROR_TAGS = setOf("ERR", "ERROR", "ОШИБКА", "ФАТАЛ", "FATAL")
private val SUCCESS_TAGS = setOf("OK", "SUCCESS", "УСПЕХ", "ГОТОВО")
// The legacy terminal painted these blue-ish service tags green too.
private val LEGACY_SUCCESS_TAGS = setOf("NET", "СЕТЬ", "WRAP", "ВОРКЕР")

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

internal fun stripAnsi(text: String): String {
    if (!text.contains('\u001B')) return text
    return ANSI_SEQUENCE.replace(text, "")
}

private fun xterm256ToRgb(index: Int): Int? {
    if (index < 16 || index > 255) return null
    val (r, g, b) = if (index < 232) {
        val cube = index - 16
        val red = cube / 36
        val green = (cube % 36) / 6
        val blue = cube % 6
        Triple(
            if (red == 0) 0 else 55 + red * 40,
            if (green == 0) 0 else 55 + green * 40,
            if (blue == 0) 0 else 55 + blue * 40,
        )
    } else {
        val level = 8 + (index - 232) * 10
        Triple(level, level, level)
    }
    return (r shl 16) or (g shl 8) or b
}

/**
 * Splits a log body into colored segments. Every CSI/OSC sequence is removed from the
 * visible text; SGR sequences (`...m`) update the current color/weight, while cursor,
 * erase, title and other control sequences are simply dropped.
 */
internal fun parseAnsiSegments(text: String): List<AnsiSegment> {
    if (!text.contains('\u001B')) {
        return listOf(AnsiSegment(text))
    }

    val segments = mutableListOf<AnsiSegment>()
    var index = 0
    var color = AnsiColor.DEFAULT
    var bold = false
    var rgb: Int? = null

    fun append(text: String) {
        if (text.isEmpty()) return
        val previous = segments.lastOrNull()
        if (previous != null && previous.color == color && previous.bold == bold && previous.rgb == rgb) {
            segments[segments.lastIndex] = previous.copy(text = previous.text + text)
        } else {
            segments += AnsiSegment(text, color, bold, rgb)
        }
    }

    for (match in ANSI_SEQUENCE.findAll(text)) {
        if (match.range.first > index) {
            append(text.substring(index, match.range.first))
        }

        val sequence = match.value
        if (sequence.startsWith("\u001B[") && sequence.endsWith("m")) {
            val rawCodes = sequence.substring(2, sequence.length - 1)
            val codes = if (rawCodes.isEmpty()) {
                listOf(0)
            } else {
                rawCodes.split(';').mapNotNull(String::toIntOrNull)
            }
            var codeIndex = 0
            while (codeIndex < codes.size) {
                val code = codes[codeIndex]
                when {
                    code == 0 -> {
                        color = AnsiColor.DEFAULT
                        bold = false
                        rgb = null
                    }
                    code == 1 -> bold = true
                    code == 22 -> bold = false
                    code == 39 -> {
                        color = AnsiColor.DEFAULT
                        rgb = null
                    }
                    code == 38 -> {
                        when (codes.getOrNull(codeIndex + 1)) {
                            5 -> {
                                when (val paletteIndex = codes.getOrNull(codeIndex + 2)) {
                                    null -> Unit
                                    in 0..15 -> {
                                        color = ANSI_PALETTE[paletteIndex]
                                        rgb = null
                                    }
                                    else -> {
                                        rgb = xterm256ToRgb(paletteIndex)
                                    }
                                }
                                codeIndex += 2
                            }
                            2 -> {
                                val red = codes.getOrNull(codeIndex + 2)
                                val green = codes.getOrNull(codeIndex + 3)
                                val blue = codes.getOrNull(codeIndex + 4)
                                if (red != null && green != null && blue != null &&
                                    red in 0..255 && green in 0..255 && blue in 0..255
                                ) {
                                    rgb = (red shl 16) or (green shl 8) or blue
                                }
                                codeIndex += 4
                            }
                        }
                    }
                    ANSI_SGR_COLORS.containsKey(code) -> {
                        color = ANSI_SGR_COLORS.getValue(code)
                        rgb = null
                    }
                }
                codeIndex++
            }
        }

        index = match.range.last + 1
    }

    if (index < text.length) {
        append(text.substring(index))
    }
    return segments
}

internal fun parseMessageTag(rawMessage: String, level: LogLevel): Pair<String, String> {
    val trimmed = rawMessage.trim()
    if (trimmed.startsWith("[")) {
        val closeIndex = trimmed.indexOf(']')
        if (closeIndex in 1..24) {
            val tag = trimmed.substring(1, closeIndex).trim()
            val body = trimmed.substring(closeIndex + 1).trim()
            if (tag.isNotEmpty() && body.isNotEmpty()) {
                return tag to body
            }
        }
    }
    return level.name to trimmed
}

internal fun resolveLogLevel(level: LogLevel?, priority: Int, isError: Boolean): LogLevel {
    if (level != null) return level
    return when {
        isError || priority >= 6 -> LogLevel.ERR
        priority <= 3 -> LogLevel.DBG
        else -> LogLevel.LOG
    }
}

internal fun isErrorLog(tag: String, level: LogLevel, isError: Boolean): Boolean {
    if (isError || level == LogLevel.ERR) return true
    return tag.trim().uppercase(Locale.ROOT) in ERROR_TAGS
}

internal fun isSuccessLog(tag: String, level: LogLevel, isError: Boolean): Boolean {
    if (isErrorLog(tag, level, isError)) return false
    if (level == LogLevel.OK || level == LogLevel.NET) return true
    val normalized = tag.trim().uppercase(Locale.ROOT)
    return normalized in SUCCESS_TAGS || normalized in LEGACY_SUCCESS_TAGS
}

/**
 * Extracts `[TAG] body` while keeping ANSI escapes in the body so the terminal can color it.
 * Leading CSI/OSC escapes before the tag are ignored; the tag itself is stripped of them.
 */
internal fun parseMessageTagPreservingAnsi(rawMessage: String, level: LogLevel): Pair<String, String> {
    val trimmed = rawMessage.trim()
    val matches = ANSI_SEQUENCE.findAll(trimmed).iterator()
    var cursor = 0
    while (matches.hasNext()) {
        val match = matches.next()
        if (match.range.first != cursor) break
        cursor = match.range.last + 1
    }
    if (trimmed.getOrNull(cursor) == '[') {
        val closeIndex = trimmed.indexOf(']', startIndex = cursor + 1)
        if ((closeIndex - cursor) in 2..25) {
            val tag = stripAnsi(trimmed.substring(cursor + 1, closeIndex)).trim()
            val body = trimmed.substring(closeIndex + 1).trim()
            if (tag.isNotEmpty() && body.isNotEmpty()) {
                return tag to body
            }
        }
    }
    return level.name to trimmed
}

internal fun formatLogTime(
    timestampMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (timestampMs <= 0L) return ""
    return Instant.ofEpochMilli(timestampMs).atZone(zone).format(TIME_FORMATTER)
}

internal fun countLogsAfter(entries: List<LogEntry>, readUpToId: Long): Int =
    entries.count { it.id > readUpToId }

internal fun formatLogEntryForCopy(
    message: String,
    level: LogLevel,
    timestampMs: Long,
    count: Int,
): String {
    val cleaned = stripAnsi(message).trim()
    val (tag, body) = parseMessageTag(cleaned, level)
    val time = formatLogTime(timestampMs)
    return buildString {
        if (time.isNotEmpty()) {
            append(time).append(' ')
        }
        append('[').append(tag.uppercase(Locale.ROOT)).append("] ").append(body)
        if (count > 1) {
            append(" (x").append(count).append(')')
        }
    }
}
