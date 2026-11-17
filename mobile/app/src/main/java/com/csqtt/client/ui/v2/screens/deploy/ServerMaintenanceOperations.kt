package com.csqtt.client.ui.v2.screens.deploy

import android.content.Context
import com.csqtt.client.TunnelManager
import com.csqtt.client.ui.legacy.createSSHClient
import com.csqtt.client.ui.legacy.friendlyDeployError
import com.csqtt.client.ui.legacy.rootCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ServerInspectionResult(
    val checkedAtMs: Long = 0L,
    val isSuccess: Boolean = false,
    val arch: String = "",
    val serviceStatus: String = "", // "systemd_active", "docker_active", "process_running", "inactive"
    val installedVersion: String = "",
    val message: String = "",
    val panelAvailable: Boolean = false,
)

/**
 * Long-lived state for server maintenance actions. The screen only observes it, so leaving the
 * Deploy tab (or opening settings) neither cancels an in-flight SSH operation nor drops the
 * inspection result / busy indicator.
 */
object ServerMaintenanceState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val isInspecting = MutableStateFlow(false)
    val isPlanning = MutableStateFlow(false)
    val inspectionResult = MutableStateFlow<ServerInspectionResult?>(null)
    val isChangingSecretPath = MutableStateFlow(false)
    val changeSecretPathStage = MutableStateFlow("")
    @Volatile
    var lastAutoInspectAttemptMs: Long = 0L
    @Volatile
    var lastAutoInspectKey: String? = null
}

private const val INSPECT_TIMEOUT_MS = 20_000L
private const val COMMAND_TIMEOUT_MS = 180_000L

private fun secretPathRuntimeLabel(runtime: String): String = when (runtime) {
    "systemd" -> "systemd"
    "docker" -> "Docker"
    else -> ""
}

private class RemoteCommandResult(val exitStatus: Int, val output: String)

private fun readCommand(
    remote: Session.Command,
    timeoutMs: Long,
    onLine: ((String) -> Unit)? = null,
): RemoteCommandResult {
    val output = StringBuilder()
    val finished = CountDownLatch(1)
    val reader = Thread({
        try {
            remote.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) { output.appendLine(line) }
                    if (onLine != null) runCatching { onLine(line) }
                }
            }
        } catch (_: Exception) {
        } finally {
            finished.countDown()
        }
    }, "csqtt-remote-command")
    reader.isDaemon = true
    reader.start()

    val completed = finished.await(timeoutMs, TimeUnit.MILLISECONDS)
    if (!completed) {
        runCatching { remote.close() }
        return RemoteCommandResult(-1, output.toString())
    }
    runCatching { remote.join(3, TimeUnit.SECONDS) }
    return RemoteCommandResult(remote.exitStatus ?: -1, output.toString())
}

private fun execBounded(session: Session, command: String, timeoutMs: Long): RemoteCommandResult =
    readCommand(session.exec(command), timeoutMs)

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

object ServerMaintenanceOperations {

    suspend fun inspectServer(
        context: Context,
        host: String,
        user: String,
        pass: String,
        port: Int,
        webPort: Int = 0,
        panelSecretPath: String = "",
        privateKey: String = "",
        keyPassphrase: String = "",
        certificate: String = "",
    ): ServerInspectionResult = withContext(Dispatchers.IO) {
        var ssh: SSHClient? = null
        fun failure(message: String): ServerInspectionResult {
            TunnelManager.addDeployErrorLog("Проверка VPS: $message")
            return ServerInspectionResult(
                checkedAtMs = System.currentTimeMillis(),
                isSuccess = false,
                message = message,
            )
        }
        try {
            ssh = createSSHClient(context, host, user, pass, port, privateKey, keyPassphrase, certificate)
            val session = ssh.startSession()
            val inspectScript = """
                arch=$(uname -m 2>/dev/null || echo "unknown")
                svc="inactive"
                if command -v systemctl >/dev/null 2>&1 && systemctl is-active csqtt >/dev/null 2>&1; then
                    svc="systemd_active"
                elif command -v docker >/dev/null 2>&1 && [ "$(docker inspect --format '{{.State.Running}}' csqtt 2>/dev/null)" = "true" ]; then
                    svc="docker_active"
                elif pgrep -x csqtt >/dev/null 2>&1; then
                    svc="process_running"
                fi
                ver=""
                if [ -x /usr/local/bin/csqtt ]; then
                    ver=$(/usr/local/bin/csqtt --version 2>/dev/null || /usr/local/bin/csqtt -v 2>/dev/null || strings /usr/local/bin/csqtt 2>/dev/null | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+' | head -n1)
                elif [ "${'$'}svc" = "docker_active" ]; then
                    ver=$(docker exec csqtt /usr/local/bin/csqtt --version 2>/dev/null || echo "")
                fi
                panel="no"
                if [ "${'$'}svc" != "inactive" ] && [ "$webPort" -gt 0 ]; then
                    probe='${panelSecretPath.trim().trimStart('/').replace("'", "'\"'\"'")}'
                    for scheme in https http; do
                        code=$(curl -k -sS --noproxy '*' -o /dev/null -w '%{http_code}' \
                            --connect-timeout 1 --max-time 2 \
                            "${'$'}scheme://127.0.0.1:$webPort/${'$'}probe" 2>/dev/null || true)
                        case "${'$'}code" in
                            2??|3??|401|403) panel="yes"; break ;;
                        esac
                    done
                fi
                echo "RESULT|${'$'}arch|${'$'}svc|${'$'}ver|${'$'}panel"
            """.trimIndent()

            val result = execBounded(
                session,
                "bash -c " + shellQuote(inspectScript),
                INSPECT_TIMEOUT_MS,
            )
            session.close()

            if (result.exitStatus != 0) {
                val detail = result.output.trim().lineSequence()
                    .lastOrNull { it.isNotBlank() }
                    ?.take(200)
                val reason = when {
                    detail.isNullOrBlank() -> "VPS не ответил за ${INSPECT_TIMEOUT_MS / 1000} с"
                    else -> "код ${result.exitStatus}: $detail"
                }
                return@withContext failure("Не удалось проверить VPS ($reason)")
            }

            val match = result.output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("RESULT|") }
                ?: run {
                    val detail = result.output.trim().lineSequence()
                        .lastOrNull { it.isNotBlank() }
                        ?.take(200)
                    return@withContext failure(
                        if (detail.isNullOrBlank()) {
                            "VPS вернул пустой ответ на проверку"
                        } else {
                            "Не удалось разобрать ответ VPS: $detail"
                        },
                    )
                }

            val parts = match.split("|")
            val arch = parts.getOrNull(1).orEmpty()
            val svc = parts.getOrNull(2).orEmpty()
            val ver = parts.getOrNull(3).orEmpty()
            val panelAvailable = parts.getOrNull(4) == "yes"
            val statusText = when (svc) {
                "systemd_active" -> "Служба systemd активна"
                "docker_active" -> "Docker-контейнер активен"
                "process_running" -> "Процесс FOCSQ запущен"
                else -> "Служба не запущена"
            }
            TunnelManager.addDeploySuccessLog(
                "VPS: $statusText (арх: $arch, вер: ${ver.ifBlank { "не определена" }}, " +
                    "панель: ${if (panelAvailable) "доступна" else "недоступна"})",
            )
            ServerInspectionResult(
                checkedAtMs = System.currentTimeMillis(),
                isSuccess = true,
                arch = arch.ifBlank { "unknown" },
                serviceStatus = svc.ifBlank { "unknown" },
                installedVersion = ver.ifBlank { "не определена" },
                message = statusText,
                panelAvailable = panelAvailable,
            )
        } catch (e: Exception) {
            failure(friendlyDeployError(e.message))
        } finally {
            try { ssh?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun readPanelCredentials(
        context: Context,
        host: String,
        user: String,
        pass: String,
        port: Int,
        privateKey: String = "",
        keyPassphrase: String = "",
        certificate: String = "",
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        var ssh: SSHClient? = null
        try {
            ssh = createSSHClient(context, host, user, pass, port, privateKey, keyPassphrase, certificate)
            val session = ssh.startSession()
            val script = """
                ENV_FILE=/etc/csqtt/csqtt.env
                if [ -f "${'$'}ENV_FILE" ]; then
                    cat "${'$'}ENV_FILE"
                elif command -v docker >/dev/null 2>&1; then
                    echo "CSQTT_WEB_USER=${'$'}(docker exec csqtt printenv CSQTT_WEB_USER 2>/dev/null || true)"
                    echo "CSQTT_WEB_PASS=${'$'}(docker exec csqtt printenv CSQTT_WEB_PASS 2>/dev/null || true)"
                fi
            """.trimIndent()
            val fullCmd = rootCommand("bash -c " + shellQuote(script), pass)
            val result = readCommand(session.exec(fullCmd), INSPECT_TIMEOUT_MS)
            session.close()

            if (result.exitStatus != 0) return@withContext null
            val lines = result.output.lineSequence().map { it.trim() }
            val webUser = lines.firstOrNull { it.startsWith("CSQTT_WEB_USER=") }
                ?.substringAfter("CSQTT_WEB_USER=")
                ?.takeIf { it.isNotBlank() }
            val webPass = lines.firstOrNull { it.startsWith("CSQTT_WEB_PASS=") }
                ?.substringAfter("CSQTT_WEB_PASS=")
                ?.takeIf { it.isNotBlank() }
            if (webUser == null || webPass == null) null else (webUser to webPass)
        } catch (e: Exception) {
            TunnelManager.addDeployWarningLog(
                "Чтение данных панели с VPS: ${friendlyDeployError(e.message)}",
            )
            null
        } finally {
            try { ssh?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun restartService(
        context: Context,
        host: String,
        user: String,
        pass: String,
        port: Int,
        isDocker: Boolean,
        privateKey: String = "",
        keyPassphrase: String = "",
        certificate: String = "",
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        var ssh: SSHClient? = null
        try {
            ssh = createSSHClient(context, host, user, pass, port, privateKey, keyPassphrase, certificate)
            val session = ssh.startSession()
            val cmd = if (isDocker) "docker restart csqtt" else "systemctl restart csqtt"
            val fullCmd = rootCommand(cmd, pass)
            val remoteCmd = session.exec(fullCmd)
            if (fullCmd.contains("sudo -S")) {
                runCatching {
                    remoteCmd.outputStream.write("$pass\n".toByteArray())
                    remoteCmd.outputStream.flush()
                }
            }
            remoteCmd.join(15, TimeUnit.SECONDS)
            val exit = remoteCmd.exitStatus ?: -1
            session.close()
            if (exit == 0) {
                TunnelManager.addDeploySuccessLog("Служба FOCSQ успешно перезапущена на VPS")
                Pair(true, "Служба сервера успешно перезапущена")
            } else {
                val err = "Код ошибки перезапуска: $exit"
                TunnelManager.addDeployErrorLog(err)
                Pair(false, err)
            }
        } catch (e: Exception) {
            val friendly = friendlyDeployError(e.message)
            TunnelManager.addDeployErrorLog("Перезапуск: $friendly")
            Pair(false, friendly)
        } finally {
            try { ssh?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Changes the web panel secret path on the server, restarts CSQTT and verifies that the panel
     * answers on the new path. On any failure the previous env file is restored and the service
     * restarted again, so the caller can persist the new path only after a confirmed success.
     */
    suspend fun changeWebSecretPath(
        context: Context,
        host: String,
        user: String,
        pass: String,
        port: Int,
        webPort: Int,
        newSecretPath: String,
        privateKey: String = "",
        keyPassphrase: String = "",
        certificate: String = "",
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val secretPath = newSecretPath.trim().trimStart('/')
        if (secretPath.isBlank()) {
            return@withContext false to "Пустой секретный путь"
        }
        if (webPort !in 1..65535) {
            return@withContext false to "Некорректный web-порт: $webPort"
        }
        var ssh: SSHClient? = null
        try {
            ssh = createSSHClient(context, host, user, pass, port, privateKey, keyPassphrase, certificate)
            val session = ssh.startSession()
            val script = """
                set -eu
                ENV_FILE=/etc/csqtt/csqtt.env
                BACKUP=/etc/csqtt/.csqtt-env-secret-path.bak
                NEW_PATH='${secretPath.replace("'", "'\"'\"'")}'
                WEB_PORT=$webPort
                START_TS=${'$'}SECONDS
                [ -f "${'$'}ENV_FILE" ] || { echo 'CSQTT_SECRET_PATH_ERROR|env_missing'; exit 2; }
                cp -a "${'$'}ENV_FILE" "${'$'}BACKUP"

                runtime=""
                if command -v systemctl >/dev/null 2>&1; then
                    load_state=$(systemctl show --value -p LoadState csqtt 2>/dev/null || true)
                    if [ -n "${'$'}load_state" ] && [ "${'$'}load_state" != "not-found" ] && \
                       systemctl is-active --quiet csqtt 2>/dev/null; then
                        runtime=systemd
                    fi
                fi
                if [ -z "${'$'}runtime" ] && command -v docker >/dev/null 2>&1; then
                    if docker inspect csqtt >/dev/null 2>&1; then
                        runtime=docker
                    elif docker inspect csqtt-previous >/dev/null 2>&1; then
                        # An interrupted previous attempt left the container renamed; restore it.
                        docker rm -f csqtt >/dev/null 2>&1 || true
                        docker rename csqtt-previous csqtt >/dev/null 2>&1 || true
                        docker start csqtt >/dev/null 2>&1 || true
                        docker inspect csqtt >/dev/null 2>&1 && runtime=docker || true
                    fi
                fi
                if [ -z "${'$'}runtime" ]; then
                    rm -f "${'$'}BACKUP"
                    echo 'CSQTT_SECRET_PATH_ERROR|service_not_running'
                    exit 3
                fi

                write_env_file() {
                    tmp=$(mktemp)
                    grep -v -E '^[[:space:]]*(export[[:space:]]+)?CSQTT_WEB_SECRET_PATH=' "${'$'}1" > "${'$'}tmp" || true
                    printf 'CSQTT_WEB_SECRET_PATH=%s\n' "${'$'}2" >> "${'$'}tmp"
                    chmod 600 "${'$'}tmp"
                    mv "${'$'}tmp" "${'$'}ENV_FILE"
                }

                # Docker bakes --env-file into the container at creation time, so a plain
                # restart keeps the old secret path. Recreate the container with the same
                # image, command and mounts. The running container is kept as
                # csqtt-previous until the new path is confirmed, so rollback is instant.
                docker_restore_previous() {
                    docker rm -f csqtt >/dev/null 2>&1 || true
                    docker rename csqtt-previous csqtt >/dev/null 2>&1 || true
                    docker start csqtt >/dev/null 2>&1 || true
                }

                docker_apply() {
                    command -v docker >/dev/null 2>&1 || return 1
                    docker inspect csqtt >/dev/null 2>&1 || return 1
                    image=$(docker inspect --format '{{.Config.Image}}' csqtt 2>/dev/null) || return 1
                    [ -n "${'$'}image" ] || return 1
                    cmd_args=()
                    while IFS= read -r line; do
                        [ -n "${'$'}line" ] && cmd_args+=("${'$'}line")
                    done < <(docker inspect --format '{{range .Config.Cmd}}{{.}}{{println}}{{end}}' csqtt 2>/dev/null)
                    [ "${'$'}{#cmd_args[@]}" -gt 0 ] || return 1
                    mount_args=()
                    while IFS='|' read -r mount_type mount_src mount_dst; do
                        [ -n "${'$'}mount_type" ] || continue
                        case "${'$'}mount_type" in
                            bind) mount_args+=(--mount "type=bind,src=${'$'}mount_src,dst=${'$'}mount_dst") ;;
                            volume) mount_args+=(-v "${'$'}mount_src:${'$'}mount_dst") ;;
                        esac
                    done < <(docker inspect --format '{{range .Mounts}}{{.Type}}|{{.Source}}|{{.Destination}}{{println}}{{end}}' csqtt 2>/dev/null)

                    docker rm -f csqtt-recreate >/dev/null 2>&1 || true
                    docker create \
                        --name csqtt-recreate \
                        --label com.csqtt.managed=true \
                        --label com.csqtt.component=server \
                        --restart unless-stopped \
                        --network host \
                        --cap-drop ALL \
                        --cap-add NET_ADMIN \
                        --cap-add NET_RAW \
                        --device /dev/net/tun:/dev/net/tun \
                        --security-opt seccomp=unconfined \
                        --stop-timeout 5 \
                        --ulimit nofile=65535:65535 \
                        --env-file "${'$'}ENV_FILE" \
                        --env CSQTT_SERVICE_MANAGER=docker \
                        ${'$'}{mount_args[@]+"${'$'}{mount_args[@]}"} \
                        "${'$'}image" ${'$'}{cmd_args[@]+"${'$'}{cmd_args[@]}"} >/dev/null 2>&1 || {
                            docker rm -f csqtt-recreate >/dev/null 2>&1 || true
                            return 1
                        }
                    docker rm -f csqtt-previous >/dev/null 2>&1 || true
                    docker rename csqtt csqtt-previous >/dev/null 2>&1 || {
                        docker rm -f csqtt-recreate >/dev/null 2>&1 || true
                        return 1
                    }
                    timeout 15 docker stop --time 5 csqtt-previous >/dev/null 2>&1 || true
                    docker rename csqtt-recreate csqtt >/dev/null 2>&1 || {
                        docker_restore_previous
                        return 1
                    }
                    if ! docker start csqtt >/dev/null 2>&1; then
                        docker_restore_previous
                        return 1
                    fi
                    return 0
                }

                apply_runtime() {
                    if [ "${'$'}runtime" = "systemd" ]; then
                        systemctl restart csqtt
                    else
                        docker_apply
                    fi
                }

                probe_panel() {
                    last_code="000"
                    deadline=$((SECONDS + 60))
                    while [ "${'$'}SECONDS" -lt "${'$'}deadline" ]; do
                        for target in "/${'$'}NEW_PATH" "/${'$'}NEW_PATH/"; do
                            for scheme in https http; do
                                code=$(curl -k -sS --noproxy '*' -o /dev/null -w '%{http_code}' \
                                    --connect-timeout 1 --max-time 1 \
                                    "${'$'}scheme://127.0.0.1:${'$'}WEB_PORT${'$'}target" 2>/dev/null || true)
                                case "${'$'}code" in
                                    ''|000) ;;
                                    *)
                                        last_code="${'$'}code"
                                        case "${'$'}code" in
                                            2??|3??|401|403) return 0 ;;
                                        esac
                                        ;;
                                esac
                            done
                        done
                        sleep 0.25
                    done
                    return 1
                }

                echo 'CSQTT_STAGE|restart'
                write_env_file "${'$'}ENV_FILE" "${'$'}NEW_PATH"
                if ! apply_runtime; then
                    cp -a "${'$'}BACKUP" "${'$'}ENV_FILE"
                    rm -f "${'$'}BACKUP"
                    if [ "${'$'}runtime" = "systemd" ]; then
                        systemctl restart csqtt >/dev/null 2>&1 || true
                    fi
                    echo "CSQTT_SECRET_PATH_ERROR|apply_failed|${'$'}runtime|$((SECONDS - START_TS))"
                    exit 4
                fi
                RESTART_SECONDS=$((SECONDS - START_TS))

                echo 'CSQTT_STAGE|probe'
                if probe_panel; then
                    if [ "${'$'}runtime" = "docker" ]; then
                        docker rm -f csqtt-previous >/dev/null 2>&1 || true
                    fi
                    rm -f "${'$'}BACKUP"
                    echo "CSQTT_SECRET_PATH_OK|${'$'}runtime|$((SECONDS - START_TS))|${'$'}RESTART_SECONDS"
                    exit 0
                fi

                echo 'CSQTT_STAGE|rollback'
                cp -a "${'$'}BACKUP" "${'$'}ENV_FILE"
                rm -f "${'$'}BACKUP"
                if [ "${'$'}runtime" = "docker" ]; then
                    docker_restore_previous
                else
                    systemctl restart csqtt >/dev/null 2>&1 || true
                fi
                echo "CSQTT_SECRET_PATH_ERROR|probe_failed|${'$'}runtime|${'$'}last_code|$((SECONDS - START_TS))|${'$'}RESTART_SECONDS"
                exit 1
            """.trimIndent()

            val fullCmd = rootCommand("bash -c " + shellQuote(script), pass)
            val remoteCmd = session.exec(fullCmd)
            if (fullCmd.contains("sudo -S")) {
                runCatching {
                    remoteCmd.outputStream.write("$pass\n".toByteArray())
                    remoteCmd.outputStream.flush()
                }
            }
            val result = readCommand(remoteCmd, COMMAND_TIMEOUT_MS) { line ->
                val stage = line.substringAfter("CSQTT_STAGE|", "").trim()
                if (stage.isNotEmpty()) {
                    ServerMaintenanceState.changeSecretPathStage.value = when (stage) {
                        "restart" -> "Перезапуск службы FOCSQ…"
                        "probe" -> "Проверка панели по новому пути…"
                        "rollback" -> "Откат на прежний путь…"
                        else -> ServerMaintenanceState.changeSecretPathStage.value
                    }
                }
            }
            session.close()

            if (result.output.contains("CSQTT_SECRET_PATH_OK")) {
                val parts = result.output.lineSequence()
                    .firstOrNull { it.contains("CSQTT_SECRET_PATH_OK|") }
                    ?.substringAfter("CSQTT_SECRET_PATH_OK|")
                    ?.trim()
                    ?.split('|')
                    .orEmpty()
                val runtimeLabel = secretPathRuntimeLabel(parts.getOrNull(0).orEmpty())
                val totalSeconds = parts.getOrNull(1)?.toIntOrNull()
                val restartSeconds = parts.getOrNull(2)?.toIntOrNull()
                val logTiming = listOfNotNull(
                    runtimeLabel.takeIf { it.isNotBlank() },
                    totalSeconds?.let { "$it с" },
                    restartSeconds?.let { "перезапуск $it с" },
                ).joinToString(", ")
                val toastTiming = listOfNotNull(
                    runtimeLabel.takeIf { it.isNotBlank() },
                    totalSeconds?.let { "$it с" },
                ).joinToString(", ")
                TunnelManager.addDeploySuccessLog(
                    "Секретный путь web-панели изменён на сервере" +
                        if (logTiming.isNotBlank()) " ($logTiming)" else "",
                )
                return@withContext true to (
                    "Путь панели обновлён" +
                        if (toastTiming.isNotBlank()) " ($toastTiming)" else ""
                    )
            }

            val parts = result.output.lineSequence()
                .firstOrNull { it.contains("CSQTT_SECRET_PATH_ERROR|") }
                ?.substringAfter("CSQTT_SECRET_PATH_ERROR|")
                ?.trim()
                ?.split('|')
                .orEmpty()
            val code = parts.getOrNull(0)
            val runtimeLabel = secretPathRuntimeLabel(parts.getOrNull(1).orEmpty())
            val lastHttpCode = if (code == "probe_failed") {
                parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            val totalSeconds = (
                if (code == "probe_failed") parts.getOrNull(3) else parts.getOrNull(2)
                )?.toIntOrNull()
            val message = when (code) {
                "env_missing" -> "На сервере не найдена конфигурация FOCSQ (/etc/csqtt/csqtt.env)"
                "service_not_running" -> "Служба FOCSQ не запущена — сначала выполните деплой"
                "apply_failed" -> buildString {
                    append("Не удалось перезапустить FOCSQ с новым путём")
                    val extra = listOfNotNull(
                        runtimeLabel.takeIf { it.isNotBlank() },
                        totalSeconds?.let { "$it с" },
                    )
                    if (extra.isNotEmpty()) append(" (${extra.joinToString(", ")})")
                    append("; прежний путь восстановлен")
                }
                "probe_failed" -> buildString {
                    append("Панель не ответила по новому пути")
                    val extra = listOfNotNull(
                        runtimeLabel.takeIf { it.isNotBlank() },
                        lastHttpCode?.let { "HTTP $it" },
                        totalSeconds?.let { "$it с" },
                    )
                    if (extra.isNotEmpty()) append(" (${extra.joinToString(", ")})")
                    append("; прежний путь восстановлен")
                }
                null -> if (result.exitStatus == -1) {
                    "Превышено время ожидания ответа от сервера"
                } else {
                    "Не удалось изменить путь панели (код ${result.exitStatus})"
                }
                else -> code
            }
            TunnelManager.addDeployErrorLog("Смена пути панели: $message")
            false to message
        } catch (e: Exception) {
            val friendly = friendlyDeployError(e.message)
            TunnelManager.addDeployErrorLog("Смена пути панели: $friendly")
            false to friendly
        } finally {
            try { ssh?.disconnect() } catch (_: Exception) {}
        }
    }
}
