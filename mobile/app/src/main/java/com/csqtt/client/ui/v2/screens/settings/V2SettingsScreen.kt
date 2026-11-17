package com.csqtt.client.ui.v2.screens.settings

import com.csqtt.client.ui.v2.dialogs.V2AutoVkRiskDialog
import com.csqtt.client.ui.v2.dialogs.V2HashesDialog
import com.csqtt.client.ui.v2.dialogs.V2ExtraWorkersInfoDialog
import com.csqtt.client.ui.v2.dialogs.V2HashModeInfoDialog
import com.csqtt.client.ui.v2.dialogs.V2ImportExportDialog
import com.csqtt.client.ui.v2.dialogs.V2TcpTransportRiskDialog
import com.csqtt.client.ui.v2.dialogs.V2TurnTransportInfoDialog
import com.csqtt.client.ui.v2.dialogs.V2VkTokenRevokeDialog
import com.csqtt.client.ui.v2.dialogs.V2WifiAutoPauseInfoDialog
import com.csqtt.client.ui.v2.dialogs.V2WifiNetworksDialog
import com.csqtt.client.ui.v2.dialogs.V2WorkModeInfoDialog

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.CsqttConstants
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelAuthSnapshot
import com.csqtt.client.TunnelManager
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.VkHashValidator
import com.csqtt.client.showRaisedToast
import com.csqtt.client.shouldConfirmAutoJsMode
import com.csqtt.client.shouldConfirmTcpTransport
import com.csqtt.client.vkAuthModeForHashMode
import com.csqtt.client.ui.v2.dialogs.V2TurnSubnetsDialog
import com.csqtt.client.ui.legacy.utils.stripVkUrlStatic
import com.csqtt.client.ui.v2.components.V2ActionSetting
import com.csqtt.client.ui.v2.components.V2Card
import com.csqtt.client.ui.v2.components.V2DropdownPill
import com.csqtt.client.ui.v2.components.V2DropdownSetting
import com.csqtt.client.ui.v2.components.V2HeroCard
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2SettingRow
import com.csqtt.client.ui.v2.components.V2SquareIconButton
import com.csqtt.client.ui.v2.components.V2SwitchSetting
import com.csqtt.client.ui.v2.components.V2TopBar
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.v2.design.V2AccentFamily
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val fingerprintLabels = listOf(
    "chrome" to "Chrome",
    "safari" to "Safari",
    "firefox" to "Firefox",
    "edge" to "Edge",
    "opera" to "Opera",
)

@Composable
fun V2SettingsScreen(
    settingsStore: SettingsStore,
    version: String,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onVkAuthRequested: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val colors = V2Theme.colors

    val tunnelAuthSettings by settingsStore.tunnelAuthSnapshot.collectAsStateWithLifecycle(
        initialValue = TunnelAuthSnapshot(),
    )
    val activeProfile = tunnelAuthSettings.profile
    val csqttLinkMode = tunnelAuthSettings.csqttLinkMode
    val participantMode = csqttLinkMode

    val activeFingerprint by settingsStore.selectedFingerprint.collectAsStateWithLifecycle(
        initialValue = CsqttConstants.Tunnel.DEFAULT_FINGERPRINT,
    )
    val activeClientIds by settingsStore.activeClientIds.collectAsStateWithLifecycle(
        initialValue = CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS,
    )
    val vkHashCheckResultsJson by settingsStore.vkHashCheckResults.collectAsStateWithLifecycle(initialValue = "{}")
    val vkHashCheckResults = remember(vkHashCheckResultsJson) {
        VkHashValidationCodec.decode(vkHashCheckResultsJson)
    }
    val savedHashMode by settingsStore.vkHashMode.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkHashMode)
    val savedVkAccessToken by settingsStore.vkAccessToken.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkAccessToken)
    val autoJsRiskAcknowledged by settingsStore.autoJsRiskAcknowledged.collectAsStateWithLifecycle(initialValue = false)
    val tcpTransportRiskAcknowledged by settingsStore.tcpTransportRiskAcknowledged.collectAsStateWithLifecycle(initialValue = false)
    val turnTransport by settingsStore.turnTransport.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedTurnTransport ?: CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT,
    )
    val srtpMode by settingsStore.srtpMode.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedSrtpMode ?: CsqttConstants.SrtpMode.DEFAULT,
    )
    val loggingEnabled by settingsStore.loggingEnabled.collectAsStateWithLifecycle(initialValue = true)
    val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = CsqttConstants.Theme.MODE_SYSTEM)
    val autoPauseOnWifi by settingsStore.autoPauseOnWifi.collectAsStateWithLifecycle(initialValue = false)
    val autoPauseWifiSsids by settingsStore.autoPauseWifiSsids.collectAsStateWithLifecycle(
        initialValue = emptySet(),
    )
    val extraWorkers by settingsStore.extraWorkers.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedExtraWorkers ?: false,
    )
    val selectedTurnSubnet by settingsStore.selectedTurnSubnet.collectAsStateWithLifecycle(initialValue = "")
    val turnSubnetIpLimit by settingsStore.turnSubnetIpLimit.collectAsStateWithLifecycle(initialValue = "ALL")
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()

    var vkAuthMode by rememberSaveable { mutableStateOf(CsqttConstants.VkAuth.MODE_CALLS) }
    var vkHash1 by rememberSaveable { mutableStateOf("") }
    var vkHash2 by rememberSaveable { mutableStateOf("") }
    var vkHash3 by rememberSaveable { mutableStateOf("") }
    var vkHash4 by rememberSaveable { mutableStateOf("") }
    var vkHash5 by rememberSaveable { mutableStateOf("") }
    var vkHash6 by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable(activeProfile) { mutableStateOf(false) }
    var settingsRevision by remember { mutableStateOf(0) }

    var autoCaptchaEnabled by rememberSaveable(activeProfile) { mutableStateOf(true) }
    var useWVCaptcha by rememberSaveable(activeProfile) { mutableStateOf(false) }
    var isManualMode by rememberSaveable(activeProfile) { mutableStateOf(true) }
    var wbvManualMode by rememberSaveable(activeProfile) { mutableStateOf(true) }

    // dialogs
    var showHashesDialog by rememberSaveable { mutableStateOf(false) }
    var showImportExportDialog by rememberSaveable { mutableStateOf(false) }
    var showVkRevokeDialog by rememberSaveable { mutableStateOf(false) }
    var showHashModeGeneralDialog by rememberSaveable { mutableStateOf(false) }
    var showHashModeDetailDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showWorkModeGeneralDialog by rememberSaveable { mutableStateOf(false) }
    var showWorkModeDetailDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showTurnTransportGeneralDialog by rememberSaveable { mutableStateOf(false) }
    var showTurnTransportDetailDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showAutoPauseInfoDialog by rememberSaveable { mutableStateOf(false) }
    var showWifiNetworksDialog by rememberSaveable { mutableStateOf(false) }
    var showExtraWorkersInfoDialog by rememberSaveable { mutableStateOf(false) }
    var showTurnSubnetsDialog by rememberSaveable { mutableStateOf(false) }
    var showAutoJsRiskDialog by remember { mutableStateOf(false) }
    var showTcpTransportRiskDialog by remember { mutableStateOf(false) }
    var pendingAutoJsSelection by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingTcpTransportSelection by remember { mutableStateOf<(() -> Unit)?>(null) }

    suspend fun loadLocalSettings() {
        val hashes = settingsStore.vkHashes.first()
        vkAuthMode = settingsStore.vkAuthMode.first()
        val parts = hashes.split(Regex("[,\\s\\n]+")).map { stripVkUrlStatic(it) }.filter { it.isNotEmpty() }
        vkHash1 = parts.getOrElse(0) { "" }
        vkHash2 = parts.getOrElse(1) { "" }
        vkHash3 = parts.getOrElse(2) { "" }
        vkHash4 = parts.getOrElse(3) { "" }
        vkHash5 = parts.getOrElse(4) { "" }
        vkHash6 = parts.getOrElse(5) { "" }
        val captchaMode = settingsStore.captchaMode.first()
        val captchaMethod = settingsStore.captchaSolveMethod.first()
        val wbvMethod = settingsStore.captchaWbvSolveMethod.first()
        autoCaptchaEnabled = captchaMode == "auto"
        useWVCaptcha = captchaMode != "rjs"
        wbvManualMode = wbvMethod != "auto"
        isManualMode = if (captchaMode == "wv") wbvManualMode else captchaMethod != "auto"
        TunnelManager.isLoggingEnabled = settingsStore.loggingEnabled.first()
        initialized = true
    }

    LaunchedEffect(activeProfile) {
        if (initialized) return@LaunchedEffect
        loadLocalSettings()
    }

    LaunchedEffect(settingsRevision) {
        if (settingsRevision > 0) loadLocalSettings()
    }

    val allHashes = listOf(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6)
    val activeHashes = remember(allHashes, vkHashCheckResults) {
        VkHashValidationCodec.active(allHashes, vkHashCheckResults)
    }
    val filledHashCount = activeHashes.size

    val parsedCsqttLink = remember(tunnelAuthSettings.csqttLink) {
        parseCsqttLink(tunnelAuthSettings.csqttLink)
    }
    val linkHashes = parsedCsqttLink?.hashes.orEmpty()
    val linkVkAccessToken = parsedCsqttLink?.vkAccessToken.orEmpty()
    val linkTokenActive = participantMode && linkVkAccessToken.isNotEmpty()

    val hashSettingsLoaded = savedHashMode != null && savedVkAccessToken != null
    val effectiveHashMode = if (linkTokenActive && savedHashMode == CsqttConstants.VkAutoHash.MODE_MANUAL) {
        CsqttConstants.VkAutoHash.MODE_AUTO_API
    } else {
        savedHashMode
    }
    val effectiveVkAuthMode = if (
        participantMode && linkHashes.isNotEmpty() && vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
    ) {
        CsqttConstants.VkAuth.MODE_CALLS
    } else {
        vkAuthMode
    }
    val accountAutoJsMode = effectiveVkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
    val autoHashMode = accountAutoJsMode || (
        effectiveHashMode != null && effectiveHashMode != CsqttConstants.VkAutoHash.MODE_MANUAL
    )
    val vkTokenActive = savedVkAccessToken?.isNotBlank() == true

    val hashModeOptions = if (linkTokenActive) {
        listOf("Авто API", "Авто ВК")
    } else {
        listOf("Ручной", "Авто API", "Авто ВК")
    }
    val workModeOptions = if (participantMode && linkHashes.isNotEmpty()) {
        listOf("Капча", "Авто")
    } else {
        listOf("Капча", "Авто", "Авто ВК")
    }

    fun hashModeKey(label: String): String = when (label) {
        "Авто API" -> CsqttConstants.VkAutoHash.MODE_AUTO_API
        "Авто ВК" -> CsqttConstants.VkAutoHash.MODE_AUTO_JS
        else -> CsqttConstants.VkAutoHash.MODE_MANUAL
    }

    fun hashModeLabel(mode: String?): String = when (mode) {
        CsqttConstants.VkAutoHash.MODE_AUTO_API -> "Авто API"
        CsqttConstants.VkAutoHash.MODE_AUTO_JS -> "Авто ВК"
        else -> "Ручной"
    }

    fun workModeKey(label: String): String = when (label) {
        "Авто" -> CsqttConstants.VkAuth.MODE_CALLS
        "Авто ВК" -> CsqttConstants.VkAuth.MODE_AUTO_JS
        else -> CsqttConstants.VkAuth.MODE_CAPTCHA
    }

    fun workModeLabel(mode: String): String = when (mode) {
        CsqttConstants.VkAuth.MODE_CALLS -> "Авто"
        CsqttConstants.VkAuth.MODE_AUTO_JS -> "Авто ВК"
        else -> "Капча"
    }

    fun transportKey(label: String): String = if (label == "TCP") {
        CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS
    } else {
        CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT
    }

    fun applyWorkMode(mode: String) {
        vkAuthMode = mode
        scope.launch { settingsStore.saveVkAuthMode(mode) }
    }

    fun applyHashMode(mode: String) {
        val nextWorkMode = vkAuthModeForHashMode(mode, vkAuthMode)
        vkAuthMode = nextWorkMode
        scope.launch { settingsStore.saveVkHashMode(mode) }
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

    fun applyTurnTransport(transport: String) {
        scope.launch { settingsStore.saveTurnTransport(transport) }
    }

    fun requestTurnTransport(transport: String) {
        if (shouldConfirmTcpTransport(turnTransport, transport, tcpTransportRiskAcknowledged)) {
            pendingTcpTransportSelection = { applyTurnTransport(transport) }
            showTcpTransportRiskDialog = true
        } else {
            applyTurnTransport(transport)
        }
    }

    LaunchedEffect(effectiveVkAuthMode, extraWorkers) {
        if (effectiveVkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS && extraWorkers) {
            settingsStore.saveExtraWorkers(false)
        }
    }

    LaunchedEffect(initialized, participantMode, linkHashes, linkTokenActive, vkAuthMode, savedHashMode) {
        if (!initialized || !participantMode) return@LaunchedEffect
        when {
            linkHashes.isNotEmpty() && vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS ->
                applyWorkMode(CsqttConstants.VkAuth.MODE_CALLS)
            linkTokenActive && savedHashMode == CsqttConstants.VkAutoHash.MODE_MANUAL ->
                applyHashMode(CsqttConstants.VkAutoHash.MODE_AUTO_API)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = V2Sizes.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        V2TopBar(
            onBack = onBack,
            title = "Настройки",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = V2Spacing.Xxl),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            V2HeroCard(
                title = "FOCSQ",
                version = version,
                showGithub = true,
                onGithubClick = {
                    val url = CsqttConstants.Links.REPOSITORY
                    runCatching {
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            .let { context.startActivity(it) }
                    }
                },
            )

            V2SettingsSection("Подключение")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                V2PillButton(
                    text = "Владелец",
                    onClick = {
                        if (participantMode) {
                            scope.launch { settingsStore.saveCsqttLinkMode(false) }
                        }
                    },
                    style = if (participantMode) V2PillStyle.Outline else V2PillStyle.Contrast,
                    modifier = Modifier.weight(1f),
                    height = 44.dp,
                )
                V2PillButton(
                    text = "Участник",
                    onClick = {
                        if (!participantMode) {
                            scope.launch { settingsStore.saveCsqttLinkMode(true) }
                        }
                    },
                    style = if (participantMode) V2PillStyle.Contrast else V2PillStyle.Outline,
                    modifier = Modifier.weight(1f),
                    height = 44.dp,
                )
            }

            if (participantMode) {
                if (linkHashes.isNotEmpty()) {
                    V2SettingRow(title = "VK хеши получены: ${linkHashes.size}")
                } else if (linkTokenActive) {
                    V2SettingRow(title = "VK токен активен")
                }
            }

            if (effectiveVkAuthMode != CsqttConstants.VkAuth.MODE_AUTO_JS) {
                V2SwitchSetting(
                    title = "Экстра потоки",
                    checked = extraWorkers,
                    enabled = !tunnelRunning,
                    onCheckedChange = { checked ->
                        scope.launch { settingsStore.saveExtraWorkers(checked) }
                    },
                    onInfoClick = { showExtraWorkersInfoDialog = true },
                )
            }
            // Режим хешей + под-действие (соединять только для ручных хешей)
            if (hashSettingsLoaded && (!participantMode || linkHashes.isEmpty())) {
                val showHashSubAction = !participantMode || !linkTokenActive
                val joinWithManualHashes = !autoHashMode && showHashSubAction

                if (joinWithManualHashes) {
                    // Соединяем только для хешей (ручной режим)
                    V2Card(
                        shape = V2Shapes.Row,
                        color = colors.bottomSurface,
                        borderColor = null,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Режим хешей",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = V2BodyFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                ),
                                color = colors.textPrimary,
                            )
                            V2SquareIconButton(
                                icon = Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = "Подробнее",
                                onClick = { showHashModeGeneralDialog = true },
                                size = 30.dp,
                                iconSize = 16.dp,
                                shape = V2Shapes.Pill,
                                backgroundColor = Color.Transparent,
                                contentColor = colors.textMuted,
                            )
                            V2DropdownPill(
                                value = hashModeLabel(effectiveHashMode),
                                options = hashModeOptions,
                                onSelected = { label -> requestHashMode(hashModeKey(label)) },
                                onInfo = { label -> showHashModeDetailDialog = hashModeKey(label) },
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            thickness = 1.dp,
                            color = colors.field,
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { showHashesDialog = true },
                                )
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Ручные VK хеши",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = V2BodyFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                ),
                                color = colors.textPrimary,
                            )
                            Text(
                                text = if (filledHashCount > 0) "Заполнено: $filledHashCount" else "Настроить",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = V2BodyFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                ),
                                color = colors.textSecondary,
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(V2Sizes.IconSmall),
                            )
                        }
                    }
                } else {
                    // Режим хешей отдельной плашкой
                    V2Card(
                        shape = V2Shapes.Row,
                        color = colors.bottomSurface,
                        borderColor = null,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Режим хешей",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = V2BodyFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                ),
                                color = colors.textPrimary,
                            )
                            V2SquareIconButton(
                                icon = Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = "Подробнее",
                                onClick = { showHashModeGeneralDialog = true },
                                size = 30.dp,
                                iconSize = 16.dp,
                                shape = V2Shapes.Pill,
                                backgroundColor = Color.Transparent,
                                contentColor = colors.textMuted,
                            )
                            V2DropdownPill(
                                value = hashModeLabel(effectiveHashMode),
                                options = hashModeOptions,
                                onSelected = { label -> requestHashMode(hashModeKey(label)) },
                                onInfo = { label -> showHashModeDetailDialog = hashModeKey(label) },
                            )
                        }
                    }

                    // Для остальных режимов (Авто ВК / Интерактивный) выводим токен/авторизацию отдельной плашкой
                    if (showHashSubAction) {
                        if (vkTokenActive) {
                            V2Card(
                                shape = V2Shapes.Row,
                                color = colors.bottomSurface,
                                borderColor = null,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 60.dp)
                                        .padding(horizontal = 18.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "Сбросить VK токен",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontFamily = V2BodyFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                        ),
                                        color = colors.textPrimary,
                                    )
                                    V2PillButton(
                                        text = "Сбросить",
                                        onClick = { showVkRevokeDialog = true },
                                        style = V2PillStyle.Danger,
                                        height = 38.dp,
                                        horizontalPadding = 16.dp,
                                        fontSize = 14.sp,
                                    )
                                }
                            }
                        } else {
                            V2Card(
                                shape = V2Shapes.Row,
                                color = colors.bottomSurface,
                                borderColor = null,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 60.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = onVkAuthRequested,
                                        )
                                        .padding(horizontal = 18.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "VK авторизация",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontFamily = V2BodyFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                        ),
                                        color = colors.textPrimary,
                                    )
                                    Text(
                                        text = "Войти",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = V2BodyFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                        ),
                                        color = colors.textSecondary,
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Режим работы
            V2DropdownSetting(
                title = "Режим работы",
                value = workModeLabel(effectiveVkAuthMode),
                options = workModeOptions,
                onTitleInfo = { showWorkModeGeneralDialog = true },
                onInfo = { label -> showWorkModeDetailDialog = workModeKey(label) },
                onSelected = { label -> requestWorkMode(workModeKey(label)) },
            )

            // Маскировка
            V2DropdownSetting(
                title = "Маскировка",
                value = when (srtpMode) {
                    CsqttConstants.SrtpMode.WRAP -> "WRAP-SRTP"
                    CsqttConstants.SrtpMode.DTLS -> "DTLS-SRTP"
                    else -> "Авто"
                },
                options = listOf("WRAP-SRTP", "DTLS-SRTP", "Авто"),
                onSelected = { label ->
                    val mode = when (label) {
                        "WRAP-SRTP" -> CsqttConstants.SrtpMode.WRAP
                        "DTLS-SRTP" -> CsqttConstants.SrtpMode.DTLS
                        else -> CsqttConstants.SrtpMode.AUTO
                    }
                    scope.launch { settingsStore.saveSrtpMode(mode) }
                },
            )

            // Транспорт
            V2DropdownSetting(
                title = "Транспорт",
                value = if (turnTransport == CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS) "TCP" else "UDP",
                options = listOf("UDP", "TCP"),
                onTitleInfo = { showTurnTransportGeneralDialog = true },
                onInfo = { label -> showTurnTransportDetailDialog = transportKey(label) },
                onSelected = { label -> requestTurnTransport(transportKey(label)) },
            )

            if (effectiveVkAuthMode == CsqttConstants.VkAuth.MODE_CAPTCHA) {
                V2SwitchSetting(
                    title = if (autoCaptchaEnabled) "Авто капча" else "Ручная капча",
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
                    },
                )
                if (!autoCaptchaEnabled) {
                    V2DropdownSetting(
                        title = "Метод обхода капчи",
                        value = if (useWVCaptcha) "WBV" else "RJS",
                        options = listOf("WBV", "RJS"),
                        enabled = !tunnelRunning,
                        onSelected = { label ->
                            val useWv = label == "WBV"
                            useWVCaptcha = useWv
                            isManualMode = if (useWv) wbvManualMode else false
                            scope.launch {
                                settingsStore.saveCaptchaMode(if (useWv) "wv" else "rjs")
                                settingsStore.saveCaptchaSolveMethod(if (useWv && wbvManualMode) "manual" else "auto")
                            }
                        },
                    )
                    if (useWVCaptcha) {
                        V2DropdownSetting(
                            title = "Режим обхода",
                            value = if (isManualMode) "Ручной" else "Авто",
                            options = listOf("Ручной", "Авто"),
                            enabled = !tunnelRunning,
                            onSelected = { label ->
                                val manual = label == "Ручной"
                                isManualMode = manual
                                wbvManualMode = manual
                                scope.launch {
                                    settingsStore.saveWbvCaptchaSolveMethod(if (manual) "manual" else "auto")
                                }
                            },
                        )
                    } else {
                        V2SettingRow(title = "Режим обхода") {
                            Text(text = "Авто", color = V2Theme.colors.textSecondary)
                        }
                    }
                }
            }

            if (!participantMode) {
                V2ActionSetting(
                    title = "TURN подсети",
                    action = "Настроить",
                    onAction = { showTurnSubnetsDialog = true },
                )
            }

            // Отпечаток
            V2DropdownSetting(
                title = "Отпечаток",
                value = fingerprintLabels.firstOrNull { it.first == activeFingerprint }?.second ?: "Firefox",
                options = fingerprintLabels.map { it.second },
                onSelected = { label ->
                    fingerprintLabels.firstOrNull { it.second == label }?.first?.let { key ->
                        scope.launch { settingsStore.saveFingerprint(key) }
                    }
                },
            )

            V2SwitchSetting(
                title = "Автопауза при Wi-Fi",
                checked = autoPauseOnWifi,
                onCheckedChange = { checked ->
                    scope.launch { settingsStore.saveAutoPauseOnWifi(checked) }
                },
                onInfoClick = { showAutoPauseInfoDialog = true },
            )

            if (autoPauseOnWifi) {
                V2ActionSetting(
                    title = "Сети для паузы",
                    action = if (autoPauseWifiSsids.isEmpty()) {
                        "Все Wi-Fi"
                    } else {
                        "${autoPauseWifiSsids.size}"
                    },
                    onAction = { showWifiNetworksDialog = true },
                )
            }

            V2SettingsSection("Приложение")

            V2SwitchSetting(
                title = "Логи",
                checked = loggingEnabled,
                onCheckedChange = { checked ->
                    TunnelManager.isLoggingEnabled = checked
                    scope.launch { settingsStore.saveLoggingEnabled(checked) }
                },
            )

            V2DropdownSetting(
                title = "Тема оформления",
                value = when (themeMode) {
                    CsqttConstants.Theme.MODE_LIGHT -> "Светлая"
                    CsqttConstants.Theme.MODE_DARK -> "Тёмная"
                    else -> "Системная"
                },
                options = listOf("Системная", "Тёмная", "Светлая"),
                onSelected = { label ->
                    val mode = when (label) {
                        "Светлая" -> CsqttConstants.Theme.MODE_LIGHT
                        "Тёмная" -> CsqttConstants.Theme.MODE_DARK
                        else -> CsqttConstants.Theme.MODE_SYSTEM
                    }
                    scope.launch { settingsStore.saveThemeMode(mode) }
                },
            )

            V2ActionSetting(
                title = "Импорт и экспорт",
                action = "Открыть",
                onAction = { showImportExportDialog = true },
            )

            V2SettingRow(
                title = "О проекте",
                onClick = onAbout,
            ) {
                com.csqtt.client.ui.v2.components.V2Chevron()
            }

            Spacer(modifier = Modifier.height(V2Spacing.Xl))

            Text(
                text = "design by luminescq",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = V2AccentFamily),
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = V2Spacing.Sm),
            )
        }
    }

    if (showHashesDialog) {
        V2HashesDialog(
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
            onAutoSave = { h1: String, h2: String, h3: String, h4: String, h5: String, h6: String ->
                vkHash1 = stripVkUrlStatic(h1)
                vkHash2 = stripVkUrlStatic(h2)
                vkHash3 = stripVkUrlStatic(h3)
                vkHash4 = stripVkUrlStatic(h4)
                vkHash5 = stripVkUrlStatic(h5)
                vkHash6 = stripVkUrlStatic(h6)
                scope.launch {
                    settingsStore.saveVkHashesOnly(
                        listOf(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6)
                            .filter { it.isNotBlank() && it.length >= 16 }
                            .distinct()
                            .joinToString(","),
                    )
                }
            },
            onSave = { h1: String, h2: String, h3: String, h4: String, h5: String, h6: String ->
                vkHash1 = stripVkUrlStatic(h1)
                vkHash2 = stripVkUrlStatic(h2)
                vkHash3 = stripVkUrlStatic(h3)
                vkHash4 = stripVkUrlStatic(h4)
                vkHash5 = stripVkUrlStatic(h5)
                vkHash6 = stripVkUrlStatic(h6)
                scope.launch {
                    settingsStore.saveVkHashesOnly(
                        listOf(vkHash1, vkHash2, vkHash3, vkHash4, vkHash5, vkHash6)
                            .filter { it.isNotBlank() && it.length >= 16 }
                            .distinct()
                            .joinToString(","),
                    )
                }
            },
            onDismiss = { showHashesDialog = false },
        )
    }

    if (showImportExportDialog) {
        V2ImportExportDialog(
            settingsStore = settingsStore,
            tunnelRunning = tunnelRunning,
            onImported = { settingsRevision++ },
            onDismissRequest = { showImportExportDialog = false },
        )
    }

    if (showVkRevokeDialog) {
        V2VkTokenRevokeDialog(
            onCancel = { showVkRevokeDialog = false },
            onCopyToken = {
                val token = savedVkAccessToken.orEmpty()
                if (token.isNotBlank()) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("VK token", token))
                        context.showRaisedToast("Токен VK скопирован", Toast.LENGTH_SHORT)
                    }
                }
            },
            onRevokeToken = {
                showVkRevokeDialog = false
                scope.launch { settingsStore.clearVkAccessToken() }
            },
        )
    }

    if (showHashModeGeneralDialog) {
        V2HashModeInfoDialog(mode = null, onDismiss = { showHashModeGeneralDialog = false })
    }
    showHashModeDetailDialog?.let { mode ->
        V2HashModeInfoDialog(mode = mode, onDismiss = { showHashModeDetailDialog = null })
    }
    if (showWorkModeGeneralDialog) {
        V2WorkModeInfoDialog(mode = null, onDismiss = { showWorkModeGeneralDialog = false })
    }
    showWorkModeDetailDialog?.let { mode ->
        V2WorkModeInfoDialog(mode = mode, onDismiss = { showWorkModeDetailDialog = null })
    }
    if (showAutoPauseInfoDialog) {
        V2WifiAutoPauseInfoDialog(onDismiss = { showAutoPauseInfoDialog = false })
    }
    if (showWifiNetworksDialog) {
        V2WifiNetworksDialog(
            settingsStore = settingsStore,
            onDismiss = { showWifiNetworksDialog = false },
        )
    }
    if (showExtraWorkersInfoDialog) {
        V2ExtraWorkersInfoDialog(onDismiss = { showExtraWorkersInfoDialog = false })
    }
    if (showTurnSubnetsDialog) {
        V2TurnSubnetsDialog(
            selectedSubnetId = selectedTurnSubnet,
            turnSubnetIpLimit = turnSubnetIpLimit,
            onSelectSubnet = { id -> scope.launch { settingsStore.saveSelectedTurnSubnet(id) } },
            onSelectIpLimit = { limit -> scope.launch { settingsStore.saveTurnSubnetIpLimit(limit) } },
            onDismiss = { showTurnSubnetsDialog = false },
        )
    }
    if (showTurnTransportGeneralDialog) {
        V2TurnTransportInfoDialog(mode = null, onDismiss = { showTurnTransportGeneralDialog = false })
    }
    showTurnTransportDetailDialog?.let { transport ->
        V2TurnTransportInfoDialog(mode = transport, onDismiss = { showTurnTransportDetailDialog = null })
    }
    if (showAutoJsRiskDialog) {
        V2AutoVkRiskDialog(
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
    if (showTcpTransportRiskDialog) {
        V2TcpTransportRiskDialog(
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
}

@Composable
private fun V2SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = V2BodyFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
        ),
        color = V2Theme.colors.textMuted,
        modifier = Modifier.padding(start = 4.dp, top = V2Spacing.Xs),
    )
}
