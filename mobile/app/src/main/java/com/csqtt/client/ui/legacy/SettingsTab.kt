package com.csqtt.client.ui.legacy

import com.csqtt.client.showRaisedToast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelAuthSnapshot
import com.csqtt.client.CsqttConstants
import com.csqtt.client.WorkerCountPolicy
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.VkHashValidator
import com.csqtt.client.shouldConfirmAutoJsMode
import com.csqtt.client.shouldConfirmTcpTransport
import com.csqtt.client.vkAuthModeForHashMode
import com.csqtt.client.ui.legacy.components.CsqttScreen
import com.csqtt.client.ui.legacy.components.CsqttSettingRow
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes
import com.csqtt.client.ui.legacy.dialogs.HashesDialog
import com.csqtt.client.ui.legacy.dialogs.SecretsDialog
import com.csqtt.client.ui.legacy.dialogs.VkTokenRevokeDialog
import com.csqtt.client.ui.legacy.tunnel.WorkersInfoDialog
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.legacy.utils.stripVkUrlStatic
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast

private const val WORKERS_PER_GROUP = CsqttConstants.Tunnel.WORKERS_PER_GROUP

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTab(
    settingsStore: SettingsStore,
    tunnelAuthSettings: TunnelAuthSnapshot,
    validationRequest: Int = 0,
    onVkAuthRequested: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    SettingsTabContent(
        context,
        scope,
        settingsStore,
        tunnelAuthSettings,
        validationRequest,
        onVkAuthRequested,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTabContent(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    settingsStore: SettingsStore,
    tunnelAuthSettings: TunnelAuthSnapshot,
    validationRequest: Int,
    onVkAuthRequested: () -> Unit,
) {
    val workersPerHash = remember(settingsStore) {
        settingsStore.workersPerHash
        .map<Int, Int?> { it }
    }
    val savedWorkersPerHash by workersPerHash
        .collectAsStateWithLifecycle(initialValue = SettingsStore.cachedWorkersPerHash)

    val activeProfile = tunnelAuthSettings.profile
    val csqttLinkMode = tunnelAuthSettings.csqttLinkMode
    val csqttLink = tunnelAuthSettings.csqttLink
    val manualPortsEnabled by settingsStore.manualPortsEnabled.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedManualPortsEnabled ?: false,
    )
    val savedServerPeerPort by settingsStore.serverPeerPort.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedServerPeerPort ?: CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT,
    )

    val activeFingerprint by settingsStore.selectedFingerprint.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_FINGERPRINT)
    val activeClientIds by settingsStore.activeClientIds.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS)
    val vkHashCheckResultsJson by settingsStore.vkHashCheckResults.collectAsStateWithLifecycle(initialValue = "{}")
    val vkHashCheckResults = remember(vkHashCheckResultsJson) {
        VkHashValidationCodec.decode(vkHashCheckResultsJson)
    }
    val savedHashMode by settingsStore.vkHashMode.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkHashMode)
    val savedVkAccessToken by settingsStore.vkAccessToken.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkAccessToken)
    val connectionPassword by settingsStore.connectionPassword.collectAsStateWithLifecycle(initialValue = "")
    var showSecretsDialog by rememberSaveable { mutableStateOf(false) }

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    var showHashModeGeneralDialog by rememberSaveable { mutableStateOf(false) }
    var showHashModeDetailDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showWorkModeGeneralDialog by rememberSaveable { mutableStateOf(false) }
    var showWorkModeDetailDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showVkRevokeDialog by rememberSaveable { mutableStateOf(false) }
    var vkAuthMode by rememberSaveable { mutableStateOf(CsqttConstants.VkAuth.MODE_CALLS) }
    val autoJsRiskAcknowledged by settingsStore.autoJsRiskAcknowledged.collectAsStateWithLifecycle(initialValue = false)
    var showAutoJsRiskDialog by remember { mutableStateOf(false) }
    var showWorkersInfoDialog by rememberSaveable { mutableStateOf(false) }
    var pendingAutoJsSelection by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showRequiredFieldErrors by rememberSaveable(activeProfile) { mutableStateOf(false) }

    val turnTransport by settingsStore.turnTransport.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedTurnTransport ?: CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT,
    )
    val tcpTransportRiskAcknowledged by settingsStore.tcpTransportRiskAcknowledged.collectAsStateWithLifecycle(
        initialValue = false,
    )
    var showTurnTransportGeneralDialog by rememberSaveable { mutableStateOf(false) }
    var showTurnTransportDetailDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showTcpTransportRiskDialog by remember { mutableStateOf(false) }
    var pendingTcpTransportSelection by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun applyTurnTransport(transport: String) {
        scope.launch { settingsStore.saveTurnTransport(transport) }
    }

    val srtpMode by settingsStore.srtpMode.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedSrtpMode ?: CsqttConstants.SrtpMode.DEFAULT,
    )

    fun applySrtpMode(mode: String) {
        scope.launch { settingsStore.saveSrtpMode(mode) }
    }

    fun requestTurnTransport(transport: String) {
        if (shouldConfirmTcpTransport(turnTransport, transport, tcpTransportRiskAcknowledged)) {
            pendingTcpTransportSelection = { applyTurnTransport(transport) }
            showTcpTransportRiskDialog = true
        } else {
            applyTurnTransport(transport)
        }
    }

    val hashSettingsLoaded = savedHashMode != null && savedVkAccessToken != null
    var peerInput by rememberSaveable { mutableStateOf("") }
    var peerPortInput by rememberSaveable(activeProfile) {
        val initialPort = if (SettingsStore.cachedManualPortsEnabled == true) {
            (SettingsStore.cachedServerPeerPort ?: CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT).toString()
        } else {
            CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT.toString()
        }
        mutableStateOf(initialPort)
    }
    var vkHash1 by rememberSaveable { mutableStateOf("") }
    var vkHash2 by rememberSaveable { mutableStateOf("") }
    var vkHash3 by rememberSaveable { mutableStateOf("") }
    var vkHash4 by rememberSaveable { mutableStateOf("") }
    var vkHash5 by rememberSaveable { mutableStateOf("") }
    var vkHash6 by rememberSaveable { mutableStateOf("") }
    var showHashesDialog by rememberSaveable { mutableStateOf(false) }
    var autoCaptchaEnabled by rememberSaveable { mutableStateOf(true) }
    var useWVCaptcha by rememberSaveable { mutableStateOf(false) }
    var isManualMode by rememberSaveable { mutableStateOf(true) }
    var wbvManualMode by rememberSaveable { mutableStateOf(true) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var peerPortSaveJob by remember { mutableStateOf<Job?>(null) }
    var peerPortEdited by rememberSaveable(activeProfile) { mutableStateOf(false) }
    var linkSaveJob by remember { mutableStateOf<Job?>(null) }
    var linkText by remember(activeProfile, csqttLink) { mutableStateOf(csqttLink) }
    var initialized by rememberSaveable(activeProfile) { mutableStateOf(false) }
    val participantMode = csqttLinkMode

    val allHashes = remember(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6) {
        listOf(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6)
    }
    val uniqueHashes = remember(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6, vkHashCheckResults) {
        VkHashValidationCodec.active(allHashes, vkHashCheckResults)
    }
    val parsedCsqttLink = remember(linkText) { parseCsqttLink(linkText) }
    val linkHashes = parsedCsqttLink?.hashes.orEmpty()
    val linkVkAccessToken = parsedCsqttLink?.vkAccessToken.orEmpty()
    val serverWorkers by TunnelManager.serverAssignedWorkers.collectAsState()
    val effectiveServerWorkers = serverWorkers.takeIf { tunnelRunning && it in 3..100 }
    val linkTokenActive = participantMode && linkVkAccessToken.isNotEmpty()
    val effectiveVkAuthMode = if (
        participantMode && linkHashes.isNotEmpty() && vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
    ) {
        CsqttConstants.VkAuth.MODE_CALLS
    } else {
        vkAuthMode
    }
    val effectiveHashMode = if (
        linkTokenActive && savedHashMode == CsqttConstants.VkAutoHash.MODE_MANUAL
    ) {
        CsqttConstants.VkAutoHash.MODE_AUTO_API
    } else {
        savedHashMode
    }
    val accountAutoJsMode = effectiveVkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
    val autoHashMode = accountAutoJsMode || (
        effectiveHashMode != null && effectiveHashMode != CsqttConstants.VkAutoHash.MODE_MANUAL
    )
    val vkTokenActive = savedVkAccessToken?.isNotBlank() == true
    val effectiveVkTokenActive = vkTokenActive || linkTokenActive
    val filledHashCount = remember(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6) { uniqueHashes.size }
    val combinedHashes = remember(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6) {
        allHashes.filter { it.isNotBlank() && it.length >= 16 }.distinct().joinToString(",")
    }
    val selectableMaxWorkers = CsqttConstants.Tunnel.MAX_WORKERS.toFloat()
    var sniInput by rememberSaveable { mutableStateOf("") }

    val currentWorkers = roundToGroup(
        (savedWorkersPerHash ?: WORKERS_PER_GROUP).toFloat()
            .coerceIn(5f, selectableMaxWorkers),
        selectableMaxWorkers,
    )

    val hashErrors = remember(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6) {
        buildList {
            allHashes.forEachIndexed { i, h ->
                if (h.isNotBlank() && h.length < 16) add("Хеш ${i + 1} — короткий")
            }
            val filled = allHashes.filter { it.isNotBlank() && it.length >= 16 }
            if (filled.size != filled.distinct().size) add("Есть дубликаты хешей")
        }
    }
    val hasInputHashErrors = remember(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6) { hashErrors.isNotEmpty() }

    LaunchedEffect(validationRequest) {
        if (validationRequest > 0) showRequiredFieldErrors = true
    }

    fun parseHashes(raw: String) {
        val parts = raw.split(Regex("[,\\s\\n]+")).map { stripVkUrlStatic(it) }.filter { it.isNotEmpty() }
        vkHash1 = parts.getOrElse(0) { "" }
        vkHash2 = parts.getOrElse(1) { "" }
        vkHash3 = parts.getOrElse(2) { "" }
        vkHash4 = parts.getOrElse(3) { "" }
        vkHash5 = parts.getOrElse(4) { "" }
        vkHash6 = parts.getOrElse(5) { "" }
    }

    fun normalizeHashes(vararg hashes: String): String {
        return hashes
            .map { stripVkUrlStatic(it) }
            .filter { it.isNotBlank() && it.length >= 16 }
            .distinct()
            .joinToString(",")
    }

    LaunchedEffect(activeProfile) {
        saveJob?.cancel()
        linkSaveJob?.cancel()
        peerPortSaveJob?.cancel()
        val peer = settingsStore.peer.first()
        val hashes = settingsStore.vkHashes.first()
        val loadedVkAuthMode = settingsStore.vkAuthMode.first()
        val captchaMode = settingsStore.captchaMode.first()
        val captchaMethod = settingsStore.captchaSolveMethod.first()
        val wbvCaptchaMethod = settingsStore.captchaWbvSolveMethod.first()

        peerInput = peer
        val port = settingsStore.serverPeerPort.first()
        val manual = settingsStore.manualPortsEnabled.first()
        if (!peerPortEdited) {
            val targetPort = if (manual) port.toString() else CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT.toString()
            if (peerPortInput != targetPort) {
                peerPortInput = targetPort
            }
        }
        parseHashes(hashes)
        vkAuthMode = loadedVkAuthMode
        sniInput = settingsStore.sni.first()
        autoCaptchaEnabled = captchaMode == "auto"
        useWVCaptcha = captchaMode != "rjs"
        wbvManualMode = wbvCaptchaMethod != "auto"
        isManualMode = if (captchaMode == "wv") wbvManualMode else captchaMethod != "auto"

        initialized = true
    }

    LaunchedEffect(activeProfile, manualPortsEnabled, savedServerPeerPort) {
        if (!peerPortEdited) {
            val targetPort = if (manualPortsEnabled) {
                savedServerPeerPort.toString()
            } else {
                CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT.toString()
            }
            if (peerPortInput != targetPort) {
                peerPortInput = targetPort
            }
        }
    }

    LaunchedEffect(activeProfile, manualPortsEnabled) {
        if (!manualPortsEnabled) {
            peerPortSaveJob?.cancel()
            val defaultPort = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT.toString()
            if (peerPortInput != defaultPort) {
                peerPortInput = defaultPort
            }
            peerPortEdited = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            saveJob?.cancel()
            linkSaveJob?.cancel()
            peerPortSaveJob?.cancel()
        }
    }

    fun saveTunnelSettingsNow(hashes: String = combinedHashes, onSaved: (() -> Unit)? = null) {
        saveJob?.cancel()
        scope.launch {
            if (participantMode) {
                settingsStore.saveParticipantTunnelSettings(hashes, currentWorkers.toInt())
            } else {
                settingsStore.save(
                    peerInput, hashes, "",
                    currentWorkers.toInt(), "udp", 0, sniInput, false
                )
            }
            onSaved?.invoke()
        }
    }

    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            if (participantMode) {
                settingsStore.saveParticipantTunnelSettings(combinedHashes, currentWorkers.toInt())
            } else {
                settingsStore.save(
                    peerInput, combinedHashes, "",
                    currentWorkers.toInt(), "udp", 0, sniInput, false
                )
            }
        }
    }

    fun applyWorkMode(mode: String) {
        vkAuthMode = mode
        scope.launch {
            settingsStore.saveVkAuthMode(mode)
        }
    }

    fun applyHashMode(mode: String) {
        val nextWorkMode = vkAuthModeForHashMode(mode, vkAuthMode)
        vkAuthMode = nextWorkMode
        scope.launch {
            settingsStore.saveVkHashMode(mode)
        }
    }

    fun requestWorkMode(mode: String) {
        if (shouldConfirmAutoJsMode(vkAuthMode, mode, autoJsRiskAcknowledged)) {
            pendingAutoJsSelection = { applyWorkMode(mode) }
            showAutoJsRiskDialog = true
        } else {
            applyWorkMode(mode)
        }
    }

    fun requestHashMode(mode: String) {
        val nextWorkMode = vkAuthModeForHashMode(mode, vkAuthMode)
        if (shouldConfirmAutoJsMode(vkAuthMode, nextWorkMode, autoJsRiskAcknowledged)) {
            pendingAutoJsSelection = { applyHashMode(mode) }
            showAutoJsRiskDialog = true
        } else {
            applyHashMode(mode)
        }
    }

    LaunchedEffect(initialized, participantMode, linkHashes, linkTokenActive, vkAuthMode, savedHashMode) {
        if (!initialized || !participantMode) return@LaunchedEffect
        when {
            linkHashes.isNotEmpty() && vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS -> {
                applyWorkMode(CsqttConstants.VkAuth.MODE_CALLS)
            }
            linkTokenActive && savedHashMode == CsqttConstants.VkAutoHash.MODE_MANUAL -> {
                applyHashMode(CsqttConstants.VkAutoHash.MODE_AUTO_API)
            }
        }
    }

    LaunchedEffect(initialized, currentWorkers, savedWorkersPerHash) {
        val savedWorkers = savedWorkersPerHash
        if (initialized && savedWorkers != null && currentWorkers.toInt() != savedWorkers) {
            scope.launch { settingsStore.saveWorkersPerHash(currentWorkers.toInt()) }
        }
    }

    val scrollState = rememberScrollState()

    val isPeerValid = peerInput.isNotBlank() && !peerInput.contains(":")
    val isPeerPortValid = peerPortInput.toIntOrNull() in 1..65535
    val hashesReadyForTunnel = when {
        participantMode && linkHashes.isNotEmpty() -> true
        participantMode && linkTokenActive -> true
        autoHashMode -> effectiveVkTokenActive
        else -> filledHashCount > 0
    }
    val peerRequiredError = showRequiredFieldErrors && !participantMode && !isPeerValid
    val peerPortRequiredError = showRequiredFieldErrors && !participantMode && manualPortsEnabled && !isPeerPortValid
    val linkRequiredError = showRequiredFieldErrors && participantMode && parsedCsqttLink == null
    val hashesRequiredError = showRequiredFieldErrors && !hashesReadyForTunnel
    val passwordRequiredError = showRequiredFieldErrors && !participantMode && connectionPassword.isBlank()
    if (showHashesDialog) {
        HashesDialog(
            hash1 = vkHash1,
            hash2 = vkHash2,
            hash3 = vkHash3,
            hash4 = vkHash4,
            hash5 = vkHash5,
            hash6 = vkHash6,
            validationResults = vkHashCheckResults,
            onValidationResultsChange = { results ->
                scope.launch {
                    settingsStore.saveVkHashCheckResults(VkHashValidationCodec.encode(results))
                }
            },
            onCheck = { hashes ->
                VkHashValidator.check(context, hashes, activeFingerprint, activeClientIds)
            },
            onAutoSave = { h1, h2, h3, h4, h5, h6 ->
                val cleaned1 = stripVkUrlStatic(h1)
                val cleaned2 = stripVkUrlStatic(h2)
                val cleaned3 = stripVkUrlStatic(h3)
                val cleaned4 = stripVkUrlStatic(h4)
                val cleaned5 = stripVkUrlStatic(h5)
                val cleaned6 = stripVkUrlStatic(h6)
                vkHash1 = cleaned1
                vkHash2 = cleaned2
                vkHash3 = cleaned3
                vkHash4 = cleaned4
                vkHash5 = cleaned5
                vkHash6 = cleaned6
                saveTunnelSettingsNow(normalizeHashes(cleaned1, cleaned2, cleaned3, cleaned4, cleaned5, cleaned6))
            },
            onSave = { h1, h2, h3, h4, h5, h6 ->
                val cleaned1 = stripVkUrlStatic(h1)
                val cleaned2 = stripVkUrlStatic(h2)
                val cleaned3 = stripVkUrlStatic(h3)
                val cleaned4 = stripVkUrlStatic(h4)
                val cleaned5 = stripVkUrlStatic(h5)
                val cleaned6 = stripVkUrlStatic(h6)
                vkHash1 = cleaned1
                vkHash2 = cleaned2
                vkHash3 = cleaned3
                vkHash4 = cleaned4
                vkHash5 = cleaned5
                vkHash6 = cleaned6
                saveTunnelSettingsNow(normalizeHashes(cleaned1, cleaned2, cleaned3, cleaned4, cleaned5, cleaned6)) {
                    showHashesDialog = false
                }
            },
            onDismiss = {
                saveTunnelSettingsNow(normalizeHashes(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6)) {
                    showHashesDialog = false
                }
            }
        )
    }

    if (showVkRevokeDialog) {
        VkTokenRevokeDialog(
            onCancel = { showVkRevokeDialog = false },
            onCopyToken = {
                val token = savedVkAccessToken.orEmpty()
                if (token.isNotBlank()) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("VK token", token))
                        context.showRaisedToast("Бессрочный VK токен скопирован", Toast.LENGTH_SHORT)
                    } else {
                        context.showRaisedToast("Не удалось скопировать VK токен", Toast.LENGTH_SHORT)
                    }
                }
            },
            onRevokeToken = {
                showVkRevokeDialog = false
                scope.launch { settingsStore.clearVkAccessToken() }
            }
        )
    }

    CsqttScreen {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (participantMode) {
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = { value ->
                            linkText = value.filterNot(Char::isWhitespace)
                            linkSaveJob?.cancel()
                            linkSaveJob = scope.launch {
                                delay(300)
                                settingsStore.saveCsqttLink(linkText)
                            }
                        },
                        label = { Text("Ссылка csqtt://", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        placeholder = { Text("csqtt://connect?v=2&host=ip&peer=порт&password=пароль", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        isError = linkRequiredError || (linkText.isNotBlank() && parsedCsqttLink == null),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = CsqttShapes.Control,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        )
                    )

                    if (linkText.isNotBlank() && parsedCsqttLink == null) {
                        Text(
                            text = "Неверная ссылка FOCSQ v2",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }

                    if (linkHashes.isNotEmpty()) {
                        Text(
                            text = "VK хеши из ссылки: ${linkHashes.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        if (linkTokenActive) {
                            Text(
                                text = "VK токен из ссылки",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                        VkHashModeControls(
                            hashSettingsLoaded = hashSettingsLoaded,
                            autoHashMode = autoHashMode,
                            savedHashMode = effectiveHashMode,
                            vkTokenActive = effectiveVkTokenActive,
                            tunnelRunning = tunnelRunning,
                            filledHashCount = filledHashCount,
                            hasInputHashErrors = hasInputHashErrors || hashesRequiredError,
                            hashErrorTexts = hashErrors.filter { !it.contains("короткий") },
                            onOpenHashes = { showHashesDialog = true },
                            onTitleInfo = { showHashModeGeneralDialog = true },
                            onInfo = { mode -> showHashModeDetailDialog = mode },
                            onSelected = ::requestHashMode,
                            onLogin = onVkAuthRequested,
                            onRevokeToken = { showVkRevokeDialog = true },
                            authorizationRequiredError = hashesRequiredError && autoHashMode,
                            manualAllowed = !linkTokenActive,
                            tokenFromLink = linkTokenActive,
                        )
                    }
                } else {
                    Text(
                        "Сервер и Хеши",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    if (manualPortsEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = peerInput,
                                onValueChange = {
                                    peerInput = it.filter { c -> !c.isWhitespace() }
                                    scheduleSave()
                                },
                                label = {
                                    Text(
                                        "IP сервера или домен",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                placeholder = { Text("1.2.3.4", maxLines = 1) },
                                singleLine = true,
                                isError = peerRequiredError || (!isPeerValid && peerInput.isNotEmpty()),
                                modifier = Modifier.weight(1f),
                                shape = CsqttShapes.Control,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                ),
                            )
                            OutlinedTextField(
                                value = peerPortInput,
                                onValueChange = { value ->
                                    val nextValue = value.filter(Char::isDigit).take(5)
                                    peerPortInput = nextValue
                                    peerPortEdited = true
                                    peerPortSaveJob?.cancel()
                                    peerPortSaveJob = scope.launch {
                                        delay(300)
                                        val port = nextValue.toIntOrNull()
                                        if (port != null && port in 1..65535) {
                                            settingsStore.saveServerPeerPort(port)
                                            peerPortEdited = false
                                        }
                                    }
                                },
                                label = { Text("Порт PEER", maxLines = 1, softWrap = false) },
                                placeholder = {
                                    Text(
                                        CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT.toString(),
                                        maxLines = 1,
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = peerPortRequiredError || (peerPortInput.isNotEmpty() && !isPeerPortValid),
                                modifier = Modifier.weight(1f),
                                shape = CsqttShapes.Control,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                ),
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = peerInput,
                            onValueChange = {
                                peerInput = it.filter { c -> !c.isWhitespace() }
                                scheduleSave()
                            },
                            label = {
                                Text(
                                    "IP сервера или домен (без порта)",
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            placeholder = {
                                Text(
                                    "1.2.3.4 (или test.com)",
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            singleLine = true,
                            isError = peerRequiredError || (!isPeerValid && peerInput.isNotEmpty()),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            shape = CsqttShapes.Control,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            ),
                        )
                    }

                    VkHashModeControls(
                        hashSettingsLoaded = hashSettingsLoaded,
                        autoHashMode = autoHashMode,
                        savedHashMode = effectiveHashMode,
                        vkTokenActive = effectiveVkTokenActive,
                        tunnelRunning = tunnelRunning,
                        filledHashCount = filledHashCount,
                        hasInputHashErrors = hasInputHashErrors || hashesRequiredError,
                        hashErrorTexts = hashErrors.filter { !it.contains("короткий") },
                        onOpenHashes = { showHashesDialog = true },
                        onTitleInfo = { showHashModeGeneralDialog = true },
                        onInfo = { mode -> showHashModeDetailDialog = mode },
                        onSelected = ::requestHashMode,
                        onLogin = onVkAuthRequested,
                        onRevokeToken = { showVkRevokeDialog = true },
                        authorizationRequiredError = hashesRequiredError && autoHashMode,
                    )
                }


                CompactDropdownSetting(
                    title = "Режим кредов",
                    selectedKey = effectiveVkAuthMode,
                    options = listOf(
                        CsqttConstants.VkAuth.MODE_CAPTCHA to "Капча",
                        CsqttConstants.VkAuth.MODE_CALLS to "Авто",
                        CsqttConstants.VkAuth.MODE_AUTO_JS to "Авто ВК",
                    ).filterNot { (mode, _) ->
                        participantMode && linkHashes.isNotEmpty() && mode == CsqttConstants.VkAuth.MODE_AUTO_JS
                    },
                    enabled = true,
                    indicatorProvider = { mode ->
                        when (mode) {
                            CsqttConstants.VkAuth.MODE_CAPTCHA -> ModeIndicator(progress = 0.30f, color = Color(0xFFE53935))
                            CsqttConstants.VkAuth.MODE_CALLS -> ModeIndicator(progress = 0.78f, color = Color(0xFF43A047))
                            CsqttConstants.VkAuth.MODE_AUTO_JS -> ModeIndicator(progress = 1.0f, color = Color(0xFF1475FD))
                            else -> null
                        }
                    },
                    onTitleInfo = { showWorkModeGeneralDialog = true },
                    onInfo = { mode -> showWorkModeDetailDialog = mode },
                    onSelected = ::requestWorkMode,
                )

                CompactDropdownSetting(
                    title = "Режим SRTP",
                    selectedKey = srtpMode,
                    options = listOf(
                        CsqttConstants.SrtpMode.WRAP to "WRAP-SRTP",
                        CsqttConstants.SrtpMode.DTLS to "DTLS-SRTP",
                        CsqttConstants.SrtpMode.AUTO to "Авто",
                    ),
                    enabled = true,
                    indicatorProvider = { mode ->
                        when (mode) {
                            CsqttConstants.SrtpMode.WRAP -> ModeIndicator(progress = 0.5f, color = Color(0xFF43A047))
                            CsqttConstants.SrtpMode.DTLS -> ModeIndicator(progress = 1.0f, color = Color(0xFF43A047))
                            CsqttConstants.SrtpMode.AUTO -> ModeIndicator(progress = 1.0f, color = Color(0xFF1475FD))
                            else -> null
                        }
                    },
                    onTitleInfo = null,
                    onInfo = null,
                    onSelected = ::applySrtpMode,
                )

                CompactDropdownSetting(
                    title = "Транспорт",
                    selectedKey = turnTransport,
                    options = listOf(
                        CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS to "TCP",
                        CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT to "UDP",
                    ),
                    enabled = true,
                    indicatorProvider = { transport ->
                        when (transport) {
                            CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT -> ModeIndicator(
                                progress = 1.0f,
                                color = Color(0xFF1475FD),
                            )
                            CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS -> ModeIndicator(
                                progress = 0.70f,
                                color = Color(0xFF43A047),
                            )
                            else -> null
                        }
                    },
                    onTitleInfo = { showTurnTransportGeneralDialog = true },
                    onInfo = { transport -> showTurnTransportDetailDialog = transport },
                    onSelected = ::requestTurnTransport,
                )

                TunnelWorkersControl(
                    value = currentWorkers,
                    maximum = selectableMaxWorkers,
                    enabled = !tunnelRunning,
                    onValueChange = { value ->
                        val newWorkers = value.toInt()
                        scope.launch {
                            settingsStore.saveWorkersPerHash(newWorkers)
                        }
                    },
                    onInfo = { showWorkersInfoDialog = true },
                    fixedWorkers = effectiveServerWorkers,
                )

                AnimatedVisibility(
                    visible = vkAuthMode == CsqttConstants.VkAuth.MODE_CAPTCHA,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (autoCaptchaEnabled) "Авто капча" else "Ручная капча",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = autoCaptchaEnabled,
                                enabled = !tunnelRunning,
                                onCheckedChange = { enabled ->
                                    autoCaptchaEnabled = enabled
                                    scope.launch {
                                        if (enabled) {
                                            settingsStore.saveCaptchaMode("auto")
                                            settingsStore.saveCaptchaSolveMethod("auto")
                                        } else {
                                            val mode = if (useWVCaptcha) "wv" else "rjs"
                                            settingsStore.saveCaptchaMode(mode)
                                            settingsStore.saveCaptchaSolveMethod(if (mode == "wv" && isManualMode) "manual" else "auto")
                                        }
                                    }
                                }
                            )
                        }

                        AnimatedVisibility(
                            visible = !autoCaptchaEnabled,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Метод обхода капчи",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ProtocolChip("WBV", useWVCaptcha, enabled = !tunnelRunning) {
                                            useWVCaptcha = true
                                            isManualMode = wbvManualMode
                                            scope.launch {
                                                settingsStore.saveCaptchaMode("wv")
                                                settingsStore.saveCaptchaSolveMethod(if (wbvManualMode) "manual" else "auto")
                                            }
                                        }
                                        ProtocolChip("RJS", !useWVCaptcha, enabled = !tunnelRunning, isError = false) {
                                            useWVCaptcha = false
                                            isManualMode = false
                                            scope.launch {
                                                settingsStore.saveCaptchaMode("rjs")
                                                settingsStore.saveCaptchaSolveMethod("auto")
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Режим обхода",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (useWVCaptcha) {
                                            ProtocolChip(
                                                "РУЧ",
                                                isManualMode,
                                                enabled = !tunnelRunning,
                                                isError = false
                                            ) {
                                                isManualMode = true
                                                wbvManualMode = true
                                                scope.launch { settingsStore.saveWbvCaptchaSolveMethod("manual") }
                                            }
                                            ProtocolChip(
                                                "АВТ",
                                                !isManualMode,
                                                enabled = !tunnelRunning,
                                                isError = false
                                            ) {
                                                isManualMode = false
                                                wbvManualMode = false
                                                scope.launch { settingsStore.saveWbvCaptchaSolveMethod("auto") }
                                            }
                                        } else {
                                            ProtocolChip(
                                                "АВТ",
                                                selected = true,
                                                enabled = false,
                                                isError = false
                                            ) {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            }

            if (!participantMode) {
                OutlinedButton(
                    onClick = { showSecretsDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = CsqttShapes.Pill,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (passwordRequiredError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.Black,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Авторизация", fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }

            if (showHashModeGeneralDialog) {
                HashModeInfoDialog(mode = null, onDismiss = { showHashModeGeneralDialog = false })
            }
            showHashModeDetailDialog?.let { mode ->
                HashModeInfoDialog(mode = mode, onDismiss = { showHashModeDetailDialog = null })
            }

            if (showWorkModeGeneralDialog) {
                WorkModeInfoDialog(mode = null, onDismiss = { showWorkModeGeneralDialog = false })
            }
            showWorkModeDetailDialog?.let { mode ->
                WorkModeInfoDialog(mode = mode, onDismiss = { showWorkModeDetailDialog = null })
            }
            if (showAutoJsRiskDialog) {
                AutoVkRiskDialog(
                    requireAcknowledgement = true,
                    onAcknowledge = {
                        showAutoJsRiskDialog = false
                        pendingAutoJsSelection?.invoke()
                        pendingAutoJsSelection = null
                    },
                    onDoNotRemind = {
                        scope.launch { settingsStore.saveAutoJsRiskAcknowledged(true) }
                        showAutoJsRiskDialog = false
                        pendingAutoJsSelection?.invoke()
                        pendingAutoJsSelection = null
                    },
                )
            }
            if (showWorkersInfoDialog) {
                WorkersInfoDialog(onDismiss = { showWorkersInfoDialog = false })
            }
            if (showTurnTransportGeneralDialog) {
                TurnTransportInfoDialog(mode = null, onDismiss = { showTurnTransportGeneralDialog = false })
            }
            showTurnTransportDetailDialog?.let { transport ->
                TurnTransportInfoDialog(mode = transport, onDismiss = { showTurnTransportDetailDialog = null })
            }
            if (showTcpTransportRiskDialog) {
                TcpTransportRiskDialog(
                    onAcknowledge = {
                        showTcpTransportRiskDialog = false
                        pendingTcpTransportSelection?.invoke()
                        pendingTcpTransportSelection = null
                    },
                    onDoNotRemind = {
                        scope.launch { settingsStore.saveTcpTransportRiskAcknowledged(true) }
                        showTcpTransportRiskDialog = false
                        pendingTcpTransportSelection?.invoke()
                        pendingTcpTransportSelection = null
                    },
                )
            }
            if (showSecretsDialog) {
                SecretsDialog(
                    settingsStore = settingsStore,
                    initialPassword = connectionPassword,
                    onSaved = { showSecretsDialog = false },
                    onDismiss = { showSecretsDialog = false },
                )
            }
        }
    }
}
}

@Composable
private fun VkHashAuthControls(
    tunnelRunning: Boolean,
    tokenActive: Boolean,
    tokenFromLink: Boolean,
    isError: Boolean,
    onLogin: () -> Unit,
    onRevokeToken: () -> Unit,
) {
    if (tokenFromLink) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.height(44.dp),
            shape = CsqttShapes.Pill,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = MaterialTheme.colorScheme.primary,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Text("Из ссылки", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    } else if (tokenActive) {
        OutlinedButton(
            onClick = onRevokeToken,
            enabled = true,
            modifier = Modifier.height(44.dp),
            shape = CsqttShapes.Pill,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Text("Активно", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    } else if (isError) {
        OutlinedButton(
            onClick = onLogin,
            modifier = Modifier.height(44.dp),
            shape = CsqttShapes.Pill,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            Text("Вход", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    } else {
        Button(
            onClick = onLogin,
            enabled = true,
            modifier = Modifier.height(44.dp),
            shape = CsqttShapes.Pill,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        ) {
            Text("Вход", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun VkHashModeControls(
    hashSettingsLoaded: Boolean,
    autoHashMode: Boolean,
    savedHashMode: String?,
    vkTokenActive: Boolean,
    tunnelRunning: Boolean,
    filledHashCount: Int,
    hasInputHashErrors: Boolean,
    hashErrorTexts: List<String>,
    onOpenHashes: () -> Unit,
    onTitleInfo: () -> Unit,
    onInfo: (String) -> Unit,
    onSelected: (String) -> Unit,
    onLogin: () -> Unit,
    onRevokeToken: () -> Unit,
    authorizationRequiredError: Boolean = false,
    manualAllowed: Boolean = true,
    tokenFromLink: Boolean = false,
) {
    if (!hashSettingsLoaded) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!autoHashMode) {
            OutlinedButton(
                onClick = onOpenHashes,
                modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
                shape = CsqttShapes.Pill,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = BorderStroke(
                    1.dp,
                    if (hasInputHashErrors) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Filled.Phone, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Ссылки VK звонков",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else if (tokenFromLink) {
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
                shape = CsqttShapes.Pill,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = MaterialTheme.colorScheme.primary,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Filled.Link, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Из ссылки", fontWeight = FontWeight.SemiBold)
            }
        } else if (vkTokenActive) {
            OutlinedButton(
                onClick = onRevokeToken,
                enabled = true,
                modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
                shape = CsqttShapes.Pill,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            ) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                Text("Сессия VK активна", fontWeight = FontWeight.SemiBold)
            }
        } else if (authorizationRequiredError) {
            OutlinedButton(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
                shape = CsqttShapes.Pill,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.AutoMirrored.Filled.Login, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Вход", fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onLogin,
                enabled = true,
                modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
                shape = CsqttShapes.Pill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1475FD),
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Login, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Вход", fontWeight = FontWeight.SemiBold)
            }
        }

        if (!autoHashMode && hashErrorTexts.isNotEmpty()) {
            Text(
                text = hashErrorTexts.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    CompactDropdownSetting(
        title = "Режим хешей",
        selectedKey = if (!manualAllowed && savedHashMode == CsqttConstants.VkAutoHash.MODE_MANUAL) {
            CsqttConstants.VkAutoHash.MODE_AUTO_API
        } else {
            savedHashMode ?: CsqttConstants.VkAutoHash.MODE_MANUAL
        },
        options = listOf(
            CsqttConstants.VkAutoHash.MODE_MANUAL to "Ручной",
            CsqttConstants.VkAutoHash.MODE_AUTO_API to "Авто API",
            CsqttConstants.VkAutoHash.MODE_AUTO_JS to "Авто ВК",
        ).filterNot { (mode, _) -> !manualAllowed && mode == CsqttConstants.VkAutoHash.MODE_MANUAL },
        enabled = true,
        indicatorProvider = { mode ->
            when (mode) {
                CsqttConstants.VkAutoHash.MODE_MANUAL -> ModeIndicator(progress = 0.78f, color = Color(0xFF43A047))
                CsqttConstants.VkAutoHash.MODE_AUTO_API -> ModeIndicator(progress = 1.0f, color = Color(0xFF1475FD))
                CsqttConstants.VkAutoHash.MODE_AUTO_JS -> ModeIndicator(progress = 1.0f, color = Color(0xFF1475FD))
                else -> null
            }
        },
        onTitleInfo = onTitleInfo,
        onInfo = onInfo,
        onSelected = onSelected,
        leadingContent = null,
    )
}
