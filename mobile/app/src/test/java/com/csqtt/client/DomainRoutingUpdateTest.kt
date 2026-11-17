package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRoutingUpdateTest {

    @Test
    fun blackModeKeepsIpRulesAndFlags() {
        val update = buildDomainRoutingUpdate(
            domains = DomainPrefs(
                excludeRuZones = true,
                templateDomains = true,
                isWhitelist = false,
                black = "example.com",
                white = "white.example",
            ),
            ips = IpPrefs(
                isWhitelist = false,
                black = "10.0.0.0/24",
                white = "192.168.0.1",
            ),
        )

        assertTrue(update.enabled)
        assertTrue(update.excludeRuZones)
        assertTrue(update.templateDomains)
        assertEquals("example.com", update.directDomains)
        assertFalse(update.isWhitelist)
        assertTrue(update.ipRoutingEnabled)
        assertFalse(update.ipIsWhitelist)
        assertEquals("10.0.0.0/24", update.directIps)
    }

    @Test
    fun whiteModeUsesWhiteListsAndDropsRuFlags() {
        val update = buildDomainRoutingUpdate(
            domains = DomainPrefs(
                excludeRuZones = true,
                templateDomains = true,
                isWhitelist = true,
                black = "old-black.example",
                white = "white.example",
            ),
            ips = IpPrefs(
                isWhitelist = true,
                black = "10.0.0.1",
                white = "192.168.0.1",
            ),
        )

        assertEquals("white.example", update.directDomains)
        assertTrue(update.enabled)
        assertFalse(update.excludeRuZones)
        assertFalse(update.templateDomains)
        assertTrue(update.isWhitelist)
        assertTrue(update.ipRoutingEnabled)
        assertTrue(update.ipIsWhitelist)
        assertEquals("192.168.0.1", update.directIps)
    }

    @Test
    fun emptyActiveListsDisableRoutingWithoutLosingTheOtherList() {
        val update = buildDomainRoutingUpdate(
            domains = DomainPrefs(
                excludeRuZones = false,
                templateDomains = false,
                isWhitelist = false,
                black = "",
                white = "white.example",
            ),
            ips = IpPrefs(
                isWhitelist = true,
                black = "10.0.0.1",
                white = "",
            ),
        )

        assertFalse(update.enabled)
        assertEquals("", update.directDomains)
        assertFalse(update.ipRoutingEnabled)
        assertEquals("", update.directIps)
    }

    @Test
    fun templateAloneEnablesBlackDomainMode() {
        val update = buildDomainRoutingUpdate(
            domains = DomainPrefs(
                excludeRuZones = false,
                templateDomains = true,
                isWhitelist = false,
                black = "",
                white = "",
            ),
            ips = IpPrefs(false, "", ""),
        )

        assertTrue(update.enabled)
        assertFalse(update.ipRoutingEnabled)
    }
}
