package com.csqtt.client.ui.legacy

import com.csqtt.client.CsqttConstants

// What `deploy.sh inventory` found on the server, and what the app offers for it (deploy-check-first-design.md §1–§3).
// The app looks before it offers: the user picks an action that fits the server, never a mode that assumes what is there.

internal enum class ServerPlace { STANDARD, SIDE, PANEL, OTHER, LEFTOVER }

internal data class FoundServer(
    val place: ServerPlace,
    val runtime: String,
    val state: String,
    val version: String?,
    val peerPort: Int?,
    val webPort: Int?,
    val name: String,
) : java.io.Serializable {
    /** Something runs or can be started there; "leftover" is only a binary, a store or a stopped foreign unit. */
    val installed: Boolean get() = state != "leftover" && place != ServerPlace.LEFTOVER
}

internal data class BusyPort(val proto: String, val port: Int, val holder: String)

internal data class ServerInventory(val servers: List<FoundServer>, val busy: List<BusyPort>)

/** null unless the check finished (its last line is CSQTT_INV|end): a half-read server is never planned on. */
internal fun parseServerInventory(output: String): ServerInventory? {
    val servers = mutableListOf<FoundServer>()
    val busy = mutableListOf<BusyPort>()
    var finished = false
    for (raw in output.lineSequence()) {
        val line = raw.trim()
        if (!line.startsWith("CSQTT_INV|")) continue
        val parts = line.split('|')
        val fields = parts.drop(2).mapNotNull { part ->
            part.indexOf('=').takeIf { it > 0 }?.let { part.substring(0, it) to part.substring(it + 1) }
        }.toMap()
        when (parts.getOrNull(1)) {
            "end" -> finished = true
            "server" -> {
                val place = when (fields["place"]) {
                    "standard" -> ServerPlace.STANDARD
                    "side" -> ServerPlace.SIDE
                    "panel" -> ServerPlace.PANEL
                    "other" -> ServerPlace.OTHER
                    "leftover" -> ServerPlace.LEFTOVER
                    else -> null
                } ?: continue
                servers += FoundServer(
                    place = place,
                    runtime = fields["runtime"].orEmpty(),
                    state = fields["state"].orEmpty(),
                    version = fields["version"]?.takeIf { it.isNotBlank() && it != "?" },
                    peerPort = fields["peer"]?.toIntOrNull(),
                    webPort = fields["web"]?.toIntOrNull(),
                    name = fields["name"].orEmpty(),
                )
            }
            "busy" -> {
                val port = fields["port"]?.toIntOrNull() ?: continue
                busy += BusyPort(fields["proto"].orEmpty(), port, fields["by"].orEmpty())
            }
        }
    }
    return if (finished) ServerInventory(servers, busy) else null
}

/** 2.5 and later speak the 2.5 protocol; 2.1 and older, or a binary without a version label, do not. */
internal fun isLine25(version: String?): Boolean {
    val parts = version?.split('.') ?: return false
    val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return major > 2 || (major == 2 && minor >= 5)
}

internal enum class PlanKind { INSTALL, UPDATE, INSTALL_NEXT_TO, REPLACE }

internal data class PlanAction(
    val kind: PlanKind,
    val instance: DeployInstance,
    val peerPort: Int,
    val webPort: Int,
    val title: String,
    val detail: String,
) : java.io.Serializable {
    val destructive: Boolean get() = kind == PlanKind.REPLACE
}

internal data class DeployPlan(
    val servers: List<FoundServer>,
    val actions: List<PlanAction>,
    val notes: List<String>,
) : java.io.Serializable

private fun runtimeLabel(runtime: String): String = when (runtime) {
    "systemd" -> "служба systemd"
    "docker" -> "Docker"
    "process" -> "процесс"
    else -> ""
}

/** One line for the "На сервере" list: what it is, then how it runs. */
internal fun describeServer(server: FoundServer, profilePeerPort: Int?): Pair<String, String> {
    if (server.place == ServerPlace.LEFTOVER || !server.installed) {
        val what = if (server.place == ServerPlace.LEFTOVER) "Остановленная служба FOCSQ" else "Остатки прежней установки FOCSQ"
        return what to server.name
    }
    val title = "FOCSQ " + (server.version ?: "(версия неизвестна)")
    val detail = listOfNotNull(
        when (server.place) {
            ServerPlace.SIDE -> "второй сервер (csqtt25)"
            ServerPlace.PANEL -> "управляет swg-panel"
            ServerPlace.OTHER -> "установлен не этим приложением: ${server.name}"
            else -> null
        },
        runtimeLabel(server.runtime).ifBlank { null },
        server.peerPort?.let { "порт $it" },
        when (server.state) {
            "running" -> "работает"
            "stopped" -> "остановлен"
            else -> null
        },
        if (profilePeerPort != null && server.peerPort == profilePeerPort &&
            (server.place == ServerPlace.STANDARD || server.place == ServerPlace.SIDE)) "подключение профиля" else null,
    ).joinToString(", ")
    return title to detail
}

/**
 * The actions that fit what the check found, the recommended one first (deploy-check-first-design.md §3). An update or a
 * replace keeps the server's own ports; a new server takes the form's ports with «Ручные порты» on ([manualPorts]),
 * else its place's defaults.
 */
internal fun planDeploy(
    inventory: ServerInventory,
    manualPorts: EffectiveServerPorts?,
    profilePeerPort: Int?,
    dockerInstall: Boolean,
    appVersion: String,
): DeployPlan {
    val servers = inventory.servers
    val notes = mutableListOf<String>()
    if (servers.any { it.place == ServerPlace.PANEL }) {
        notes += "Этим сервером управляет swg-panel — устанавливайте и обновляйте csqtt из панели"
        return DeployPlan(servers, emptyList(), notes)
    }
    val standard = servers.firstOrNull { it.place == ServerPlace.STANDARD }
    val liveStandard = standard?.takeIf { it.installed }
    val side = servers.firstOrNull { it.place == ServerPlace.SIDE }?.takeIf { it.installed }
    val others = servers.filter { it.place == ServerPlace.OTHER }
    val leftovers = servers.filter { it.place == ServerPlace.LEFTOVER }
    for (other in others) {
        notes += "Ещё один сервер csqtt установлен не этим приложением (${other.name}" +
            (other.peerPort?.let { ", порт $it" } ?: "") + "). Приложение его не трогает" +
            // what the user can do: the standard place's cleanup would stop it, so its actions are withheld
            if (liveStandard != null) ". Пока он работает, обновить или заменить сервер на стандартном месте нельзя — " +
                "это остановило бы и его. Остановите его и проверьте сервер заново" else ""
    }

    val wanted = if (dockerInstall) "docker" else "systemd"
    fun runtimeChange(server: FoundServer): String =
        if (server.runtime in setOf("systemd", "docker") && server.runtime != wanted) {
            " Сейчас ${runtimeLabel(server.runtime)}, будет ${runtimeLabel(wanted)}."
        } else ""
    val removed = if (leftovers.isEmpty()) "" else " Будет удалено: ${leftovers.joinToString { it.name }}."

    // ports a new server cannot take: another installed csqtt server's (a leftover reports the defaults it would have used,
    // and goes), or a program's the check saw on a default port
    val taken = servers.filter { it.installed }.flatMap { listOfNotNull(it.peerPort, it.webPort) }.toSet()
    fun clash(peer: Int, web: Int): String? {
        inventory.busy.firstOrNull { it.port == peer || it.port == web }?.let {
            return "Порт ${it.port} занят: ${it.holder}. Укажите другие порты («Ручные порты»)"
        }
        if (peer in taken || web in taken) {
            return "Порт ${if (peer in taken) peer else web} занят другим сервером csqtt. Укажите другие порты («Ручные порты»)"
        }
        return null
    }

    val actions = mutableListOf<PlanAction>()
    fun update(server: FoundServer, title: String) {
        val main = server.place == ServerPlace.STANDARD
        val instance = if (main) DeployInstance.MAIN else DeployInstance.SIDE
        actions += PlanAction(
            PlanKind.UPDATE, instance,
            server.peerPort ?: instance.defaultPeerPort, server.webPort ?: instance.defaultWebPort,
            title,
            "csqtt ${server.version ?: "?"} → $appVersion на порту ${server.peerPort ?: instance.defaultPeerPort}. " +
                "Клиенты и настройки сохранятся; пароль туннеля и вход в web-панель — из «Авторизации» этого профиля." +
                runtimeChange(server) + if (main) removed else "",
        )
    }
    fun newServer(kind: PlanKind, instance: DeployInstance, title: String, detail: (Int, Int) -> String) {
        // a manual port that is already another server's (2.5.1 prefilled the stock ones) means "not chosen" next to it
        val peer = manualPorts?.peer?.takeUnless { instance == DeployInstance.SIDE && it in taken } ?: instance.defaultPeerPort
        val web = manualPorts?.web?.takeUnless { instance == DeployInstance.SIDE && it in taken } ?: instance.defaultWebPort
        val problem = clash(peer, web)
        if (problem != null) notes += problem else actions += PlanAction(kind, instance, peer, web, title, detail(peer, web))
    }

    val updates = listOfNotNull(liveStandard?.takeIf { isLine25(it.version) && others.isEmpty() }, side)
        .sortedByDescending { it.peerPort == profilePeerPort }
    for (server in updates) update(server, if (updates.size > 1) "Обновить: порт ${server.peerPort}" else "Обновить")

    when {
        liveStandard != null && !isLine25(liveStandard.version) && side == null -> {
            val old = "csqtt ${liveStandard.version ?: "(версия неизвестна)"}"
            newServer(PlanKind.INSTALL_NEXT_TO, DeployInstance.SIDE, "Установить рядом") { peer, web ->
                "Второй сервер csqtt $appVersion на порты $peer и $web. $old продолжит работать для своих клиентов."
            }
            if (others.isEmpty()) {
                actions += PlanAction(
                    PlanKind.REPLACE, DeployInstance.MAIN,
                    liveStandard.peerPort ?: DeployInstance.MAIN.defaultPeerPort,
                    liveStandard.webPort ?: DeployInstance.MAIN.defaultWebPort,
                    "Заменить",
                    "$old заменяется на $appVersion на тех же портах. Клиенты и пароли сохранятся, но приложения 2.1 " +
                        "перестанут подключаться — им нужно приложение 2.5. Пароль туннеля и вход в web-панель — из " +
                        "«Авторизации» этого профиля." + runtimeChange(liveStandard) + removed,
                )
            }
        }
        liveStandard == null && side == null -> if (others.isEmpty()) {
            val kept = if (standard != null) " Клиенты прежней установки сохранятся." else ""
            newServer(PlanKind.INSTALL, DeployInstance.MAIN, "Установить") { peer, web ->
                "csqtt $appVersion на порты $peer и $web.$kept$removed"
            }
        } else {
            // the standard place may be the other server's (its paths, csqtt1): a second server of our own instead
            newServer(PlanKind.INSTALL, DeployInstance.SIDE, "Установить") { peer, web ->
                "csqtt $appVersion вторым сервером (csqtt25) на порты $peer и $web."
            }
        }
    }
    return DeployPlan(servers, actions, notes)
}

/**
 * The servers «Удалить» may remove: the two places this app installs into, with something installed there. Removing the
 * standard place runs the stock cleanup, which stops and deletes every csqtt it matches — withheld while a server this
 * app did not install, or swg-panel's, runs (the second server's removal touches only its own names).
 */
internal fun removableServers(inventory: ServerInventory): List<FoundServer> {
    val foreign = inventory.servers.any { it.place == ServerPlace.OTHER || it.place == ServerPlace.PANEL }
    return inventory.servers.filter {
        it.installed && (it.place == ServerPlace.SIDE || (it.place == ServerPlace.STANDARD && !foreign))
    }
}

/** What the removal list says beside the servers: why the standard one is withheld, what else its removal deletes. */
internal fun removalNotes(inventory: ServerInventory): List<String> {
    val notes = mutableListOf<String>()
    val standard = inventory.servers.any { it.place == ServerPlace.STANDARD && it.installed }
    val foreign = inventory.servers.filter { it.place == ServerPlace.OTHER || it.place == ServerPlace.PANEL }
    if (standard && foreign.isNotEmpty()) {
        notes += "Сервер на стандартном месте не удалить, пока работает ${foreign.joinToString { it.name }}: удаление " +
            "остановило бы и его"
    }
    val leftovers = inventory.servers.filter { it.place == ServerPlace.LEFTOVER }
    if (standard && foreign.isEmpty() && leftovers.isNotEmpty()) {
        notes += "Вместе с сервером на стандартном месте будет удалено: ${leftovers.joinToString { it.name }}"
    }
    return notes
}

internal fun FoundServer.deployInstance(): DeployInstance =
    if (place == ServerPlace.SIDE) DeployInstance.SIDE else DeployInstance.MAIN

internal fun FoundServer.peerPortOrDefault(): Int =
    peerPort ?: if (place == ServerPlace.SIDE) CsqttConstants.Network.DEFAULT_SIDE_SERVER_PEER_PORT
    else CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT
