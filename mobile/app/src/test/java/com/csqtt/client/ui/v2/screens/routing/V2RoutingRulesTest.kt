package com.csqtt.client.ui.v2.screens.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2RoutingRulesTest {

    @Test
    fun domainsAreCleanedDedupedAndValidated() {
        val parsed = parseDomainsFromText(
            " https://WWW.Example.com/path, example.com; bad, 1.2.3.4 ",
        )
        assertEquals(listOf("example.com"), parsed)
    }

    @Test
    fun domainParserCapsAtSharedLimit() {
        val total = MAX_ROUTING_RULES + 10
        val text = (1..total).joinToString(",") { "d$it.com" }

        assertEquals(MAX_ROUTING_RULES, parseDomainsFromText(text).size)
        assertEquals(total, parseDomainsFromText(text, Int.MAX_VALUE).size)
    }

    @Test
    fun ipParserSupportsCidrRangesAndRejectsReversedRange() {
        assertTrue(isValidIpRule("10.0.0.0/24"))
        assertFalse(isValidIpRule("10.0.0.0/33"))
        assertTrue(isValidIpRule("10.0.0.1-10.0.0.5"))
        assertFalse(isValidIpRule("10.0.0.5-10.0.0.1"))
        assertTrue(isValidIpRule("10.0.0.7"))
        assertFalse(isValidIpRule("10.0.0.256"))
    }

    @Test
    fun ipParserCleansDedupesAndCapsAtLimit() {
        val total = MAX_ROUTING_RULES + 10
        val text = (0 until total).joinToString(",") { "10.0.${it / 256}.${it % 256}" }
            .let { "$it,${it.split(',').first()}" }

        val parsed = parseIpsFromText(text)
        assertEquals(MAX_ROUTING_RULES, parsed.size)
        assertEquals(MAX_ROUTING_RULES + 10, parseIpsFromText(text, Int.MAX_VALUE).size)
    }

    @Test
    fun domainCountLabelAccountsForTemplateAndMode() {
        assertEquals(
            "В туннеле 3 доменов",
            domainsCountLabel(
                isWhitelist = true,
                listSize = 3,
                templateDomainsEnabled = true,
                templateSize = 10,
                customCount = 0,
            ),
        )
        assertEquals(
            "Настроено 12 доменов",
            domainsCountLabel(
                isWhitelist = false,
                listSize = 12,
                templateDomainsEnabled = true,
                templateSize = 10,
                customCount = 2,
            ),
        )
        assertEquals(
            "Настроено 10 доменов (RU)",
            domainsCountLabel(
                isWhitelist = false,
                listSize = 10,
                templateDomainsEnabled = true,
                templateSize = 10,
                customCount = 0,
            ),
        )
        assertEquals(
            "Настроено 0 доменов",
            domainsCountLabel(
                isWhitelist = false,
                listSize = 0,
                templateDomainsEnabled = false,
                templateSize = 10,
                customCount = 0,
            ),
        )
        assertEquals(
            "Настроено 5 доменов",
            domainsCountLabel(
                isWhitelist = false,
                listSize = 5,
                templateDomainsEnabled = false,
                templateSize = 10,
                customCount = 0,
            ),
        )
    }

    @Test
    fun appExportParsesBothSections() {
        val parsed = parseAppExportFromText(
            """
            Чёрный-список:
            com.example.one
            com.example.two # заметка
            белый список:
            com.example.three
            """.trimIndent(),
        )
        assertNotNull(parsed)
        assertEquals(setOf("com.example.one", "com.example.two"), parsed!!.first)
        assertEquals(setOf("com.example.three"), parsed.second)
    }
}
