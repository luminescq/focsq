package com.csqtt.client.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSelectionOrderingTest {
    private data class App(val pkg: String, val name: String)
    private val apps = listOf(App("z", "Zulu"), App("b", "beta"), App("a", "Alpha"), App("c", "Charlie"))
    private fun order(selected: Set<String>) =
        selectedApplicationsFirst(apps, selected, { it.pkg }, { it.name }).map { it.pkg }

    @Test fun selectedAppsPrecedeUnselectedAndEachGroupIsAlphabetical() {
        assertEquals(listOf("b", "z", "a", "c"), order(setOf("z", "b")))
    }
    @Test fun blackAndWhiteListsUseTheirOwnSelection() {
        assertEquals(listOf("c", "a", "b", "z"), order(setOf("c")))
        assertEquals(listOf("z", "a", "b", "c"), order(setOf("z")))
    }
    @Test fun emptySelectionAndUnknownPackagesKeepAlphabeticalOrder() {
        assertEquals(listOf("a", "b", "c", "z"), order(emptySet()))
        assertEquals(listOf("a", "b", "c", "z"), order(setOf("not.installed")))
    }
}
