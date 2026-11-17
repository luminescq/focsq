package com.csqtt.client.ui.legacy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.LogEntry
import com.csqtt.client.LogLevel
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSpacing
import com.csqtt.client.ui.legacy.design.CsqttTheme

private data class TagColors(
    val background: Color,
    val text: Color,
    val border: Color = Color.Transparent,
)

private fun isErrorLog(tag: String, level: LogLevel, isError: Boolean): Boolean {
    return isError || level == LogLevel.ERR ||
        tag.contains("ОШИБКА", ignoreCase = true) ||
        tag.contains("ФАТАЛ", ignoreCase = true) ||
        tag.contains("ERR", ignoreCase = true)
}

private fun isGreenLog(tag: String, level: LogLevel, isError: Boolean): Boolean {
    if (isErrorLog(tag, level, isError)) return false
    return level == LogLevel.OK ||
        level == LogLevel.NET ||
        tag.contains("OK", ignoreCase = true) ||
        tag.contains("NET", ignoreCase = true) ||
        tag.contains("СЕТЬ", ignoreCase = true) ||
        tag.contains("WRAP", ignoreCase = true) ||
        tag.contains("ВОРКЕР", ignoreCase = true) ||
        (tag.contains("ДЕПЛОЙ", ignoreCase = true) && (level == LogLevel.OK || level == LogLevel.NET))
}

private fun resolveTagColors(isGreen: Boolean, isError: Boolean): TagColors {
    if (isError) {
        return TagColors(
            background = Color(0xFF3E1218),
            text = Color(0xFFFF897D),
            border = Color(0xFF681D26),
        )
    }
    if (isGreen) {
        return TagColors(
            background = Color(0xFF052E16),
            text = Color(0xFF4ADE80),
            border = Color(0xFF14532D),
        )
    }
    return TagColors(
        background = Color(0xFF0A223D),
        text = Color(0xFF3DBFFD),
        border = Color(0xFF1475FD),
    )
}

private fun parseMessageTag(rawMessage: String, level: LogLevel): Pair<String, String> {
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

private fun parseAnsiLogBody(
    text: String,
    defaultColor: Color,
    greenColor: Color,
    redColor: Color,
): AnnotatedString {
    val ansiRegex = Regex("\u001B\\[([0-9;]+)m")
    if (!text.contains("\u001B[")) {
        return AnnotatedString(text)
    }
    val builder = AnnotatedString.Builder()
    var currentIndex = 0
    var currentColor = defaultColor

    for (match in ansiRegex.findAll(text)) {
        if (match.range.first > currentIndex) {
            val segment = text.substring(currentIndex, match.range.first)
            builder.pushStyle(SpanStyle(color = currentColor))
            builder.append(segment)
            builder.pop()
        }
        val code = match.groupValues[1]
        currentColor = when (code) {
            "32", "92" -> greenColor
            "31", "91" -> redColor
            "0" -> defaultColor
            else -> currentColor
        }
        currentIndex = match.range.last + 1
    }
    if (currentIndex < text.length) {
        val segment = text.substring(currentIndex)
        builder.pushStyle(SpanStyle(color = currentColor))
        builder.append(segment)
        builder.pop()
    }
    return builder.toAnnotatedString()
}

@Composable
internal fun LogLine(
    entry: LogEntry,
    modifier: Modifier = Modifier,
) {
    val terminalColors = CsqttTheme.extendedColors
    val level = entry.level ?: when {
        entry.isError -> LogLevel.ERR
        entry.priority <= 2 -> LogLevel.OK
        entry.priority <= 4 -> LogLevel.DBG
        entry.priority <= 6 -> LogLevel.NET
        else -> LogLevel.LOG
    }

    val (rawTag, body) = parseMessageTag(entry.message, level)
    val isError = isErrorLog(rawTag, level, entry.isError)
    val isGreen = isGreenLog(rawTag, level, isError)

    val displayTag = if (isGreen) "OK" else rawTag
    val tagColors = resolveTagColors(isGreen = isGreen, isError = isError)

    val bodyColor = when {
        isError -> terminalColors.terminalError
        isGreen -> terminalColors.terminalSuccess
        else -> terminalColors.terminalText
    }

    val annotatedBody = remember(body, bodyColor, terminalColors.terminalSuccess, terminalColors.terminalError) {
        parseAnsiLogBody(
            text = body,
            defaultColor = bodyColor,
            greenColor = terminalColors.terminalSuccess,
            redColor = terminalColors.terminalError,
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(CsqttSpacing.Sm),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = CsqttShapes.Pill,
            color = tagColors.background,
            contentColor = tagColors.text,
            border = BorderStroke(1.dp, tagColors.border),
            modifier = Modifier.defaultMinSize(minHeight = 22.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = displayTag,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }

        Text(
            text = annotatedBody,
            color = bodyColor,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            fontFamily = FontFamily.Monospace,
            fontWeight = if (entry.isError || level == LogLevel.ERR) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )

        if (entry.count > 1) {
            Surface(
                shape = CsqttShapes.Pill,
                color = Color(0xFF142438),
                contentColor = Color(0xFF3DBFFD),
                border = BorderStroke(1.dp, Color(0xFF1E4976)),
                modifier = Modifier.defaultMinSize(minHeight = 18.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "x${entry.count}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
