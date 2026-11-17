package com.csqtt.client.ui.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCheckTest {
    private fun inv(vararg lines: String): ServerInventory =
        parseServerInventory((lines.toList() + "CSQTT_INV|end").joinToString("\n"))!!

    private fun server(place: String, version: String, peer: Int, web: Int, state: String = "running", runtime: String = "systemd", name: String = "csqtt.service") =
        "CSQTT_INV|server|place=$place|runtime=$runtime|state=$state|version=$version|peer=$peer|web=$web|name=$name"

    private val std21 = server("standard", "2.1.9", 46000, 46002)
    private val std250 = server("standard", "2.5.0", 46000, 46002)
    private val side252 = server("side", "2.5.2", 46100, 46102, name = "csqtt25.service")
    private val storeOnly = server("standard", "?", 46000, 46002, state = "leftover", runtime = "none", name = "/etc/csqtt")

    private fun plan(inventory: ServerInventory, manual: EffectiveServerPorts? = null, profilePeer: Int? = 46000, docker: Boolean = false) =
        planDeploy(inventory, manual, profilePeer, docker, "2.5.2")

    private fun DeployPlan.kinds() = actions.map { "${it.kind}:${it.instance}:${it.peerPort}/${it.webPort}" }

    @Test
    fun `the check result is trusted only when it finished`() {
        assertNull(parseServerInventory(std250))
        val parsed = inv("noise", std250 + "|future=1", "CSQTT_INV|server|place=nowhere|state=running", "CSQTT_INV|busy|proto=udp|port=46000|by=nginx (PID 9, /usr/sbin/nginx)")
        assertEquals(1, parsed.servers.size)
        assertEquals("2.5.0", parsed.servers[0].version)
        assertEquals(46000, parsed.busy.single().port)
        assertNull(inv(server("standard", "?", 46000, 46002)).servers.single().version)
    }

    @Test
    fun `the protocol line comes from the version label`() {
        assertTrue(isLine25("2.5.0") && isLine25("2.5.2") && isLine25("2.6") && isLine25("3.0"))
        assertFalse(isLine25("2.1.9") || isLine25("2.0") || isLine25("2") || isLine25(null) || isLine25("x"))
    }

    @Test
    fun `an empty server gets the standard place on the standard ports, or the form's`() {
        assertEquals(listOf("INSTALL:MAIN:46000/46002"), plan(inv()).kinds())
        assertEquals(listOf("INSTALL:MAIN:47000/47002"), plan(inv(), EffectiveServerPorts(22, 47000, 47002)).kinds())
        // the store an uninstall kept is reused: its clients come back
        val reuse = plan(inv(storeOnly))
        assertEquals(listOf("INSTALL:MAIN:46000/46002"), reuse.kinds())
        assertTrue(reuse.actions[0].detail.contains("Клиенты прежней установки сохранятся"))
    }

    @Test
    fun `a 2_5 server is updated on its own ports, whatever the form says`() {
        val p = plan(inv(server("standard", "2.5.0", 47000, 47002)), EffectiveServerPorts(22, 46000, 46002))
        assertEquals(listOf("UPDATE:MAIN:47000/47002"), p.kinds())
        assertEquals("Обновить", p.actions[0].title)
        assertTrue(p.actions[0].detail.contains("2.5.0 → 2.5.2"))
        assertTrue(plan(inv(std250), docker = true).actions[0].detail.contains("будет Docker"))
        assertFalse(plan(inv(std250)).actions[0].detail.contains("будет"))
    }

    @Test
    fun `an older server gets a second server next to it first, and a replace that asks again`() {
        val p = plan(inv(std21))
        assertEquals(listOf("INSTALL_NEXT_TO:SIDE:46100/46102", "REPLACE:MAIN:46000/46002"), p.kinds())
        assertFalse(p.actions[0].destructive)
        assertTrue(p.actions[1].destructive)
        assertTrue(p.actions[1].detail.contains("Клиенты и пароли сохранятся"))
        // the stock ports 2.5.1 prefilled into «Ручные порты» belong to the old server: next to it they mean "not chosen"
        assertEquals("INSTALL_NEXT_TO:SIDE:46100/46102", plan(inv(std21), EffectiveServerPorts(22, 46000, 46002)).kinds()[0])
        assertEquals("INSTALL_NEXT_TO:SIDE:47000/47002", plan(inv(std21), EffectiveServerPorts(22, 47000, 47002)).kinds()[0])
        // a server without a version label is treated as an older one
        assertEquals(2, plan(inv(server("standard", "?", 46000, 46002))).actions.size)
    }

    @Test
    fun `with our second server there, only it is updated`() {
        assertEquals(listOf("UPDATE:SIDE:46100/46102"), plan(inv(std21, side252)).kinds())
        // the old server removed: its kept store is no place for another 2.5 server
        assertEquals(listOf("UPDATE:SIDE:46100/46102"), plan(inv(storeOnly, side252)).kinds())
        // two 2.5 servers (the old one replaced later): each updated, the profile's first
        val two = plan(inv(std250, side252), profilePeer = 46100)
        assertEquals(listOf("UPDATE:SIDE:46100/46102", "UPDATE:MAIN:46000/46002"), two.kinds())
        assertEquals("Обновить: порт 46100", two.actions[0].title)
        assertTrue(describeServer(two.servers[1], 46100).second.contains("подключение профиля"))
        assertFalse(describeServer(two.servers[0], 46100).second.contains("подключение профиля"))
    }

    @Test
    fun `a server swg-panel runs is left to the panel`() {
        val p = plan(inv(server("panel", "2.1.9", 56000, 56002, name = "swg-csqtt-csqtt1.service"), std250))
        assertTrue(p.actions.isEmpty())
        assertTrue(p.notes.single().contains("swg-panel"))
    }

    @Test
    fun `a server this app did not install keeps the standard place out of reach`() {
        val other = server("other", "2.5.0", 47000, 47002, name = "csqtt47.service")
        val p = plan(inv(std250, other))
        assertTrue(p.actions.isEmpty())
        assertTrue(p.notes.single().contains("csqtt47.service") && p.notes.single().contains("Остановите его"))
        assertFalse(plan(inv(other)).notes.single().contains("Остановите его"))
        assertEquals(listOf("INSTALL_NEXT_TO:SIDE:46100/46102"), plan(inv(std21, other)).kinds())
        assertEquals(listOf("INSTALL:SIDE:46100/46102"), plan(inv(other)).kinds())
        // a stopped one the standard place's cleanup removes is said so
        val gone = plan(inv(std250, server("leftover", "?", 47000, 47002, state = "stopped", name = "csqtt47.service")))
        assertEquals(listOf("UPDATE:MAIN:46000/46002"), gone.kinds())
        assertTrue(gone.actions[0].detail.contains("Будет удалено: csqtt47.service"))
    }

    @Test
    fun `a busy port withholds the install that needs it and says what holds it`() {
        val busy = "CSQTT_INV|busy|proto=udp|port=46000|by=nginx (PID 9, /usr/sbin/nginx)"
        val p = plan(inv(busy))
        assertTrue(p.actions.isEmpty())
        assertTrue(p.notes.single().contains("nginx"))
        assertEquals(listOf("INSTALL:MAIN:47000/47002"), plan(inv(busy), EffectiveServerPorts(22, 47000, 47002)).kinds())
    }

    @Test
    fun `removal offers only what this app installs, and the standard place only with no foreign server`() {
        val panel = server("panel", "2.5.0", 56100, 56102, name = "swg-csqtt-csqtt2.service")
        val other = server("other", "?", 47000, 47002, name = "csqtt47.service")
        val mine = inv(std21, side252, storeOnly)
        assertEquals(listOf(ServerPlace.STANDARD, ServerPlace.SIDE), removableServers(mine).map { it.place })
        assertEquals(DeployInstance.SIDE, removableServers(mine)[1].deployInstance())
        assertEquals(46100, removableServers(mine)[1].peerPortOrDefault())
        assertTrue(removalNotes(mine).isEmpty())
        // the stock cleanup of a standard-place removal would stop the foreign server: withheld, and said why
        for (foreign in listOf(panel, other)) {
            val box = inv(std21, side252, foreign)
            assertEquals(listOf(ServerPlace.SIDE), removableServers(box).map { it.place })
            assertTrue(removalNotes(box).single().contains("не удалить"))
        }
        // what else the standard removal deletes is named
        val withLeftover = inv(std250, server("leftover", "?", 47000, 47002, state = "stopped", runtime = "docker", name = "mycsqtt"))
        assertTrue(removalNotes(withLeftover).single().contains("mycsqtt"))
    }

    @Test
    fun `an update or a replace says the tunnel password and web login come from this profile`() {
        assertTrue(plan(inv(std250)).actions[0].detail.contains("«Авторизации» этого профиля"))
        assertTrue(plan(inv(std21)).actions[1].detail.contains("«Авторизации» этого профиля"))
    }
}
