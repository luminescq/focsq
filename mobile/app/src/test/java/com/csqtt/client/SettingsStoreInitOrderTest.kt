package com.csqtt.client

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreInitOrderTest {
    @Test
    fun `the init block that starts the cache collectors comes after every property it reads`() {
        val lines = listOf(
            File("app/src/main/java/com/csqtt/client/SettingsStore.kt"),
            File("src/main/java/com/csqtt/client/SettingsStore.kt"),
        ).first(File::isFile).readLines()
        val property = Regex("""^    (private |internal )?(val|var) (\w+)""")
        val properties = lines.withIndex()
            .mapNotNull { (i, line) -> property.find(line)?.let { it.groupValues[3] to i } }
            .toMap()
        val init = lines.indexOfFirst { it == "    init {" }
        assertTrue("SettingsStore has no init block", init >= 0)
        val end = (init until lines.size).first { lines[it] == "    }" }

        val collected = Regex("""launch \{ (\w+)\.collect""").findAll(lines.subList(init, end).joinToString("\n"))
            .map { it.groupValues[1] }.toList()
        assertTrue("the init block no longer starts the cache collectors", collected.size >= 20)
        for (name in collected) {
            val at = properties[name]
            assertTrue("$name is not a property of SettingsStore", at != null)
            assertTrue("$name is declared after the init block that collects it (null when collected)", at!! < init)
        }
        val after = properties.filterValues { it > init }.keys
        assertTrue("properties declared after the init block: $after", after.isEmpty())
    }
}
