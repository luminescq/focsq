package com.csqtt.client.ui.v2.screens.logs

import com.csqtt.client.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class V2LogFormatTest {

    @Test
    fun stripAnsiRemovesColorsAndNonSgrSequences() {
        assertEquals(
            "green red",
            stripAnsi("\u001B[1;32mgreen\u001B[0m \u001B[31mred"),
        )
        assertEquals(
            "abc",
            stripAnsi("a\u001B[2Kb\u001B[?25lc"),
        )
        assertEquals("plain", stripAnsi("plain"))
        assertEquals("ab", stripAnsi("a\u001B]0;title\u0007b"))
        assertEquals("ab", stripAnsi("a\u001B]0;title\u001B\\b"))
    }

    @Test
    fun parses256AndRgbColors() {
        assertEquals(
            listOf(AnsiSegment("cube", AnsiColor.DEFAULT, rgb = 0xFF0000)),
            parseAnsiSegments("\u001B[38;5;196mcube"),
        )
        assertEquals(
            listOf(AnsiSegment("rgb", AnsiColor.DEFAULT, rgb = 0x010203)),
            parseAnsiSegments("\u001B[38;2;1;2;3mrgb"),
        )
        assertEquals(
            listOf(AnsiSegment("palette", AnsiColor.BRIGHT_RED)),
            parseAnsiSegments("\u001B[38;5;9mpalette"),
        )
        assertEquals(
            listOf(
                AnsiSegment("red", AnsiColor.DEFAULT, rgb = 0x010203),
                AnsiSegment(" normal", AnsiColor.DEFAULT),
            ),
            parseAnsiSegments("\u001B[38;2;1;2;3mred\u001B[0m normal"),
        )
    }

    @Test
    fun tagParsingKeepsAnsiInTheBodyAndStripsItFromTheTag() {
        val colored = parseMessageTagPreservingAnsi(
            "\u001B[32m[OK]\u001B[0m done",
            LogLevel.LOG,
        )
        assertEquals("OK", colored.first)
        assertEquals("\u001B[0m done", colored.second)

        assertEquals(
            "NET" to "connected",
            parseMessageTagPreservingAnsi("[NET] connected", LogLevel.LOG),
        )
        assertEquals(
            "ERR" to "plain message",
            parseMessageTagPreservingAnsi("plain message", LogLevel.ERR),
        )
    }

    @Test
    fun legacyNetworkTagsStayGreen() {
        assertTrue(isSuccessLog("NET", LogLevel.LOG, isError = false))
        assertTrue(isSuccessLog("СЕТЬ", LogLevel.LOG, isError = false))
        assertTrue(isSuccessLog("WRAP", LogLevel.LOG, isError = false))
        assertTrue(isSuccessLog("ВОРКЕР", LogLevel.LOG, isError = false))
        assertTrue(isSuccessLog("anything", LogLevel.NET, isError = false))
        assertFalse(isSuccessLog("PING", LogLevel.PING, isError = false))
        assertFalse(isSuccessLog("TURN", LogLevel.TURN, isError = false))
    }

    @Test
    fun newLogCounterSurvivesAFullTrimmedBuffer() {
        val entries = (150L..199L).map { id ->
            com.csqtt.client.LogEntry(id = id, key = "key$id", message = "line")
        }
        assertEquals(50, countLogsAfter(entries, readUpToId = 149L))
        assertEquals(10, countLogsAfter(entries, readUpToId = 189L))
        assertEquals(0, countLogsAfter(entries, readUpToId = 199L))
    }

    @Test
    fun parsesCompositeSgrCodesIntoColoredBoldSegments() {
        assertEquals(
            listOf(
                AnsiSegment("OK", AnsiColor.GREEN, bold = true),
                AnsiSegment(" done", AnsiColor.DEFAULT, bold = false),
            ),
            parseAnsiSegments("\u001B[1;32mOK\u001B[0m done"),
        )
    }

    @Test
    fun code39RestoresDefaultColorAnd22ClearsBold() {
        assertEquals(
            listOf(
                AnsiSegment("red", AnsiColor.RED, bold = true),
                AnsiSegment(" normal", AnsiColor.DEFAULT, bold = false),
            ),
            parseAnsiSegments("\u001B[1;31mred\u001B[22;39m normal"),
        )
    }

    @Test
    fun cursorAndEraseSequencesNeverReachTheVisibleText() {
        assertEquals(
            listOf(AnsiSegment("beforeafter", AnsiColor.DEFAULT)),
            parseAnsiSegments("before\u001B[2Kafter\u001B[?25l"),
        )
        assertEquals(
            listOf(AnsiSegment("ab", AnsiColor.DEFAULT)),
            parseAnsiSegments("a\u001B]0;title\u0007b"),
        )
    }

    @Test
    fun tagClassificationIsExact() {
        assertTrue(isErrorLog("ERR", LogLevel.LOG, isError = false))
        assertTrue(isErrorLog("ERROR", LogLevel.LOG, isError = false))
        assertTrue(isErrorLog("ОШИБКА", LogLevel.LOG, isError = false))
        assertFalse(isErrorLog("NETWORK", LogLevel.LOG, isError = false))
        assertFalse(isErrorLog("OK", LogLevel.LOG, isError = false))

        assertTrue(isSuccessLog("OK", LogLevel.LOG, isError = false))
        assertTrue(isSuccessLog("готово", LogLevel.LOG, isError = false))
        assertTrue(isSuccessLog("NET", LogLevel.NET, isError = false))
        assertFalse(isSuccessLog("PING", LogLevel.PING, isError = false))
        assertFalse(isSuccessLog("TURN", LogLevel.TURN, isError = false))
        assertFalse(isSuccessLog("OK", LogLevel.OK, isError = true))
    }

    @Test
    fun androidPriorityMapsToSeverityWithoutInventingNetworkLevels() {
        assertEquals(LogLevel.ERR, resolveLogLevel(null, priority = 6, isError = false))
        assertEquals(LogLevel.ERR, resolveLogLevel(null, priority = 7, isError = false))
        assertEquals(LogLevel.LOG, resolveLogLevel(null, priority = 5, isError = false))
        assertEquals(LogLevel.LOG, resolveLogLevel(null, priority = 4, isError = false))
        assertEquals(LogLevel.DBG, resolveLogLevel(null, priority = 3, isError = false))
        assertEquals(LogLevel.OK, resolveLogLevel(LogLevel.OK, priority = 99, isError = false))
    }

    @Test
    fun copyFormatStripsAnsiAndAddsAMissingLevelTag() {
        assertEquals(
            "[OK] готово (x3)",
            formatLogEntryForCopy("\u001B[32m[OK] готово\u001B[0m", LogLevel.LOG, 0L, 3),
        )
        assertEquals(
            "[ERR] plain message",
            formatLogEntryForCopy("plain message", LogLevel.ERR, 0L, 1),
        )
    }

    @Test
    fun timestampFormattingIsStableAndEmptyForUnknownEntries() {
        assertEquals("", formatLogTime(0L, ZoneOffset.UTC))
        assertEquals("22:13:20", formatLogTime(1_700_000_000_000L, ZoneOffset.UTC))
    }
}
