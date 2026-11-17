package com.csqtt.client

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf

@Immutable
enum class LogLevel {
    OK,
    DBG,
    NET,
    PING,
    TURN,
    LOG,
    ERR,
}

@Immutable
data class LogEntry(
    val id: Long = 0L,
    val key: String,
    val message: String,
    val count: Int = 1,
    val priority: Int = 99,
    val isError: Boolean = false,
    val level: LogLevel? = null,
    val timestampMs: Long = 0L,
)

private val LOG_STICKERS = Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]")
private val EMPTY_LOG_TAG = Regex("\\[\\s*]")
private val LOG_TIMESTAMP_PREFIX = Regex("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s")

internal fun displayVkHash(hash: String): String = hash.trim().let { value ->
    if (value.length <= 10) value else value.take(10) + "…"
}

internal fun dnsProfileName(dns: String): String = when (dns.trim()) {
    "77.88.8.8,77.88.8.1" -> "Yandex DNS"
    "1.1.1.1,1.0.0.1" -> "Cloudflare DNS"
    "8.8.8.8,8.8.4.4" -> "Google DNS"
    "94.140.14.14,94.140.15.14" -> "AdGuard DNS"
    "111.88.96.54,111.88.96.55", "111.88.96.50,111.88.96.51" -> "Xbox DNS"
    "83.220.169.155,212.109.195.93" -> "Comms DNS"
    "193.233.112.67,193.233.112.88", "95.182.120.241,37.230.192.51" -> "Geohide DNS"
    "9.9.9.9,149.112.112.112" -> "Quad9 DNS"
    "208.67.222.222,208.67.220.220" -> "OpenDNS"
    "95.189.32.74,195.208.5.1" -> "Rostelecom DNS"
    "45.90.28.181,45.90.30.181" -> "NextDNS"
    "195.208.6.1,195.208.7.1" -> "BI.ZONE DNS"
    "195.208.4.1,195.208.5.1" -> "НСДИ DNS"
    else -> "Пользовательский DNS"
}

internal fun withoutLogStickers(message: String): String = message
    .replace(LOG_STICKERS) { symbol ->
        symbol.value.takeIf { it == "✓" || it == "✗" }.orEmpty()
    }
    .replace(EMPTY_LOG_TAG, "")
    .replace(Regex("[^\\S\r\n]+"), " ")
    .trim()

object TunnelManager {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var activeScope: CoroutineScope = scope

    @Volatile
    private var process: Process? = null
    @Volatile
    private var processGeneration = 0L
    @Volatile
    private var processIdentity: ProcessIdentity? = null
    @Volatile
    private var nativeProcessPid: Int? = null
    @Volatile
    private var readerJob: Job? = null
    @Volatile
    private var restartJob: Job? = null
    @Volatile
    private var panelRestartJob: Job? = null
    @Volatile
    private var workerRecoveryJob: Job? = null
    private val transportRestartPending = AtomicBoolean(false)
    private val panelRestartPending = AtomicBoolean(false)
    private val callRecoveryPending = AtomicBoolean(false)
    private val workerRecoveryPolicy = WorkerRecoveryPolicy()
    @Volatile
    private var crashRestartStreak = 0
    private const val WORKER_ZERO_CONFIRMATION_MS = 4_000L
    private const val PANEL_SERVER_RESTART_DELAY_MS = 4_000L
    private const val STARTUP_DIAGNOSTIC_MS = 30_000L
    private const val CRASH_RESTART_STABLE_MS = 30_000L
    private const val CRASH_RESTART_MAX_DELAY_MS = 60_000L
    private const val VPN_START_RETRY_MS = 10_000L
    private const val TUN_LOSS_REBUILD_WINDOW_MS = 60_000L

    private val startStopMutex = kotlinx.coroutines.sync.Mutex()
    private val lifecycleState = TunnelLifecycleState()
    private val lifecycleCommand = AtomicLong()

    private data class ProcessIdentity(
        val process: Process,
        val generation: Long,
        val ticket: TunnelLifecycleTicket,
        val shutdownGraceMs: Long,
        val startupProgress: TunnelStartupProgress = TunnelStartupProgress(),
    ) {
        val inputLock = Any()
        val readyWorkers = mutableSetOf<Int>()
        val dtlsWorkers = mutableSetOf<Int>()
        val unavailableManualHashes = mutableSetOf<String>()
    }

    private data class PendingLog(
        val identity: ProcessIdentity?,
        val key: String,
        var message: String,
        val priority: Int,
        val isError: Boolean,
        val level: LogLevel?,
        var count: Int,
        var timestampMs: Long,
        val dedupeKey: String,
        val epoch: Long,
    )

    private const val LOG_UI_FLUSH_MS = 120L
    internal const val MAX_LOG_ENTRIES = 100
    private const val MAX_PENDING_LOGS = 128
    private const val MAX_LOG_MESSAGE_LENGTH = 4_096
    // Stats text embeds the live traffic counter, so every emission differs. Merge such
    // entries by key alone and keep the newest text, otherwise the list floods with a
    // near-identical line every second.
    private const val STATS_LOG_KEY = "stats"
    private val pendingLogLock = Any()
    private val pendingLogs = ArrayDeque<PendingLog>()
    // Incremented on clear so a batch already taken for flushing cannot reappear afterwards.
    private val logEpoch = AtomicLong()
    private val pendingLogFlushScheduled = AtomicBoolean(false)
    private val logEntryIds = AtomicLong()

    @Volatile
    private var wrapAuthTimeoutCount = 0
    private var lastVpnStartAttemptMs = 0L
    @Volatile
    private var lastTunLossMs = 0L
    @Volatile
    var processStartedAtMs = 0L
    @Volatile
    private var currentParams: TunnelParams? = null
    @Volatile
    private var strongAppContext: Context? = null
    @Volatile
    private var lastContext: WeakReference<Context>? = null
    private fun activeContext(): Context? = strongAppContext ?: lastContext?.get()
    @Volatile
    private var activeSettingsStore: SettingsStore? = null
    @Volatile
    private var currentCaptchaSolveMethod = "auto"
    @Volatile
    private var lastAppliedDns: String? = null

    @Volatile
    var isLoggingEnabled = true
        set(value) {
            // Screens re-apply the stored flag when they open, so assigning an unchanged
            // value must be a no-op; otherwise just visiting Settings wipes the log list.
            val changed = field != value
            field = value
            if (changed && !value) clearLogs()
        }

    val running = MutableStateFlow(false)
    val starting = MutableStateFlow(false)
    val stopping = MutableStateFlow(false)
    val logs = mutableStateListOf<LogEntry>()
    val unreadErrorCount = MutableStateFlow(0)
    val config = MutableStateFlow<String?>(null)
    val stats = MutableStateFlow("Ожидание данных...")
    val activeWorkers = MutableStateFlow(0)
    val autoPausedForWifi = MutableStateFlow(false)
    val vpnReady = MutableStateFlow(false)
    val serverRoutingActive = MutableStateFlow(false)
    val activeServerRoutingRule = MutableStateFlow<ServerRoutingRule?>(null)
    val serverAssignedWorkers = MutableStateFlow<Int?>(null)
    val lastTunnelDiagnostic = MutableStateFlow("")
    val uptimeSeconds = MutableStateFlow<Long?>(null)
    private var uptimeJob: Job? = null

    private val pausedForNoNetwork = NetworkPauseGate()

    val cooldownActive = MutableStateFlow(false)
    private var cooldownJob: Job? = null

    private fun startUptimeTimer() {
        processStartedAtMs = SystemClock.elapsedRealtime()
        uptimeSeconds.value = 0L
        uptimeJob?.cancel()
        uptimeJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val start = processStartedAtMs
                val isProcessActive = running.value || process != null
                if (start > 0L && isProcessActive) {
                    uptimeSeconds.value = (SystemClock.elapsedRealtime() - start) / 1000L
                } else if (!isProcessActive && !starting.value) {
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun stopUptimeTimer() {
        uptimeJob?.cancel()
        uptimeJob = null
        val start = processStartedAtMs
        if (start > 0L) {
            uptimeSeconds.value = (SystemClock.elapsedRealtime() - start) / 1000L
            processStartedAtMs = 0L
        } else {
            uptimeSeconds.value = null
        }
    }

    fun getCurrentParams(): TunnelParams? = currentParams

    private fun processIdOf(activeProcess: Process): Int? {
        return runCatching {
            val field = activeProcess.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(activeProcess).takeIf { it > 0 }
        }.getOrNull() ?: Regex("pid=(\\d+)")
            .find(activeProcess.toString())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    init {
        scope.launch {
            running.collect { started ->
                if (started) starting.value = false
            }
        }
    }

    fun restartForNetworkSwap(context: Context) {
        requestTransportRestart(
            context.applicationContext,
            "network_swap",
            "[OK] Сеть доступна · переподключение",
        )
    }

    fun restartAfterVkRecovery(context: Context) {
        if (!running.value || activeWorkers.value != 0) return
        requestTransportRestart(
            context.applicationContext,
            "workers_zero_vk_ready",
            "[NET] Потоки: 0 · обновление TURN",
        )
    }

    fun onPhysicalNetworkAvailable(context: Context) {
        if (!running.value) return
        if (pausedForNoNetwork.resume()) {
            val identity = processIdentity ?: return
            activeScope.launch {
                writeProcessCommand(identity, "RESUME", "network_resume_command_error")
            }
        }
    }

    fun onVpnInterfaceReady(dns: String? = null) {
        vpnReady.value = true
        lastTunnelDiagnostic.value = ""
        activeScope.launch { activeSettingsStore?.clearTunnelDiagnostic() }
        if (!dns.isNullOrBlank() && dns != lastAppliedDns) {
            lastAppliedDns = dns
            updateLog(
                key = "vpn_dns_active_${dnsProfileName(dns)}",
                message = "[SERVER] DNS применён: ${dnsProfileName(dns)} ✓",
                priority = 32,
                level = LogLevel.OK,
            )
        }
    }

    fun onRoutingUpdated() {
        vpnReady.value = true
        lastTunnelDiagnostic.value = ""
        activeScope.launch { activeSettingsStore?.clearTunnelDiagnostic() }
        updateLog(
            key = "vpn_routing_updated",
            message = "[СЕТЬ] Маршрутизация приложений обновлена ✓",
            priority = 25,
            level = LogLevel.NET,
        )
    }

    fun onVpnInterfaceStopped() {
        vpnReady.value = false
    }

    private fun recordTunnelDiagnostic(code: String, message: String) {
        val diagnostic = "$code: ${message.trim()}".take(480)
        lastTunnelDiagnostic.value = diagnostic
        val context = activeContext() ?: return
        scope.launch {
            val store = activeSettingsStore ?: SettingsStore(context).also {
                activeSettingsStore = it
            }
            store.saveTunnelDiagnostic(code, message)
        }
    }

    internal fun onVpnRecoverableFailure(message: String, kind: VpnFailureKind) {
        if (!VpnRecoveryPolicy.keepsTunnelRequested(kind)) {
            onVpnTerminalFailure(message, kind)
            return
        }
        vpnReady.value = false
        val diagnostic = VpnRecoveryPolicy.message(kind, message)
        recordTunnelDiagnostic(kind.code, diagnostic)
        updateLog("${kind.code}_recover", "[VPN] $diagnostic · восстановление", 98, true)
        val context = activeContext() ?: return
        if (!running.value || !lifecycleState.isDesiredRunning()) return
        requestTransportRestart(
            context.applicationContext,
            kind.code,
            "[VPN] Восстановление интерфейса и транспорта",
        )
    }

    internal fun onVpnTerminalFailure(
        message: String,
        kind: VpnFailureKind = VpnFailureKind.TunEstablishFailed,
    ) {
        vpnReady.value = false
        val diagnostic = VpnRecoveryPolicy.message(kind, message)
        recordTunnelDiagnostic(kind.code, diagnostic)
        updateLog("${kind.code}_terminal", "✗ VPN TUN: $diagnostic", 99, true)
        scope.launch {
            startStopMutex.withLock {
                stopLocked(processIdentity)
            }
        }
    }

    internal fun suspendForNoNetwork(reason: PhysicalNetworkPauseReason) {
        if (!running.value || !pausedForNoNetwork.pause()) return
        val message = when (reason) {
            PhysicalNetworkPauseReason.AIRPLANE_MODE -> "[NET] Режим полёта · ожидание"
            PhysicalNetworkPauseReason.OFFLINE -> "[NET] Сеть отключена · ожидание"
        }
        updateLog("network_pause", message, 2, false, LogLevel.NET)
        val identity = processIdentity ?: return
        activeScope.launch {
            if (pausedForNoNetwork.isPaused()) {
                writeProcessCommand(identity, "PAUSE", "network_pause_command_error")
            }
        }
    }

    fun clearUnreadErrors() {
        unreadErrorCount.value = 0
    }

    private fun handleTunnelEvent(
        event: TunnelEventParser.Event,
        identity: ProcessIdentity,
    ): Boolean {
        if (!isCurrent(identity)) return true
        when (event) {
            is TunnelEventParser.Event.Process -> {
                nativeProcessPid = event.pid
            }
            is TunnelEventParser.Event.Stats -> {
                activeWorkers.value = event.active
                if (workerRecoveryPolicy.observe(event.active)) {
                    workerRecoveryJob?.cancel()
                    workerRecoveryJob = null
                }
                if (event.active > 0) {
                    wrapAuthTimeoutCount = 0
                    val currentConfig = config.value
                    if (!vpnReady.value && currentConfig != null && currentConfig.startsWith("TUNCONF:") &&
                        SystemClock.elapsedRealtime() - lastVpnStartAttemptMs >= VPN_START_RETRY_MS
                    ) {
                        ensureVpnStarted(currentConfig, identity, reason = "stats_no_vpn")
                    }
                }
                val totalMB = (event.bytesUp + event.bytesDown) / (1024.0 * 1024.0)
                val msg = "Активных: ${event.active} | Трафик: %.2f МБ".format(totalMB)
                stats.value = msg
                updateProcessLog(identity, STATS_LOG_KEY, "[СЕТЬ] $msg", 25, false, LogLevel.NET)
            }
            is TunnelEventParser.Event.ActiveZero -> {
                activeWorkers.value = 0
                scheduleWorkerZeroRecovery(identity)
            }
            is TunnelEventParser.Event.CallUnavailable -> handleUnavailableVkCall(event, identity)
            is TunnelEventParser.Event.NetworkSuspect -> verifyVkReachability(identity)
            is TunnelEventParser.Event.ServerRestart -> schedulePanelServerRestart(identity)
            is TunnelEventParser.Event.Ready -> {
                identity.startupProgress.streamReady()
                if (event.worker == 0 || identity.readyWorkers.add(event.worker)) {
                    updateProcessLog(identity, "ready", "[ВОРКЕР] Поток готов ✓", 20, false, LogLevel.OK)
                }
            }
            is TunnelEventParser.Event.Dtls -> {
                if (isCurrent(identity)) {
                    val suffix = event.message.trim()
                    val isError = suffix.contains("не состоялось") || suffix.contains("ошибка", ignoreCase = true)
                    val level = if (isError) LogLevel.ERR else LogLevel.OK
                    if (isError) {
                        val message = "[DTLS] Поток ${event.worker}: $suffix"
                        updateProcessLog(identity, "dtls_err_${event.worker}", message, 30, true, level)
                    } else {
                        val message = if (suffix.contains("завершено")) {
                            "[DTLS] Рукопожатие завершено, ключи SRTP установлены ✓"
                        } else if (suffix.isNotEmpty()) {
                            "[DTLS] $suffix"
                        } else {
                            "[DTLS] Рукопожатие выполняется"
                        }
                        updateProcessLog(identity, "dtls_status", message, 30, false, LogLevel.OK)
                    }
                }
            }
            is TunnelEventParser.Event.Config -> {
                val configStr = event.config.trim()
                if (configStr.isNotEmpty()) {
                    config.value = configStr
                    activeScope.launch(Dispatchers.Main) {
                        if (!isCurrent(identity)) return@launch
                        if (configStr.startsWith("TUNCONF:")) {
                            ensureVpnStarted(configStr, identity, reason = "tunconf")
                        } else {
                            updateProcessLog(identity, "vpn_config_err", "Получен неизвестный формат конфига", 99, true)
                        }
                    }
                }
            }
            is TunnelEventParser.Event.Routing -> {
                val routingJsonStr = event.routing.trim()
                if (routingJsonStr.isNotEmpty()) {
                    activeScope.launch(Dispatchers.Main) {
                        if (!isCurrent(identity)) return@launch
                        val rule = parseServerRoutingRuleJson(routingJsonStr)
                        if (rule == null) {
                            handleCriticalError("Сервер передал некорректное правило маршрутизации", identity)
                            return@launch
                        }
                        val activeConf = config.value
                        var clientIp = ""
                        var dns = ""
                        if (activeConf != null && activeConf.startsWith("TUNCONF:")) {
                            val parts = activeConf.substring("TUNCONF:".length).split(":")
                            clientIp = parts.getOrNull(0)?.trim().orEmpty()
                            dns = parts.getOrNull(1)?.trim().orEmpty()
                        }
                        applyServerRoutingRule(rule, clientIp, dns)
                    }
                }
            }
            is TunnelEventParser.Event.Workers -> {
                handleServerAssignedWorkers(event.workers, identity)
            }
            is TunnelEventParser.Event.Error -> {
                if (event.fatal) {
                    handleCriticalError(event.message, identity)
                } else {
                    updateProcessLog(identity, "event_error_${event.code}", event.message, 99, true)
                }
            }
            is TunnelEventParser.Event.CaptchaRequest -> {
                activeScope.launch {
                    if (isCurrent(identity)) {
                        handleCaptchaSolve(event.mode, event.redirectUri, event.sessionToken, identity)
                    }
                }
            }
            is TunnelEventParser.Event.Progress -> {
                if (event.kind == "credentials") {
                    identity.startupProgress.credentialReceived()
                }
            }
            is TunnelEventParser.Event.TunLost -> {
                if (isCurrent(identity)) {
                    val now = SystemClock.elapsedRealtime()
                    val escalate = now - lastTunLossMs < TUN_LOSS_REBUILD_WINDOW_MS
                    lastTunLossMs = now
                    updateProcessLog(
                        identity,
                        if (escalate) "tun_lost_rebuild" else "tun_lost_reuse",
                        if (escalate) {
                            "[TUN] Запрос переподключения FD (пересборка интерфейса)"
                        } else {
                            "[TUN] Повторная выдача действующего TUN FD без пересборки"
                        },
                        20,
                        false,
                        LogLevel.OK,
                    )
                    config.value?.let { conf ->
                        ensureVpnStarted(
                            conf,
                            identity,
                            forceRebuild = escalate,
                            reason = if (escalate) "tun_lost_rebuild" else "tun_lost_reuse",
                        )
                    }
                }
            }
            else -> return false
        }
        return true
    }

    private fun handleServerAssignedWorkers(assignedWorkers: Int, identity: ProcessIdentity) {
        if (!isCurrent(identity)) return
        serverAssignedWorkers.value = if (assignedWorkers in TunnelEventParser.MIN_SERVER_WORKERS..CsqttConstants.Tunnel.MAX_WORKERS) assignedWorkers else null
    }

    private fun verifyVkReachability(identity: ProcessIdentity) {
        if (!isCurrent(identity) || !running.value) return
        val context = activeContext() ?: return
        runCatching {
            context.applicationContext.startService(
                Intent(context.applicationContext, TunnelService::class.java).apply {
                    action = CsqttConstants.General.ACTION_VERIFY_VK_REACHABILITY
                },
            )
        }
    }

    private fun recoverUnavailableVkCall(hash: String) {
        if (!running.value || !callRecoveryPending.compareAndSet(false, true)) return
        val context = activeContext() ?: run {
            callRecoveryPending.set(false)
            return
        }
        runCatching {
            context.applicationContext.startService(
                Intent(context.applicationContext, TunnelService::class.java).apply {
                    action = CsqttConstants.General.ACTION_RECOVER_VK_CALL
                    putExtra(CsqttConstants.General.EXTRA_UNAVAILABLE_VK_HASH, hash)
                },
            )
        }.onFailure {
            callRecoveryPending.set(false)
        }
    }

    private fun handleUnavailableVkCall(
        event: TunnelEventParser.Event.CallUnavailable,
        identity: ProcessIdentity,
    ) {
        val params = currentParams ?: return
        val displayHash = displayVkHash(event.hash)
        val reason = if (event.code == TunnelEventParser.CALL_NOT_FOUND_CODE) "звонок не найден" else "код ${event.code}"
        if (shouldReplaceUnavailableVkHash(params.vkHashMode)) {
            updateLog(
                key = "vk_hash_auto_replace_$displayHash",
                message = "[VK] Хеш \"$displayHash\" устарел · ошибка ${event.code} — $reason · создаём новый ✓",
                priority = 36,
                level = LogLevel.OK,
            )
            recoverUnavailableVkCall(event.hash)
            return
        }

        val isNewUnavailableHash = synchronized(identity.unavailableManualHashes) {
            identity.unavailableManualHashes.add(event.hash)
        }
        if (!isNewUnavailableHash) return
        updateProcessLog(
            identity = identity,
            key = "vk_hash_manual_invalid_$displayHash",
            message = "[VK] Хеш \"$displayHash\" устарел · ошибка ${event.code} — $reason · инвалидируем и используем активные хеши ✓",
            priority = 36,
            level = LogLevel.OK,
        )
        activeScope.launch {
            val remaining = params.vkHashes
                .split(Regex("[,\\s\\n]+"))
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .count { hash ->
                    synchronized(identity.unavailableManualHashes) {
                        hash !in identity.unavailableManualHashes
                    }
                }
            if (remaining == 0 && isCurrent(identity)) {
                stop()
            }
        }
    }

    internal fun completeVkCallRecovery() {
        callRecoveryPending.set(false)
    }

    private fun ensureVpnStarted(
        configStr: String,
        identity: ProcessIdentity,
        forceRebuild: Boolean = false,
        reason: String = "ensure",
    ) {
        ensureVpnStartedMeasured(configStr, identity, forceRebuild, reason)
    }

    private fun ensureVpnStartedMeasured(
        configStr: String,
        identity: ProcessIdentity,
        forceRebuild: Boolean,
        reason: String,
    ) {
        if (!configStr.startsWith("TUNCONF:")) return
        val parts = configStr.removePrefix("TUNCONF:").split(":", limit = 5)
        val clientIp = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val dns = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return

        val b64Routing = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("pad=") && !it.startsWith("workers=") }
        var newServerRule: ServerRoutingRule? = null
        if (b64Routing != null) {
            val routingJsonStr = try {
                val decoded = android.util.Base64.decode(b64Routing, android.util.Base64.DEFAULT)
                if (decoded.size >= 2 && decoded[0] == 0x1f.toByte() && decoded[1] == 0x8b.toByte()) {
                    java.util.zip.GZIPInputStream(decoded.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    String(decoded, Charsets.UTF_8)
                }
            } catch (_: Exception) {
                null
            }
            newServerRule = routingJsonStr?.let(::parseServerRoutingRuleJson)
            if (newServerRule == null) {
                handleCriticalError("Сервер передал некорректное правило маршрутизации", identity)
                return
            }
        }
        if (newServerRule != null || activeServerRoutingRule.value != null) {
            applyServerRoutingRule(newServerRule, clientIp, dns)
        }

        activeContext()?.let { ctx ->
            lastVpnStartAttemptMs = SystemClock.elapsedRealtime()
            val vpnIntent = Intent(ctx, TunVpnService::class.java).apply {
                action = "START"
                putExtra("client_ip", clientIp)
                putExtra("dns", dns)
                putExtra("force_rebuild", forceRebuild)
                putExtra("reason", reason)
                putExtra("mtu", CsqttConstants.Vpn.DEFAULT_MTU)
            }
            try {
                startTunVpnService(ctx, vpnIntent)
            } catch (e: Exception) {
                updateProcessLog(identity, "vpn_start_error", "Ошибка запуска VPN: ${e.readableMessage()}", 99, true)
            }
        }
    }

    private fun startTunVpnService(context: Context, intent: Intent) {
        context.startService(intent)
    }

    private fun scheduleWorkerZeroRecovery(identity: ProcessIdentity) {
        val target = workerRecoveryPolicy.armAtZero() ?: return
        if (workerRecoveryJob?.isActive == true) return
        workerRecoveryJob = activeScope.launch {
            try {
                delay(WORKER_ZERO_CONFIRMATION_MS)
                if (!isCurrent(identity) || !running.value) return@launch
                if (!workerRecoveryPolicy.shouldRecover(activeWorkers.value, target)) return@launch
                val context = activeContext() ?: return@launch
                requestTransportRestart(
                    context.applicationContext,
                    "workers_zero_refresh",
                    "[NET] Потоки: 0 · обновление TURN",
                )
            } finally {
                if (workerRecoveryJob === coroutineContext[Job]) {
                    workerRecoveryJob = null
                }
            }
        }
    }

    private fun schedulePanelServerRestart(identity: ProcessIdentity) {
        if (!isCurrent(identity) || !running.value) return
        if (!panelRestartPending.compareAndSet(false, true)) return
        resetWorkerRecoveryState()
        panelRestartJob?.cancel()
        panelRestartJob = activeScope.launch {
            try {
                delay(PANEL_SERVER_RESTART_DELAY_MS)
                if (!isCurrent(identity) || !running.value) return@launch
                val context = activeContext() ?: return@launch
                requestTransportRestart(
                    context.applicationContext,
                    "panel_server_restart",
                    "[SERVER] Перезагрузка · переподключение",
                )
            } finally {
                panelRestartPending.set(false)
                if (panelRestartJob === coroutineContext[Job]) panelRestartJob = null
            }
        }
    }

    private fun requestTransportRestart(
        context: Context,
        logKey: String,
        message: String,
    ) {
        if (logKey == "workers_zero_refresh") {
            runCatching {
                context.startService(
                    Intent(context, TunnelService::class.java).apply {
                        action = CsqttConstants.General.ACTION_RESTART_WHEN_VK_REACHABLE
                    },
                )
            }.onFailure {
                updateLog(
                    "vk_recovery_service_error",
                    "Не удалось запустить проверку VK: ${it.readableMessage()}",
                    99,
                    true,
                )
            }
            return
        }
        if (!transportRestartPending.compareAndSet(false, true)) return
        val command = lifecycleCommand.incrementAndGet()
        val appContext = context.applicationContext
        activeScope.launch {
            try {
                val params = currentParams ?: return@launch
                if (!running.value) return@launch
                val newParams = runCatching { params.withNewSession(appContext) }
                    .getOrElse {
                        if (logKey == "network_swap") pausedForNoNetwork.restore()
                        if (it is kotlin.coroutines.cancellation.CancellationException) throw it
                        updateLog(
                            "${logKey}_epoch_error",
                            "Ошибка обновления эпохи: ${it.readableMessage()}",
                            99,
                            true,
                        )
                        return@launch
                    }
                startStopMutex.withLock {
                    if (
                        lifecycleCommand.get() != command ||
                        !running.value ||
                        currentParams != params
                    ) {
                        return@withLock
                    }
                    val epoch = lifecycleState.requestRestart() ?: return@withLock
                    restartJob?.cancel()
                    restartJob = null
                    vpnReady.value = false
                    updateLog(logKey, message, 2, false)
                    terminateProcessLocked(processIdentity)
                    currentParams = newParams
                    startProcessLocked(appContext, newParams, epoch)
                }
            } finally {
                transportRestartPending.set(false)
            }
        }
    }

    private fun resetWorkerRecoveryState() {
        workerRecoveryJob?.cancel()
        workerRecoveryJob = null
        workerRecoveryPolicy.reset()
    }

    private var observersInitialized = false

    fun initObservers(context: Context) {
        if (observersInitialized) return
        observersInitialized = true
        val appContext = context.applicationContext
        scope.launch {
            running.collect { running ->
                try {
                    VpnWidgetProvider.updateAllWidgets(appContext)
                    android.service.quicksettings.TileService.requestListeningState(
                        appContext,
                        android.content.ComponentName(appContext, QuickToggleTileService::class.java)
                    )
                } catch (e: Exception) {
                }
            }
        }
    }

    private val deploySessionCounter = AtomicLong()
    private val deployLineCounter = AtomicLong()
    @Volatile
    private var deploySessionId = 0L

    fun beginDeployLog(message: String) {
        deploySessionId = deploySessionCounter.incrementAndGet()
        deployLineCounter.set(0L)
        addDeployInfoLog(message)
    }

    fun addDeployInfoLog(message: String) = addDeployLog(message, LogLevel.LOG)

    fun addDeploySuccessLog(message: String) {
        addDeployLog(message, LogLevel.OK)
    }

    fun addDeployWarningLog(message: String) {
        addDeployLog("Предупреждение: $message", LogLevel.LOG)
    }

    fun addDeployErrorLog(message: String) {
        addDeployLog(message, LogLevel.ERR)
    }

    private fun addDeployLog(message: String, level: LogLevel) {
        val clean = message.trim().take(500)
        if (clean.isEmpty()) return
        if (deploySessionId == 0L) {
            deploySessionId = deploySessionCounter.incrementAndGet()
            deployLineCounter.set(0L)
        }
        val sequence = deployLineCounter.incrementAndGet().toString().padStart(5, '0')
        updateLog(
            key = "deploy_${deploySessionId}_$sequence",
            message = "[ДЕПЛОЙ] $clean",
            priority = 7,
            isError = level == LogLevel.ERR,
            level = level,
        )
    }

    internal fun updateLog(
        key: String,
        message: String,
        priority: Int,
        isError: Boolean = false,
        level: LogLevel? = null,
    ) {
        updateLogForProcess(null, key, message, priority, isError, level)
    }

    private fun updateProcessLog(
        identity: ProcessIdentity,
        key: String,
        message: String,
        priority: Int,
        isError: Boolean = false,
        level: LogLevel? = null,
    ) {
        updateLogForProcess(identity, key, message, priority, isError, level)
    }

    private fun updateLogForProcess(
        identity: ProcessIdentity?,
        key: String,
        message: String,
        priority: Int,
        isError: Boolean,
        level: LogLevel?,
    ) {
        if (identity != null && !isCurrent(identity)) return
        if (!isLoggingEnabled && !TunnelLogPolicy.isAllowedWhenInactiveLogging(key, isError, level)) return
        val cleanMessage = withoutLogStickers(message).take(MAX_LOG_MESSAGE_LENGTH)
        if (cleanMessage.isEmpty()) return
        val pendingKey = "${identity?.generation ?: 0L}:$key:$priority:$isError:${level?.name.orEmpty()}"
        val now = System.currentTimeMillis()
        synchronized(pendingLogLock) {
            // Merge with a matching entry anywhere in the buffer, not just the most recent
            // one: interleaved repeats (e.g. two workers handshaking at once) are common.
            val matchIndex = pendingLogs.indexOfLast { candidate ->
                candidate.dedupeKey == pendingKey &&
                    (candidate.message == cleanMessage || key == STATS_LOG_KEY)
            }
            if (matchIndex >= 0) {
                val matched = pendingLogs.removeAt(matchIndex)
                matched.message = cleanMessage
                matched.count += 1
                matched.timestampMs = now
                pendingLogs.addLast(matched)
            } else {
                if (pendingLogs.size >= MAX_PENDING_LOGS) {
                    pendingLogs.removeFirst()
                }
                pendingLogs.addLast(
                    PendingLog(
                        identity = identity,
                        key = key,
                        message = cleanMessage,
                        priority = priority,
                        isError = isError,
                        level = level,
                        count = 1,
                        timestampMs = now,
                        dedupeKey = pendingKey,
                        epoch = logEpoch.get(),
                    )
                )
            }
            scheduleLogFlushLocked()
        }
    }

    private fun scheduleLogFlushLocked() {
        if (!pendingLogFlushScheduled.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Main) {
            delay(LOG_UI_FLUSH_MS)
            flushPendingLogs()
        }
    }

    private fun flushPendingLogs() {
        val batch = synchronized(pendingLogLock) {
            val values = pendingLogs.toList()
            pendingLogs.clear()
            pendingLogFlushScheduled.set(false)
            values
        }
        batch.forEach(::applyPendingLog)
    }

    private fun applyPendingLog(pending: PendingLog) {
        val identity = pending.identity
        if (identity != null && !isCurrent(identity)) return
        if (pending.epoch != logEpoch.get()) return

        // Collapse repeats anywhere in the visible list and move the merged entry to the end
        // so the newest occurrence (and its count) stays in view.
        val matchIndex = logs.indexOfLast { entry ->
            entry.key == pending.key &&
                entry.priority == pending.priority &&
                entry.isError == pending.isError &&
                entry.level == pending.level &&
                (entry.message == pending.message || pending.key == STATS_LOG_KEY)
        }
        if (matchIndex >= 0) {
            val matched = logs.removeAt(matchIndex)
            logs.add(
                matched.copy(
                    count = matched.count + pending.count,
                    message = pending.message,
                    timestampMs = pending.timestampMs,
                )
            )
        } else {
            if (pending.isError) {
                unreadErrorCount.value++
            }
            logs.add(
                LogEntry(
                    id = logEntryIds.incrementAndGet(),
                    key = pending.key,
                    message = pending.message,
                    count = pending.count,
                    priority = pending.priority,
                    isError = pending.isError,
                    level = pending.level,
                    timestampMs = pending.timestampMs,
                )
            )
        }

        while (logs.size > MAX_LOG_ENTRIES) {
            logs.removeAt(0)
        }
    }

    private fun isCurrent(identity: ProcessIdentity): Boolean =
        processIdentity === identity &&
            process === identity.process &&
            processGeneration == identity.generation &&
            lifecycleState.accepts(identity.ticket)

    fun start(context: Context, params: TunnelParams, isSwitching: Boolean = false) {
        val command = lifecycleCommand.incrementAndGet()
        autoPausedForWifi.value = false
        stopping.value = false

        if (!isSwitching) pausedForNoNetwork.reset()

        activeScope.launch {
            startStopMutex.withLock {
                if (lifecycleCommand.get() != command) return@withLock
                if (!isSwitching && lifecycleState.isDesiredRunning()) {
                    starting.value = false
                    return@withLock
                }
                val appContext = context.applicationContext
                val epoch = if (isSwitching) {
                    lifecycleState.requestRestart() ?: return@withLock
                } else {
                    clearLogs()
                    config.value = null
                    stats.value = "Ожидание данных..."
                    wrapAuthTimeoutCount = 0
                    crashRestartStreak = 0
                    processStartedAtMs = 0L
                    currentParams = params
                    strongAppContext = appContext.applicationContext
                    lastContext = WeakReference(appContext)
                    activeSettingsStore = SettingsStore(appContext)
                    lastTunnelDiagnostic.value = ""
                    activeScope.launch { activeSettingsStore?.clearTunnelDiagnostic() }
                    currentCaptchaSolveMethod = params.captchaSolveMethod
                    lifecycleState.requestStart()
                }
                restartJob?.cancel()
                restartJob = null
                if (isSwitching) terminateProcessLocked(processIdentity)
                if (isSwitching) currentParams = params
                startProcessLocked(appContext, params, epoch)
            }
        }
    }

    private fun failStartLocked(key: String, message: String) {
        updateLog(key, message, 99, true)
        lifecycleState.requestStop()
        stopUptimeTimer()
        running.value = false
        stopping.value = false
        starting.value = false
        activeWorkers.value = 0
    }

    private fun startProcessLocked(context: Context, params: TunnelParams, epoch: Long) {
        if (!lifecycleState.canStart(epoch)) {
            starting.value = false
            return
        }
        val existingProcess = process
        if (existingProcess != null) {
            if (existingProcess.isAlive) {
                terminateExactProcess(existingProcess, 1_000L, false)
            }
            process = null
            processIdentity = null
            nativeProcessPid = null
        }
        val hashList = params.vkHashes
            .split(Regex("[,\\s\\n]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(CsqttConstants.Tunnel.MAX_VK_HASHES)

        val jsHashMode = params.vkHashMode == CsqttConstants.VkAutoHash.MODE_AUTO_JS
        val jsAuthMode = params.vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
        val effectiveAuthMode = if (jsAuthMode && !jsHashMode) {
            CsqttConstants.VkAuth.MODE_CALLS
        } else {
            params.vkAuthMode
        }
        if (hashList.isEmpty() && !jsHashMode) {
            failStartLocked("hash_error", "Ошибка: Хеш не указан")
            return
        }
        if (jsHashMode && params.vkAccessToken.isBlank()) {
            failStartLocked("vk_token_error", "Ошибка: VK access token не указан")
            return
        }

        val binaryPath = context.applicationInfo.nativeLibraryDir + "/${CsqttConstants.Tunnel.BINARY_NAME}"
        if (!File(binaryPath).exists()) {
            failStartLocked("binary_error", "Ошибка: Бинарный файл не найден")
            return
        }

        val totalWorkers = WorkerCountPolicy.normalizeForHashValues(
            params.workersPerHash,
            hashList,
            params.allowHashRedistribution || jsHashMode,
        )
        val cmd = mutableListOf(
            binaryPath,
            "-peer", params.peer,
            "-n", totalWorkers.toString(),
            "-listen", "${CsqttConstants.Network.LOCAL_LISTEN_HOST}:${params.port}",
            "-tun-uds", "csqtt_tun_uds",
        )
        if (hashList.isNotEmpty()) {
            cmd.add("-vk")
            cmd.add(hashList.joinToString(","))
        }
        cmd.add("-vk-hash-mode")
        cmd.add(params.vkHashMode)
        if (params.fingerprint.isNotEmpty()) {
            cmd.add("-fingerprint")
            cmd.add(params.fingerprint)
        }
        if (params.clientIds.isNotEmpty()) {
            cmd.add("-client-ids")
            cmd.add(params.clientIds)
        }
        cmd.add("-obfs")
        cmd.add(params.obfsMode)
        cmd.add("-turn-transport")
        cmd.add(params.turnTransport)
        cmd.add("-srtp-mode")
        cmd.add(params.srtpMode)
        if (params.customTurnIps.isNotBlank()) {
            cmd.add("-custom-turn-ips")
            cmd.add(params.customTurnIps)
        }
        cmd.add("-vk-auth-mode")
        cmd.add(effectiveAuthMode)
        if (params.generationId > 0) {
            cmd.add("-gen")
            cmd.add(params.generationId.toString())
        }
        if (params.sessionSalt.isNotEmpty()) {
            cmd.add("-salt")
            cmd.add(params.sessionSalt)
        }
        cmd.add("-captcha-mode")
        cmd.add(params.captchaMode)
        val isDomainRoutingActive = params.domainRoutingEnabled || params.excludeRuZones || params.templateDomains || params.directDomains.isNotBlank()
        if (isDomainRoutingActive) {
            cmd.add("--domain-routing")
            if (params.domainIsWhitelist) {
                cmd.add("--domain-whitelist")
            }
            if (params.excludeRuZones && !params.domainIsWhitelist) {
                cmd.add("--exclude-ru-zones")
            }
            if (params.templateDomains && !params.domainIsWhitelist) {
                cmd.add("--template-domains")
                val templateFile = java.io.File(context.filesDir, "domains.txt")
                if (!templateFile.exists() || templateFile.length() == 0L) {
                    runCatching {
                        context.assets.open("domains.txt").use { input ->
                            templateFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                if (templateFile.exists() && templateFile.length() > 0L) {
                    cmd.add("--domains-file")
                    cmd.add(templateFile.absolutePath)
                }
            }
            if (params.directDomains.isNotBlank()) {
                val userFile = java.io.File(context.filesDir, "user.txt")
                runCatching {
                    val content = params.directDomains.split(',').filter { it.isNotBlank() }.joinToString("\n")
                    userFile.writeText(content)
                }
                if (userFile.exists() && userFile.length() > 0L) {
                    cmd.add("--domains-file")
                    cmd.add(userFile.absolutePath)
                }
            }
        }
        if (params.ipRoutingEnabled || params.directIps.isNotBlank()) {
            cmd.add("--ip-routing")
            if (params.ipIsWhitelist) {
                cmd.add("--ip-whitelist")
            }
            if (params.directIps.isNotBlank()) {
                cmd.add("--direct-ips")
                cmd.add(params.directIps)
            }
        }

        val pb = ProcessBuilder(cmd)
        pb.directory(context.filesDir)
        pb.redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        pb.environment()[CsqttConstants.Tunnel.PROCESS_ENV_EVENTS] = "1"
        pb.environment()["RAYON_NUM_THREADS"] = "2"
        if (params.connectionPassword.isNotEmpty()) {
            pb.environment()["CSQTT_PASSWORD"] = params.connectionPassword
        }

        val ticket = lifecycleState.reserveProcess(epoch, process == null)
        if (ticket == null) {
            starting.value = false
            return
        }
        val startedProcess = try {
            pb.start()
        } catch (e: Exception) {
            lifecycleState.releaseReservation(ticket)
            failStartLocked("critical_start_error", "Критическая ошибка запуска: ${e.readableMessage()}")
            return
        }
        if (jsHashMode) {
            val bootstrap = JSONObject()
                .put("token", params.vkAccessToken)
                .toString()
            val encoded = Base64.encodeToString(
                bootstrap.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            try {
                startedProcess.outputStream.write(
                    "VK_JS_BOOTSTRAP:$encoded\n".toByteArray(Charsets.UTF_8),
                )
                startedProcess.outputStream.flush()
            } catch (e: Exception) {
                startedProcess.destroy()
                lifecycleState.releaseReservation(ticket)
                failStartLocked(
                    "vk_js_bootstrap_error",
                    "Ошибка передачи данных Auto JS: ${e.readableMessage()}",
                )
                return
            }
        }
        processGeneration = if (processGeneration == Long.MAX_VALUE) 1L else processGeneration + 1L
        val identity = ProcessIdentity(
            startedProcess,
            processGeneration,
            ticket,
            3_500L,
        )
        process = startedProcess
        callRecoveryPending.set(false)
        nativeProcessPid = processIdOf(startedProcess)
        processIdentity = identity
        running.value = true
        startUptimeTimer()
        wrapAuthTimeoutCount = 0
        activeWorkers.value = 0
        resetWorkerRecoveryState()
        startLogReader(identity)
        scheduleStartupDiagnostic(identity)
        config.value
            ?.trim()
            ?.takeIf { it.startsWith("TUNCONF:") }
            ?.let { retainedConfig ->
                activeScope.launch {
                    delay(250L)
                    if (isCurrent(identity)) ensureVpnStarted(retainedConfig, identity, reason = "startup")
                }
            }
    }

    private fun scheduleStartupDiagnostic(identity: ProcessIdentity) {
        activeScope.launch {
            delay(STARTUP_DIAGNOSTIC_MS)
            if (!isCurrent(identity) || activeWorkers.value > 0) return@launch
            when (identity.startupProgress.stage()) {
                TunnelStartupStage.WAITING_FOR_CREDENTIALS -> updateProcessLog(
                    identity,
                    "startup_waiting_credentials",
                    "[ДИАГНОСТИКА] Rust-клиент запущен, но VK не выдал TURN-креды: проверьте сеть, токен и капчу",
                    98,
                    true,
                )
                TunnelStartupStage.WAITING_FOR_TURN_OR_PEER -> updateProcessLog(
                    identity,
                    "startup_waiting_turn",
                    "[ДИАГНОСТИКА] TURN-креды получены, но ни один allocation/handshake с сервером не завершился",
                    98,
                    true,
                )
                TunnelStartupStage.READY -> Unit
            }
        }
    }

    private fun startLogReader(identity: ProcessIdentity) {
        val targetProcess = identity.process
        readerJob = activeScope.launch {
            val reader = targetProcess.inputStream.bufferedReader()
            var lastDiagnostic: String? = null

            try {
                reader.forEachLine { line ->
                    if (!isCurrent(identity)) return@forEachLine

                    val stripped =
                        if (line.length > 19 && line[0].isDigit() && line[4] == '/') {
                            line.replace(LOG_TIMESTAMP_PREFIX, "")
                        } else {
                            line
                        }
                    var lineTrim = stripped.trim()
                    val eventPrefix = CsqttConstants.General.CSQTT_EVENT_PREFIX
                    val eventIdx = lineTrim.indexOf(eventPrefix)
                    if (eventIdx >= 0) {
                        val eventPart = lineTrim.substring(eventIdx)
                        lineTrim = lineTrim.substring(0, eventIdx).trim()
                        val event = TunnelEventParser.parse(eventPart)
                        if (event != null) {
                            handleTunnelEvent(event, identity)
                        }
                        if (lineTrim.isEmpty()) {
                            return@forEachLine
                        }
                    } else {
                        val event = TunnelEventParser.parse(lineTrim)
                        if (event != null && handleTunnelEvent(event, identity)) {
                            return@forEachLine
                        }
                    }

                    val isCritical = lineTrim.startsWith("CAPTCHA_SOLVE|") ||
                        lineTrim.contains("FATAL_") ||
                        lineTrim.contains("[ФАТАЛ]", true) ||
                        lineTrim.contains("[PANIC]", true) ||
                        lineTrim.contains("AUTH_RETRY") ||
                        lineTrim.contains("WRAP_AUTH_TIMEOUT", true) ||
                        lineTrim.contains("[WRAP]") ||
                        lineTrim.startsWith("[ВОРКЕР") ||
                        lineTrim.startsWith("[КРЕД") ||
                        lineTrim.startsWith("[СЕТЬ]") ||
                        lineTrim.startsWith("[ПОТОКИ]") ||
                        lineTrim.startsWith("[VK JS]") ||
                        lineTrim.contains("DENIED:", true) ||
                        lineTrim.contains("Ошибка", true) ||
                        lineTrim.contains("error", true) ||
                        lineTrim.contains("FAIL", true)

                    if (lineTrim.contains("[КЛИЕНТ] Все воркеры завершены", true) || isCritical) {
                        lastDiagnostic = lineTrim.take(240)
                    }

                    if (!isLoggingEnabled && !isCritical) {
                        return@forEachLine
                    }

                    if (TunnelLogPolicy.isInternalRecovery(lineTrim)) {
                        return@forEachLine
                    }

                    if (lineTrim.contains("WRAP_AUTH_TIMEOUT", true)) {
                        if (activeWorkers.value > 0) {
                            wrapAuthTimeoutCount = 0
                            updateProcessLog(
                                identity,
                                "wrap_timeout_recovered",
                                "[WRAP] Один поток не прошёл handshake, активных=${activeWorkers.value}; повторяем",
                                50,
                                true
                            )
                        } else {
                            wrapAuthTimeoutCount++
                            updateProcessLog(
                                identity,
                                "wrap_timeout_wait",
                                "[WRAP] Handshake не подтвердился, проверяем пароль/сеть ($wrapAuthTimeoutCount)",
                                50,
                                true
                            )
                        }
                        return@forEachLine
                    }

                    if (lineTrim.contains("FATAL_AUTH") || lineTrim.contains("AUTH_RETRY")) {
                        val reason = when {
                            lineTrim.contains("неверный пароль") -> "Неверный пароль подключения"
                            lineTrim.contains("истёк") -> "Срок действия пароля истёк"
                            lineTrim.contains("деактивирован") -> "Аккаунт деактивирован"
                            else -> "Ошибка авторизации"
                        }
                        handleCriticalError("\uD83D\uDD12 $reason. Воркеры остановлены.", identity)
                        return@forEachLine
                    }

                    if (lineTrim.contains("FATAL_PROTOCOL")) {
                        handleCriticalError(
                            "🔁 Клиент и сервер используют разные версии протокола. Выполните деплой из этой версии приложения. Воркеры остановлены.",
                            identity,
                        )
                        return@forEachLine
                    }

                    if (lineTrim.startsWith("CAPTCHA_SOLVE|")) {
                        val payload = lineTrim.substringAfter("CAPTCHA_SOLVE|")
                        val parts = payload.split("|", limit = 3)
                        when (parts.size) {
                            3 -> {
                                val requestMode = parts[0]
                                val redirectUri = parts[1]
                                val sessionToken = parts[2]
                                activeScope.launch {
                                    if (isCurrent(identity)) {
                                        handleCaptchaSolve(requestMode, redirectUri, sessionToken, identity)
                                    }
                                }
                            }
                            2 -> {
                                val redirectUri = parts[0]
                                val sessionToken = parts[1]
                                activeScope.launch {
                                    if (isCurrent(identity)) {
                                        handleCaptchaSolve("selected", redirectUri, sessionToken, identity)
                                    }
                                }
                            }
                            else -> {
                                writeCaptchaResult("error:invalid CAPTCHA_SOLVE format", identity)
                            }
                        }
                        return@forEachLine
                    }

                    val isError = lineTrim.contains("Ошибка", true) || lineTrim.contains("error", true) || lineTrim.contains("FAIL", true) || lineTrim.contains("timeout", true) || lineTrim.contains("refused", true) || lineTrim.contains("FATAL_", true)

                    when {
                        lineTrim.contains("[КАПЧА] AUTO:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] AUTO:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()

                            val isErr = text.contains("ошибка", true) ||
                                text.contains("timeout", true) ||
                                text.contains("не решил", true)
                            val stableKey = when {
                                text.contains("старт") -> "captcha_auto_1"
                                (text.contains("Go v2") || text.contains("Rust v2")) && text.contains("2 попыт") -> "captcha_auto_2"
                                text.contains("WBV Auto попытка") -> "captcha_auto_3"
                                text.contains("финальная") -> "captcha_auto_4"
                                text.contains("ручной WebView") -> "captcha_auto_5"
                                text.contains("решил") || text.contains("решила") -> "captcha_auto_done"
                                else -> "captcha_auto_${text.take(18).hashCode()}"
                            }
                            updateProcessLog(identity, stableKey, "[КАПЧА AUTO] $text", 60, isErr)
                        }

                        lineTrim.contains("[КАПЧА] RJS:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] RJS:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()

                            val stableKey = when {
                                text.contains("Загрузка") || text.contains("fetch") -> "captcha_rjs_1"
                                text.contains("PoW") -> "captcha_rjs_2"
                                text.contains("осматривает") || text.contains("человек") -> "captcha_rjs_3"
                                text.contains("captchaNotRobot") || text.contains("Отправка") -> "captcha_rjs_4"
                                text.contains("endSession") -> "captcha_rjs_5"
                                text.contains("решена") -> "captcha_rjs_6"
                                else -> "captcha_rjs_${text.take(15).hashCode()}"
                            }
                            updateProcessLog(identity, stableKey, "[КАПЧА RJS] $text", 60, false)
                        }

                        lineTrim.contains("[КАПЧА] WBV:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] WBV:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()

                            val isErr = text.contains("Ошибка")
                            val stableKey = when {
                                text.contains("Запрос") -> "captcha_wv_step_2"
                                text.contains("Токен") -> "captcha_wv_step_5"
                                isErr -> "captcha_wv_err"
                                else -> "captcha_wv_go_other"
                            }
                            updateProcessLog(identity, stableKey, "[КАПЧА WBV] $text", 60, isErr)
                        }

                        lineTrim.contains("Решаю VK Smart Captcha") ->
                            updateProcessLog(identity, "captcha_start", "[КАПЧА] Решение капчи...", 60, false)
                        lineTrim.contains("Smart Captcha решена") ->
                            updateProcessLog(identity, "captcha_done", "[КАПЧА] Капча решена ✓", 60, false)
                        lineTrim.contains("капча не решена") || lineTrim.contains("ошибка решения капчи") ->
                            updateProcessLog(identity, "captcha_failed", "[КАПЧА] Ошибка решения капчи", 99, true, LogLevel.ERR)
                        lineTrim == "[VK JS] Звонок создан" || lineTrim.contains("Звонок VK создан") || lineTrim.contains("Звонок создан") -> {
                            updateProcessLog(identity, "vk_js_call_progress", "[VK JS] Звонок VK создан ✓", 16, false, LogLevel.OK)
                        }
                        lineTrim.startsWith("[VK JS] Создатель вышел из звонков") ->
                            updateProcessLog(identity, "vk_js_creator_left", "[VK JS] Владелец вышел из звонка ✓", 19, false, LogLevel.OK)
                        lineTrim.startsWith("[VK JS] Звонки завершены") ->
                            updateProcessLog(identity, "vk_js_call_finished", "[VK JS] Звонок завершён ✓", 19, false, LogLevel.OK)
                        lineTrim.startsWith("[VK JS] Создатель удерживает звонок") ->
                            updateProcessLog(identity, "vk_js_creator_held", "[VK JS] Владелец удерживает звонок до отключения", 18, false, LogLevel.OK)

                        lineTrim.startsWith("[VK JS] Креды аккаунта") && lineTrim.contains("недоступны") -> {
                            updateProcessLog(identity, "vk_js_account_credentials_progress", "[VK JS] Креды аккаунта недоступны", 17, true, LogLevel.ERR)
                        }
                        lineTrim.startsWith("[VK JS] Креды аккаунта") -> {
                            updateProcessLog(identity, "vk_js_account_credentials_progress", "[VK JS] Креды аккаунта готовы ✓", 17, false, LogLevel.OK)
                        }
                        lineTrim.startsWith("[VK JS] Переход на Авто") -> {
                            val text = lineTrim.substringAfter("[VK JS]").trim()
                            val failed = text.contains("не выполнен")
                            val suffix = if (failed) "" else " ✓"
                            updateProcessLog(identity, "vk_js_account_fallback_progress", "[VK JS] $text$suffix", 17, failed, if (failed) LogLevel.ERR else LogLevel.OK)
                        }
                        lineTrim.contains("[WRAP]") -> {
                            updateProcessLog(identity, "wrap_status", "[WRAP] Ключ вычислен ✓", 10, false, LogLevel.OK)
                        }
                        lineTrim.startsWith("[ПОТОКИ]") -> {
                            val text = lineTrim.substringAfter("[ПОТОКИ]").trim()
                            val isRepair = text.contains("не пришёл", true) ||
                                text.contains("частично", true) ||
                                text.contains("Перезапуск", true)
                            val (stableKey, priority) = when {
                                text.startsWith("Отправлен ping", true) -> "stream_probe_ping" to 56
                                text.startsWith("ACK получен", true) || text.startsWith("Все активные", true) -> "stream_probe_ack" to 57
                                text.contains("ChannelBind", true) -> "stream_probe_rebind" to 58
                                text.contains("проверяю", true) -> "stream_probe_all" to 58
                                text.contains("Перезапуск", true) -> "stream_probe_restart" to 59
                                else -> "stream_probe_${text.take(24).hashCode()}" to 59
                            }
                            updateProcessLog(identity, stableKey, "[ПОТОКИ] $text", priority, isRepair, if (isRepair) LogLevel.ERR else LogLevel.LOG)
                        }
                        lineTrim.contains("[TURN]") -> {
                            val text = lineTrim.substringAfter("[TURN]").trim()
                            if (text.contains("Используются TURN IP", true)) {
                                return@forEachLine
                            }
                            val turnError = TunnelLogPolicy.isTurnStreamFailure(lineTrim) || isError
                            val (stableKey, priority) = when {
                                turnError -> "turn_error_${text.hashCode()}" to 99
                                text.contains("CreatePermission", true) -> "turn_permission_status" to 52
                                text.contains("ChannelBind", true) || text.contains("Канал", true) -> "turn_channel_status" to 53
                                text.contains("готова к передаче", true) -> "turn_ready_status" to 54
                                text.contains("Refresh", true) -> "turn_refresh_status" to 55
                                else -> null to 0
                            }
                            if (stableKey != null) {
                                updateProcessLog(identity, stableKey, "[TURN] $text", priority, turnError, if (turnError) LogLevel.ERR else LogLevel.LOG)
                            }
                        }
                        lineTrim.contains("Рукопожатие...") ->
                            updateProcessLog(identity, "peer_handshake", "Рукопожатие...", 65, false)

                        isError -> {
                            val errorKey = when {
                                lineTrim.contains("lookup login.vk.ru", true) -> "err_vk_dns"
                                lineTrim.contains("connection refused") -> "err_conn_refused"
                                lineTrim.contains("timeout") -> "err_timeout"
                                lineTrim.contains("кредов") -> "err_creds"
                                lineTrim.contains("PEER") -> "err_peer"
                                else -> "general_error_" + lineTrim.take(15).hashCode()
                            }
                            val errorMessage = if (errorKey == "err_vk_dns") {
                                "[СЕТЬ] DNS до VK недоступен: login.vk.ru"
                            } else {
                                lineTrim
                            }
                            updateProcessLog(identity, errorKey, errorMessage, 99, true)
                        }
                    }
                }
            } catch (e: Exception) {
                if (
                    isCurrent(identity) &&
                    targetProcess.isAlive &&
                    !e.readableMessage().contains("read interrupted by close", ignoreCase = true)
                ) {
                    lastDiagnostic = e.readableMessage().take(240)
                }
            } finally {
                runCatching { reader.close() }
                startStopMutex.withLock {
                    if (processIdentity === identity && process === targetProcess) {
                        val lifecycleWantsRestart = lifecycleState.processEnded(identity.ticket)
                        process = null
                        processIdentity = null
                        nativeProcessPid = null
                        activeWorkers.value = 0
                        resetWorkerRecoveryState()
                        if (readerJob === coroutineContext[Job]) readerJob = null
                        val initialExitCode = runCatching {
                            if (targetProcess.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                targetProcess.exitValue()
                            } else {
                                null
                            }
                        }.getOrNull()
                        if (initialExitCode == null) {
                            terminateExactProcess(targetProcess, identity.shutdownGraceMs)
                        }
                        val exitCode = initialExitCode
                            ?: runCatching { targetProcess.exitValue() }.getOrNull()
                        val shouldRestart = lifecycleWantsRestart
                        if (lifecycleWantsRestart) {
                            VkAutoCallsManager.finishActiveCalls()
                        }
                        val restartContext = activeContext()
                        val restartParams = if (restartContext == null) {
                            null
                        } else {
                            try {
                                currentParams?.withNewSession(restartContext)
                            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                                null
                            } catch (e: Exception) {
                                updateLog(
                                    "restart_epoch_reserve_error",
                                    "Ошибка резервирования новой эпохи: ${e.readableMessage()}. Перезапуск с последней подтверждённой эпохой.",
                                    99,
                                    true,
                                )
                                currentParams
                            }
                        }
                        if (shouldRestart && restartParams != null && restartContext != null) {
                            currentParams = restartParams
                            val reason = lastDiagnostic?.let { " Причина: $it" }.orEmpty()
                            updateLog(
                                "process_restart",
                                "✗ Rust-клиент завершился (код ${exitCode ?: "?"}). Перезапуск...$reason",
                                50,
                                true,
                            )
                            val uptimeMs = processStartedAtMs
                                .takeIf { it > 0 }
                                ?.let { SystemClock.elapsedRealtime() - it }
                                ?: Long.MAX_VALUE
                            crashRestartStreak =
                                if (uptimeMs >= CRASH_RESTART_STABLE_MS) 0 else crashRestartStreak + 1
                            val backoffShift = crashRestartStreak.coerceIn(1, 6) - 1
                            val restartDelayMs = (2_000L shl backoffShift)
                                .coerceAtMost(CRASH_RESTART_MAX_DELAY_MS)
                            scheduleRestartLocked(restartContext, restartParams, identity.ticket.epoch, restartDelayMs)
                        } else if (!lifecycleState.isDesiredRunning()) {
                            running.value = false
                        } else {
                            running.value = false
                            starting.value = false
                            stopping.value = false
                            updateLog(
                                "process_exit",
                                "✗ Rust-клиент завершился без перезапуска (код ${exitCode ?: "?"})." +
                                    lastDiagnostic?.let { " Причина: $it" }.orEmpty(),
                                99,
                                true,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun scheduleRestartLocked(
        context: Context,
        params: TunnelParams,
        epoch: Long,
        delayMs: Long,
    ) {
        restartJob?.cancel()
        restartJob = activeScope.launch {
            delay(delayMs)
            startStopMutex.withLock {
                if (!lifecycleState.canStart(epoch) || process != null) return@withLock
                restartJob = null
                startProcessLocked(context, params, epoch)
            }
        }
    }

    private fun handleCriticalError(message: String, identity: ProcessIdentity) {
        updateProcessLog(identity, "circuit_breaker", "[СТОП] $message", -1, true)
        activeScope.launch {
            startStopMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                stopLocked(identity)
            }
        }
    }

    private suspend fun TunnelParams.withNewSession(context: Context): TunnelParams {
        val store = activeSettingsStore ?: SettingsStore(context).also {
            activeSettingsStore = it
        }
        val proposed = if (generationId == Long.MAX_VALUE) Long.MAX_VALUE else generationId + 1
        val nextGen = store.reserveConnectionGeneration(proposed = proposed)
        return copy(
            generationId = nextGen,
            sessionSalt = newTunnelSessionId(),
        )
    }

    private fun terminateProcessLocked(
        identity: ProcessIdentity?,
        finishVkCalls: Boolean = false,
    ) {
        val targetProcess = identity?.process ?: process
        if (targetProcess == null) {
            process = null
            processIdentity = null
            nativeProcessPid = null
            activeWorkers.value = 0
            return
        }
        val attached = (identity == null || processIdentity === identity) && process === targetProcess
        val logReader = if (attached || process === targetProcess) readerJob else null
        val pidToKill = nativeProcessPid ?: processIdOf(targetProcess)
        if (attached || process === targetProcess) {
            process = null
            processIdentity = null
            nativeProcessPid = null
            vpnReady.value = false
            stopUptimeTimer()
            resetWorkerRecoveryState()
        }
        identity?.let { lifecycleState.releaseReservation(it.ticket) }
        logReader?.cancel()
        if (readerJob === logReader) {
            readerJob = null
        }
        val graceMs = identity?.shutdownGraceMs ?: 3_500L
        terminateExactProcess(targetProcess, graceMs, finishVkCalls)
        pidToKill?.let { pid ->
            if (pid > 0 && targetProcess.isAlive) {
                runCatching { android.os.Process.killProcess(pid) }
            }
        }
        activeWorkers.value = 0
    }

    private fun terminateExactProcess(
        targetProcess: Process,
        shutdownGraceMs: Long,
        finishVkCalls: Boolean = false,
    ) {
        val forceWindowMs = minOf(300L, shutdownGraceMs)
        val gracefulWindowMs = (shutdownGraceMs - forceWindowMs).coerceAtLeast(0L)
        try {
            val command = if (finishVkCalls) "FINISH_VK_CALLS\nSTOP\n" else "STOP\n"
            targetProcess.outputStream.write(command.toByteArray())
            targetProcess.outputStream.flush()
        } catch (_: Exception) {}
        try {
            targetProcess.waitFor(gracefulWindowMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
        if (targetProcess.isAlive) {
            try { targetProcess.destroy() } catch (_: Exception) {}
            try { targetProcess.waitFor(forceWindowMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        }
        if (targetProcess.isAlive) {
            try { targetProcess.destroyForcibly() } catch (_: Exception) {}
            try { targetProcess.waitFor(200L, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        }
        if (targetProcess.isAlive) {
            processIdOf(targetProcess)?.let { pid ->
                if (pid > 0) {
                    runCatching { android.os.Process.killProcess(pid) }
                }
            }
        }
    }

    fun stop(finishVkCalls: Boolean = false, notifyController: Boolean = true) {
        val command = lifecycleCommand.incrementAndGet()
        stopping.value = true
        scope.launch {
            startStopMutex.withLock {
                if (lifecycleCommand.get() != command) return@withLock
                stopLocked(processIdentity, finishVkCalls, notifyController = notifyController)
            }
        }
    }

    suspend fun pauseForWifi() {
        lifecycleCommand.incrementAndGet()
        startStopMutex.withLock {
            autoPausedForWifi.value = true
            stopLocked(processIdentity, preserveWifiAutoPause = true)
            starting.value = false
            stats.value = "Ожидание. Автопауза при Wi-Fi"
            updateLog(
                "wifi_auto_pause",
                "Ожидание. Автопауза при Wi-Fi",
                2,
                false,
                LogLevel.NET,
            )
        }
    }

    fun leaveWifiAutoPause() {
        autoPausedForWifi.value = false
    }

    private fun stopLocked(
        identity: ProcessIdentity?,
        finishVkCalls: Boolean = false,
        preserveWifiAutoPause: Boolean = false,
        notifyController: Boolean = true,
    ) {
        stopping.value = true
        lifecycleState.requestStop()
        if (!preserveWifiAutoPause) autoPausedForWifi.value = false
        pausedForNoNetwork.reset()
        vpnReady.value = false
        serverRoutingActive.value = false
        activeServerRoutingRule.value = null
        serverAssignedWorkers.value = null
        restartJob?.cancel()
        restartJob = null
        panelRestartJob?.cancel()
        panelRestartJob = null
        panelRestartPending.set(false)
        transportRestartPending.set(false)
        activeContext()?.let { ctx ->
            dispatchTunnelStop(
                preserveWifiAutoPause = preserveWifiAutoPause,
                notifyController = notifyController,
                stopVpn = {
                    val stopIntent = Intent(ctx, TunVpnService::class.java).apply { action = "STOP" }
                    try { ctx.startService(stopIntent) } catch (_: Exception) {}
                },
                stopController = {
                    val stopIntent = Intent(ctx, TunnelService::class.java).apply { action = "STOP" }
                    try { ctx.startService(stopIntent) } catch (_: Exception) {}
                },
            )
        }
        terminateProcessLocked(identity, finishVkCalls)
        running.value = false
        stopping.value = false
        activeWorkers.value = 0
        nativeProcessPid = null
        currentParams = null
        activeSettingsStore = null
        lastAppliedDns = null
        ManlCaptchaWebViewManager.cancelCaptcha()
    }

    fun reloadVpn() {
        val configStr = config.value?.trim() ?: return
        if (running.value && configStr.startsWith("TUNCONF:")) {
            activeScope.launch(Dispatchers.Main) {
                val parts = configStr.removePrefix("TUNCONF:").split(":", limit = 3)
                val clientIp = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return@launch
                val dns = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return@launch
                activeContext()?.let { ctx ->
                    val vpnIntent = Intent(ctx, TunVpnService::class.java).apply {
                        action = "RELOAD_ROUTING"
                        putExtra("client_ip", clientIp)
                        putExtra("dns", dns)
                        putExtra("force_rebuild", true)
                        putExtra("is_routing_reload", true)
                        putExtra("reason", "reload_vpn")
                    }
                    try { startTunVpnService(ctx, vpnIntent) } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun handleCaptchaSolve(
        requestMode: String,
        redirectUri: String,
        sessionToken: String,
        identity: ProcessIdentity,
    ) {
        if (!isCurrent(identity)) return
        val ctx = activeContext() ?: run {
            writeCaptchaResult("error:context is null", identity)
            return
        }
        val mode = requestMode.lowercase()

        try {
            val token = when (mode) {
                "auto" -> solveSingleAutoWebViewCaptcha(redirectUri, sessionToken, identity)
                "manual" -> {
                    updateProcessLog(identity, "captcha_wv_step_1", "[КАПЧА WBV] Создание ручного WebView...", 5, false)
                    ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
                else -> {
                    if (currentCaptchaSolveMethod == "auto") {
                        solveAutoWebViewCaptcha(ctx, redirectUri, sessionToken, identity)
                    } else {
                        updateProcessLog(identity, "captcha_wv_step_1", "[КАПЧА WBV] Создание ручного WebView...", 5, false)
                        ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                    }
                }
            }
            updateProcessLog(identity, "captcha_wv_step_4", "[КАПЧА WBV] Капча решена ✓", 5, false)
            writeCaptchaResult(token, identity)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            updateProcessLog(identity, "captcha_wv_err", "[КАПЧА WBV] Таймаут WebView", 5, true)
            writeCaptchaResult("error:timeout", identity)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            updateProcessLog(identity, "captcha_wv_err", "[КАПЧА WBV] Отменено", 5, true)
            writeCaptchaResult("error:cancelled", identity)
        } catch (e: IllegalStateException) {
            val errorMsg = e.message ?: "WV state error"
            updateProcessLog(identity, "captcha_wv_err", "[КАПЧА WBV] $errorMsg", 5, true)
            writeCaptchaResult("error:$errorMsg", identity)
        } catch (e: Exception) {
            val errorMsg = e.message ?: "${e::class.simpleName}"
            if (errorMsg != "tunnel stopped") {
                updateProcessLog(identity, "captcha_wv_err", "[КАПЧА WBV] Ошибка — $errorMsg", 5, true)
            }
            writeCaptchaResult("error:$errorMsg", identity)
        }

        updateProcessLog(identity, "captcha_wv_step_6", "[КАПЧА WBV] WebView уничтожен", 5, false)
    }

    private suspend fun solveSingleAutoWebViewCaptcha(
        redirectUri: String,
        sessionToken: String,
        identity: ProcessIdentity,
    ): String {
        updateProcessLog(identity, "captcha_wv_step_1", "[КАПЧА WBV] Авто WebView попытка 10с...", 5, false)
        return CaptchaWebViewManager.solveCaptchaAsync(redirectUri, sessionToken) { step ->
            updateProcessLog(identity, "captcha_wv_auto_step", "[КАПЧА WBV] $step", 5, false)
        }
    }

    private suspend fun solveAutoWebViewCaptcha(
        ctx: Context,
        redirectUri: String,
        sessionToken: String,
        identity: ProcessIdentity,
    ): String {
        for (attempt in 1..2) {
            updateProcessLog(identity, "captcha_wv_step_1", "[КАПЧА WBV] Авто WebView попытка $attempt/2...", 5, false)
            try {
                return CaptchaWebViewManager.solveCaptchaAsync(redirectUri, sessionToken) { step ->
                    updateProcessLog(identity, "captcha_wv_auto_step", "[КАПЧА WBV] $step", 5, false)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                updateProcessLog(identity, "captcha_wv_timeout_$attempt", "[КАПЧА WBV] Авто таймаут 10с ($attempt/2)", 5, attempt == 2)
                if (attempt == 2) {
                    updateProcessLog(identity, "captcha_wv_fallback", "[КАПЧА WBV] 2 таймаута авто, открыт ручной WebView", 5, false)
                    return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
            } catch (e: IllegalStateException) {
                if (e.message == CaptchaWebViewManager.ERROR_SLIDER_DETECTED) {
                    updateProcessLog(identity, "captcha_wv_fallback", "[КАПЧА WBV] Обнаружен слайдер, открыт ручной WebView", 5, false)
                    return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
                throw e
            }
        }
        return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
    }

    fun parseServerRoutingRuleJson(routingJsonStr: String): ServerRoutingRule? =
        parseServerRoutingRule(routingJsonStr)

    fun applyServerRoutingRule(newServerRule: ServerRoutingRule?, clientIp: String, dns: String) {
        val previousRule = activeServerRoutingRule.value
        activeServerRoutingRule.value = newServerRule
        serverRoutingActive.value = (newServerRule != null)

        activeContext()?.let { ctx ->
            if (newServerRule != null) {
                updateDomainRouting(
                    enabled = newServerRule.templateDomains || newServerRule.domains.isNotEmpty(),
                    excludeRuZones = false,
                    templateDomains = newServerRule.templateDomains,
                    directDomains = newServerRule.domains.joinToString(","),
                    isWhitelist = newServerRule.domainsIsWhite,
                    ipRoutingEnabled = newServerRule.ips.isNotEmpty(),
                    ipIsWhitelist = newServerRule.ipIsWhite,
                    directIps = newServerRule.ips.joinToString(","),
                )
                // the VPN's excludeRoutes come from ips/ipIsWhite too: a change there alone (App routing from the panel)
                // left stale routes until a reconnect
                val vpnInputsChanged = previousRule == null ||
                    previousRule.isWhite != newServerRule.isWhite ||
                    previousRule.packages.toSet() != newServerRule.packages.toSet() ||
                    previousRule.ipIsWhite != newServerRule.ipIsWhite ||
                    // only a blacklist's IPs become VPN routes (excludeRoute); a whitelist's list changes no route
                    (!newServerRule.ipIsWhite && previousRule.ips.toSet() != newServerRule.ips.toSet())
                if (vpnReady.value && vpnInputsChanged) {
                    val reloadIntent = Intent(ctx, TunVpnService::class.java).apply {
                        action = "RELOAD_ROUTING"
                        putExtra("client_ip", clientIp)
                        putExtra("dns", dns)
                        putExtra("reason", "routing_changed")
                    }
                    startTunVpnService(ctx, reloadIntent)
                }
            } else if (previousRule != null) {
                activeScope.launch {
                    val settingsStore = SettingsStore(ctx)
                    val domainWhite = settingsStore.domainIsWhitelist.first()
                    val excludeRu = settingsStore.excludeRuZonesEnabled.first()
                    val templateEnabled = settingsStore.templateDomainsEnabled.first()
                    val domains = if (domainWhite) settingsStore.directDomainsWhite.first() else settingsStore.directDomainsBlack.first()
                    val ipWhite = settingsStore.ipIsWhitelist.first()
                    val ips = if (ipWhite) settingsStore.directIpsWhite.first() else settingsStore.directIpsBlack.first()
                    val hasDomains = if (domainWhite) domains.isNotBlank() else (domains.isNotBlank() || templateEnabled || excludeRu)
                    val effectiveDomainEnabled = hasDomains   // the list is the switch, same as IP
                    val hasIps = ips.isNotBlank()
                    val effectiveIpEnabled = hasIps   // the list is the switch (TunVpnService)
                    updateDomainRouting(
                        enabled = effectiveDomainEnabled,
                        excludeRuZones = if (domainWhite) false else excludeRu,
                        templateDomains = if (domainWhite) false else templateEnabled,
                        directDomains = domains,
                        isWhitelist = domainWhite,
                        ipRoutingEnabled = effectiveIpEnabled,
                        ipIsWhitelist = ipWhite,
                        directIps = ips,
                    )
                    if (vpnReady.value) {
                        val reloadIntent = Intent(ctx, TunVpnService::class.java).apply {
                            action = "RELOAD_ROUTING"
                            putExtra("client_ip", clientIp)
                            putExtra("dns", dns)
                            putExtra("reason", "routing_cleared")
                        }
                        startTunVpnService(ctx, reloadIntent)
                    }
                }
            }
        }
    }

    fun updateDomainRouting(
        enabled: Boolean,
        excludeRuZones: Boolean,
        templateDomains: Boolean,
        directDomains: String,
        isWhitelist: Boolean = false,
        ipRoutingEnabled: Boolean = false,
        ipIsWhitelist: Boolean = false,
        directIps: String = "",
    ) {
        val identity = processIdentity ?: return
        val command = "UPDATE_DOMAINS|enabled=${if (enabled) 1 else 0};ru=${if (excludeRuZones) 1 else 0};template=${if (templateDomains) 1 else 0};whitelist=${if (isWhitelist) 1 else 0};domains=$directDomains;ip_enabled=${if (ipRoutingEnabled) 1 else 0};ip_whitelist=${if (ipIsWhitelist) 1 else 0};ips=$directIps"
        writeProcessCommand(identity, command, "domain_routing_err")
    }

    fun pushLocalRouting(ctx: Context) {
        if (serverRoutingActive.value) return
        activeScope.launch {
            val settingsStore = SettingsStore(ctx)
            val domainWhite = settingsStore.domainIsWhitelist.first()
            val excludeRu = settingsStore.excludeRuZonesEnabled.first()
            val templateEnabled = settingsStore.templateDomainsEnabled.first()
            val domains = if (domainWhite) settingsStore.directDomainsWhite.first() else settingsStore.directDomainsBlack.first()
            val ipWhite = settingsStore.ipIsWhitelist.first()
            val ips = if (ipWhite) settingsStore.directIpsWhite.first() else settingsStore.directIpsBlack.first()
            val hasDomains = if (domainWhite) domains.isNotBlank() else (domains.isNotBlank() || templateEnabled || excludeRu)
            val effectiveDomainEnabled = hasDomains   // the list is the switch, same as IP
            val hasIps = ips.isNotBlank()
            val effectiveIpEnabled = hasIps   // the list is the switch (TunVpnService)
            updateDomainRouting(
                enabled = effectiveDomainEnabled,
                excludeRuZones = if (domainWhite) false else excludeRu,
                templateDomains = if (domainWhite) false else templateEnabled,
                directDomains = domains,
                isWhitelist = domainWhite,
                ipRoutingEnabled = effectiveIpEnabled,
                ipIsWhitelist = ipWhite,
                directIps = ips,
            )
        }
    }

    private fun writeCaptchaResult(result: String, identity: ProcessIdentity) {
        writeProcessCommand(identity, "CAPTCHA_RESULT|$result", "captcha_write_err")
    }

    private fun writeProcessCommand(
        identity: ProcessIdentity,
        command: String,
        errorKey: String,
    ): Boolean {
        if (!isCurrent(identity) || !identity.process.isAlive) return false
        return try {
            synchronized(identity.inputLock) {
                identity.process.outputStream.write("$command\n".toByteArray(Charsets.UTF_8))
                identity.process.outputStream.flush()
            }
            true
        } catch (e: Exception) {
            updateProcessLog(identity, errorKey, "Ошибка записи в Rust: ${e.message}", 200, true)
            false
        }
    }

    fun clearLogs() {
        synchronized(pendingLogLock) {
            logEpoch.incrementAndGet()
            pendingLogs.clear()
        }
        unreadErrorCount.value = 0
        scope.launch(Dispatchers.Main) {
            logs.clear()
        }
        if (!running.value) {
            activeWorkers.value = 0
        }
    }

    fun startCooldown(millis: Long) {
        cooldownJob?.cancel()
        cooldownActive.value = true
        cooldownJob = scope.launch(Dispatchers.Main) {
            delay(millis)
            cooldownActive.value = false
        }
    }

    private fun Throwable.readableMessage(): String {
        val text = message ?: localizedMessage
        return if (text.isNullOrBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
    }
}

data class TunnelParams(
    val peer: String,
    val vkHashes: String,
    val secondaryVkHash: String = "",
    val workersPerHash: Int,
    val port: Int,
    val sni: String = "",
    val connectionPassword: String = "",
    val protocol: String = "udp",
    val vkAuthMode: String = "vkcalls",
    val captchaMode: String = "auto",
    val captchaSolveMethod: String = "auto",
    val fingerprint: String = "firefox",
    val clientIds: String = CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS,
    val obfsMode: String = CsqttConstants.Tunnel.DEFAULT_OBFS_MODE,
    val turnTransport: String = CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT,
    val srtpMode: String = CsqttConstants.SrtpMode.DEFAULT,
    val generationId: Long = 0,
    val sessionSalt: String = "",
    val allowHashRedistribution: Boolean = false,
    val vkHashMode: String = CsqttConstants.VkAutoHash.MODE_MANUAL,
    val vkAccessToken: String = "",
    val customTurnIps: String = "",
    val domainRoutingEnabled: Boolean = false,
    val domainIsWhitelist: Boolean = false,
    val excludeRuZones: Boolean = false,
    val templateDomains: Boolean = false,
    val directDomains: String = "",
    val ipRoutingEnabled: Boolean = false,
    val ipIsWhitelist: Boolean = false,
    val directIps: String = "",
)
