package com.csqtt.client.ui.v2.screens.connection

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import com.csqtt.client.ui.legacy.dialogs.QrScannerDialog
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ConnectionSource
import com.csqtt.client.ConnectionStartBlocker
import com.csqtt.client.ConnectionStartPolicy
import com.csqtt.client.CsqttConstants
import com.csqtt.client.R
import com.csqtt.client.SettingsStore
import com.csqtt.client.StoredSecretState
import com.csqtt.client.TunnelAuthSnapshot
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelService
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.defaultProfileName
import com.csqtt.client.newTunnelSessionId
import com.csqtt.client.resolveConnectionSource
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.v2.components.V2Card
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2FlowerButton
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2TopBar
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

private val ProfileCardRowHeight = 48.dp
private val ProfileCardVerticalPadding = 6.dp
private val ProfileCardHeight = ProfileCardRowHeight + ProfileCardVerticalPadding * 2
private val ProfileMenuBottomInset = ProfileCardHeight + V2Spacing.Sm + V2Spacing.Xs

@Composable
fun V2ConnectionScreen(
    settingsStore: SettingsStore,
    version: String,
    onSettings: () -> Unit,
    onInvalidConfiguration: () -> Unit,
    modifier: Modifier = Modifier,
    profilePickerRequest: Int = 0,
    onProfilePickerRequestHandled: () -> Unit = {},
    onVersionClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val colors = V2Theme.colors

    val tunnelAuthSettings by settingsStore.tunnelAuthSnapshot.collectAsStateWithLifecycle(
        initialValue = TunnelAuthSnapshot(),
    )
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val tunnelStarting by TunnelManager.starting.collectAsStateWithLifecycle()
    val tunnelStopping by TunnelManager.stopping.collectAsStateWithLifecycle()
    val autoPausedForWifi by TunnelManager.autoPausedForWifi.collectAsStateWithLifecycle()
    val cooldownActive by TunnelManager.cooldownActive.collectAsStateWithLifecycle()
    val uptimeSeconds by TunnelManager.uptimeSeconds.collectAsStateWithLifecycle()
    val tunnelBusy = tunnelRunning || tunnelStarting || tunnelStopping || autoPausedForWifi

    val peer by settingsStore.peer.collectAsStateWithLifecycle(initialValue = "")
    val vkHashes by settingsStore.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val vkHashCheckResultsJson by settingsStore.vkHashCheckResults.collectAsStateWithLifecycle(initialValue = "{}")
    val serverPeerPort by settingsStore.serverPeerPort.collectAsStateWithLifecycle(initialValue = 46000)
    val manualPortsEnabled by settingsStore.manualPortsEnabled.collectAsStateWithLifecycle(initialValue = false)
    val vkAuthMode by settingsStore.vkAuthMode.collectAsStateWithLifecycle(initialValue = "vkcalls")
    val savedHashMode by settingsStore.vkHashMode.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkHashMode)
    val savedVkAccessToken by settingsStore.vkAccessToken.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkAccessToken)
    val occupiedProfiles by settingsStore.occupiedProfiles.collectAsStateWithLifecycle(initialValue = emptySet())
    val deployServers by settingsStore.deployServers.collectAsStateWithLifecycle(initialValue = emptyList())

    val activeProfile by settingsStore.activeProfile.collectAsStateWithLifecycle(initialValue = 0)
    val profileNames by settingsStore.profileNames.collectAsStateWithLifecycle(initialValue = emptyMap())
    val profileSubscriptions by settingsStore.profileSubscriptions.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    var showProfileDialog by rememberSaveable { mutableStateOf(false) }
    var editingProfile by rememberSaveable { mutableStateOf<Int?>(null) }
    var creatingProfile by rememberSaveable { mutableStateOf(false) }
    var pendingStartAfterVpnPermission by rememberSaveable { mutableStateOf(false) }
    var showAddChoiceDialog by rememberSaveable { mutableStateOf(false) }

    var showQrScanner by rememberSaveable { mutableStateOf(false) }

    var subscriptionInitialUrl by rememberSaveable { mutableStateOf("") }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            showQrScanner = true
        } else {
            context.showRaisedToast(
                "Требуется разрешение на камеру для сканирования QR-кода",
                Toast.LENGTH_SHORT,
            )
        }
    }

    fun launchQrScanner() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            showQrScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var showSubscriptionDialog by rememberSaveable { mutableStateOf(false) }
    var showNoHashesAlert by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(profilePickerRequest) {
        if (profilePickerRequest > 0) {
            showProfileDialog = true
            onProfilePickerRequestHandled()
        }
    }

    LaunchedEffect(occupiedProfiles, activeProfile, deployServers, tunnelBusy) {
        if (tunnelBusy) return@LaunchedEffect
        val activeIsDeployServer = deployServers.any { it.profile == activeProfile }
        if (!activeIsDeployServer && occupiedProfiles.isNotEmpty() && activeProfile !in occupiedProfiles) {
            occupiedProfiles.minOrNull()?.let { settingsStore.saveActiveProfile(it) }
        }
    }

    val csqttLinkMode = tunnelAuthSettings.csqttLinkMode
    val connectionPassword = tunnelAuthSettings.connectionPassword
    val parsedLink = remember(tunnelAuthSettings.csqttLink) {
        parseCsqttLink(tunnelAuthSettings.csqttLink)
    }
    val linkHashes = parsedLink?.hashes.orEmpty()
    val linkTokenActive = csqttLinkMode && !parsedLink?.vkAccessToken.isNullOrEmpty()

    val rawManualHashes = remember(vkHashes) {
        vkHashes.split(Regex("[,\\s\\n]+"))
            .map(String::trim)
            .filter { it.length >= 16 }
            .distinct()
    }
    val activeManualHashes = remember(rawManualHashes, vkHashCheckResultsJson) {
        VkHashValidationCodec.active(rawManualHashes, VkHashValidationCodec.decode(vkHashCheckResultsJson))
    }
    val manualHashes = if (activeManualHashes.isNotEmpty()) activeManualHashes else rawManualHashes

    val accountAutoJsMode = vkAuthMode == "auto_js"
    val hashSettingsLoaded = savedHashMode != null && savedVkAccessToken != null
    val autoOrInteractive = accountAutoJsMode || (savedHashMode != null && savedHashMode != "manual")
    val hashesReady = savedVkAccessToken?.isNotBlank() == true
    val readyVkCredentials = hashSettingsLoaded && ConnectionStartPolicy.hasReadyVkCredentials(
        autoOrInteractive,
        linkHashes.size,
        linkTokenActive,
        autoOrInteractive,
        hashesReady,
        manualHashes.size,
    )
    val peerPortValid = !manualPortsEnabled || serverPeerPort in 1..65535
    val trimmedPeer = peer.trim()
    val isLocalOrUnspecifiedPeer = trimmedPeer == "0.0.0.0" || trimmedPeer == "127.0.0.1" || trimmedPeer.equals("localhost", ignoreCase = true) || trimmedPeer == "::1"

    val startBlocker = ConnectionStartPolicy.blocker(
        csqttLinkMode,
        parsedLink != null,
        trimmedPeer.isNotBlank() && !trimmedPeer.contains(":") && !isLocalOrUnspecifiedPeer,
        peerPortValid,
        connectionPassword.isNotBlank(),
        tunnelAuthSettings.connectionPasswordState != StoredSecretState.Unreadable,
        readyVkCredentials,
    )
    val configurationValid = startBlocker == null

    // --- tunnel start/stop (transferred from legacy ConnectionTab) ---
    fun startTunnelService() {
        scope.launch {
            val source = resolveConnectionSource(settingsStore) ?: run {
                context.showRaisedToast("Ошибка разрешения источника подключения", Toast.LENGTH_SHORT)
                return@launch
            }
            TunnelManager.isLoggingEnabled = settingsStore.loggingEnabled.first()
            val nextGen = System.currentTimeMillis() / 1000L
            val salt = newTunnelSessionId()
            val intent = Intent(context, TunnelService::class.java).apply {
                action = "START"
                putExtra("peer", source.peer)
                putExtra("vk_hashes", source.hashes)
                putExtra("vk_hashes_from_link", source.hashesFromLink)
                putExtra("vk_access_token", source.vkAccessToken)
                putExtra("vk_token_from_link", source.tokenFromLink)
                putExtra("workers_per_hash", settingsStore.workersPerHash.first())
                putExtra("port", 0)
                putExtra("sni", settingsStore.sni.first())
                putExtra("connection_password", source.password)
                putExtra("vk_auth_mode", vkAuthMode)
                putExtra("captcha_mode", settingsStore.captchaMode.first())
                putExtra("captcha_solve_method", settingsStore.captchaSolveMethod.first())
                putExtra("fingerprint", settingsStore.selectedFingerprint.first())
                putExtra("client_ids", settingsStore.activeClientIds.first())
                putExtra("obfs_mode", CsqttConstants.Tunnel.DEFAULT_OBFS_MODE)
                putExtra("turn_transport", settingsStore.turnTransport.first())
                putExtra("srtp_mode", settingsStore.srtpMode.first())
                putExtra("custom_turn_ips", settingsStore.customTurnIps.first())
                putExtra("generation_id", nextGen)
                putExtra("session_salt", salt)
            }
            runCatching { context.startForegroundService(intent) }
                .onFailure { error ->
                    TunnelManager.updateLog(
                        "foreground_request_error",
                        "Android отклонил запуск VPN: ${error.message ?: error.javaClass.simpleName}",
                        99,
                        true,
                    )
                    Toast.makeText(
                        context,
                        "Android отклонил запуск VPN. Проверьте права приложения.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (!pendingStartAfterVpnPermission) return@rememberLauncherForActivityResult
        pendingStartAfterVpnPermission = false
        if (result.resultCode == Activity.RESULT_OK && VpnService.prepare(context) == null) {
            startTunnelService()
        } else {
            context.showRaisedToast("VPN-разрешение отклонено", Toast.LENGTH_SHORT)
        }
    }

    fun toggleTunnel() {
        if (tunnelRunning || tunnelStarting || autoPausedForWifi) {
            context.startService(Intent(context, TunnelService::class.java).apply { action = "STOP" })
            return
        }
        if (!configurationValid) {
            val blocker = requireNotNull(startBlocker)
            TunnelManager.updateLog(
                "connection_configuration_${blocker.name}",
                "[Конфигурация] Нельзя подключиться: ${blocker.message}",
                99,
                true,
            )
            if (blocker == ConnectionStartBlocker.StoredSecret) {
                scope.launch {
                    val reset = settingsStore.resetUnreadableConnectionPassword()
                    if (reset) {
                        context.showRaisedToast(
                            "Пароль повреждён. Введите его заново в профиле.",
                            Toast.LENGTH_LONG,
                        )
                    }
                    onInvalidConfiguration()
                }
                return
            }
            if (blocker == ConnectionStartBlocker.Hashes) {
                showNoHashesAlert = true
                return
            }
            onInvalidConfiguration()
            return
        }
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            pendingStartAfterVpnPermission = true
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startTunnelService()
        }
    }

    // Cooldown after an unexpected disconnect (same as legacy MainScreen).
    var wasActive by remember { mutableStateOf(tunnelRunning) }
    LaunchedEffect(tunnelRunning, autoPausedForWifi) {
        val isActive = tunnelRunning || autoPausedForWifi
        if (wasActive && !isActive && !tunnelStopping) {
            TunnelManager.startCooldown(CsqttConstants.Timeouts.VPN_PERMISSION_COOLDOWN_MS)
        }
        wasActive = isActive
    }

    BackHandler(enabled = showProfileDialog) {
        showProfileDialog = false
    }
    BackHandler(enabled = showAddChoiceDialog) {
        showAddChoiceDialog = false
    }

    // --- visuals ---
    val isStarting = tunnelStarting && !tunnelRunning && !tunnelStopping
    val canToggle = !tunnelStopping &&
        (tunnelRunning || isStarting || autoPausedForWifi || !cooldownActive || !configurationValid)
    val activeVisual = tunnelRunning || isStarting || autoPausedForWifi || tunnelStopping

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = V2Sizes.ScreenPadding),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            V2TopBar(
                version = version,
                onSettings = onSettings,
                onVersionClick = onVersionClick,
                actions = {
                    V2CircleIconButton(
                        iconPainter = painterResource(R.drawable.ic_v2_plus),
                        contentDescription = "Добавить профиль",
                        onClick = { showAddChoiceDialog = true },
                    )
                },
            )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            V2FlowerButton(
                connected = activeVisual,
                connecting = isStarting,
                enabled = canToggle,
                onClick = { toggleTunnel() },
            )
            Text(
                text = connectionHeadline(
                    running = tunnelRunning,
                    starting = isStarting,
                    stopping = tunnelStopping,
                    autoPaused = autoPausedForWifi,
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = V2Spacing.Xxl),
            )
            Text(
                text = connectionHint(
                    running = tunnelRunning,
                    starting = isStarting,
                    stopping = tunnelStopping,
                    autoPaused = autoPausedForWifi,
                    cooldown = cooldownActive,
                    uptimeSeconds = uptimeSeconds,
                    configurationValid = configurationValid,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = V2Spacing.Xs),
            )
        }

            V2ProfileCard(
                name = if (activeProfile in occupiedProfiles) {
                    profileDisplayName(activeProfile, profileNames, profileSubscriptions)
                } else {
                    "Нет профилей"
                },
                profileIndex = activeProfile,
                expanded = showProfileDialog,
                onClick = { showProfileDialog = !showProfileDialog },
                modifier = Modifier.padding(bottom = V2Spacing.Sm),
            )
        }

        if (showProfileDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showProfileDialog = false },
                    ),
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val menuMaxHeight = (
                maxHeight - V2Sizes.TopBarHeight - ProfileMenuBottomInset - V2Spacing.Sm
            ).coerceAtLeast(120.dp)
            AnimatedVisibility(
                visible = showProfileDialog,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = ProfileMenuBottomInset),
                enter = fadeIn(animationSpec = tween(180)) +
                        slideInVertically(
                            initialOffsetY = { it / 4 },
                            animationSpec = tween(180),
                        ),
                exit = fadeOut(animationSpec = tween(140)) +
                        slideOutVertically(
                            targetOffsetY = { it / 6 },
                            animationSpec = tween(140),
                        ),
            ) {
                V2ProfilePickerMenu(
                    modifier = Modifier.fillMaxWidth(),
                    maxHeight = menuMaxHeight,
                    settingsStore = settingsStore,
                    activeProfile = activeProfile,
                    profileNames = profileNames,
                    occupiedProfiles = occupiedProfiles,
                    profileSubscriptions = profileSubscriptions,
                    tunnelBusy = tunnelBusy,
                    onDismiss = { showProfileDialog = false },
                    onEditProfile = {
                        creatingProfile = false
                        editingProfile = it
                    },
                )
            }
        }
    }

    V2AddProfileMenu(
        expanded = showAddChoiceDialog,
        onDismissRequest = { showAddChoiceDialog = false },
        onScanQr = {
            showAddChoiceDialog = false
            launchQrScanner()
        },
        onManual = {
            showAddChoiceDialog = false
            scope.launch {
                val slot = settingsStore.findFirstFreeProfile()
                if (slot != null) {
                    creatingProfile = true
                    editingProfile = slot
                } else {
                    context.showRaisedToast("Нет свободных профилей", Toast.LENGTH_SHORT)
                }
            }
        },
        onFromClipboard = {
            showAddChoiceDialog = false
            scope.launch {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val clip = clipboard?.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0)?.text?.toString().orEmpty().trim()
                } else {
                    ""
                }
                val parsed = parseCsqttLink(text)
                if (parsed == null) {
                    context.showRaisedToast("В буфере нет ссылки csqtt://", Toast.LENGTH_SHORT)
                    return@launch
                }
                val duplicateSlot = settingsStore.knownProfileEndpoints()[parsed.endpoint()]
                if (duplicateSlot != null) {
                    context.showRaisedToast(
                        "Этот сервер уже добавлен: ${profileNames[duplicateSlot] ?: defaultProfileName(duplicateSlot)}",
                        Toast.LENGTH_SHORT,
                    )
                    return@launch
                }
                val slot = settingsStore.findFirstFreeProfile()
                if (slot == null) {
                    context.showRaisedToast("Нет свободных профилей", Toast.LENGTH_SHORT)
                    return@launch
                }
                if (!settingsStore.saveImportedLink(slot, text)) {
                    context.showRaisedToast(
                        "В ссылке нет пароля подключения",
                        Toast.LENGTH_LONG,
                    )
                    return@launch
                }
                context.showRaisedToast(
                    "Добавлен профиль: ${parsed.host}",
                    Toast.LENGTH_SHORT,
                )
            }
        },
        onFromSubscription = {
            showAddChoiceDialog = false
            if (tunnelBusy) {
                context.showRaisedToast(
                    "Остановите туннель, чтобы импортировать подписку",
                    Toast.LENGTH_SHORT,
                )
            } else {
                showSubscriptionDialog = true
            }
        },
    )

    if (showSubscriptionDialog) {
        V2SubscriptionImportDialog(
            settingsStore = settingsStore,
            initialUrl = subscriptionInitialUrl,
            onDismiss = {
                showSubscriptionDialog = false
                subscriptionInitialUrl = ""
            },
            onImported = { profile, summary ->
                showSubscriptionDialog = false
                subscriptionInitialUrl = ""
                context.showRaisedToast(summary, Toast.LENGTH_LONG)
                scope.launch { settingsStore.saveActiveProfile(profile) }
            },
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onDismissRequest = { showQrScanner = false },
            onQrCodeScanned = { rawLink ->
                showQrScanner = false
                val text = rawLink.trim()
                val parsed = parseCsqttLink(text)
                if (parsed != null) {
                    scope.launch {
                        val duplicateSlot = settingsStore.knownProfileEndpoints()[parsed.endpoint()]
                        if (duplicateSlot != null) {
                            context.showRaisedToast(
                                "Этот сервер уже добавлен: ${profileNames[duplicateSlot] ?: defaultProfileName(duplicateSlot)}",
                                Toast.LENGTH_SHORT,
                            )
                            return@launch
                        }
                        val slot = settingsStore.findFirstFreeProfile()
                        if (slot == null) {
                            context.showRaisedToast("Нет свободных профилей", Toast.LENGTH_SHORT)
                            return@launch
                        }
                        if (!settingsStore.saveImportedLink(slot, text)) {
                            context.showRaisedToast(
                                "В QR-ссылке нет пароля подключения",
                                Toast.LENGTH_LONG,
                            )
                            return@launch
                        }
                        context.showRaisedToast(
                            "Добавлен профиль из QR: ${parsed.host}",
                            Toast.LENGTH_SHORT,
                        )
                    }
                } else if (text.startsWith("http://") || text.startsWith("https://")) {
                    scope.launch {
                        if (tunnelBusy) {
                            context.showRaisedToast(
                                "Остановите туннель, чтобы импортировать подписку",
                                Toast.LENGTH_SHORT,
                            )
                        } else {
                            subscriptionInitialUrl = text
                            showSubscriptionDialog = true
                        }
                    }
                } else {
                    context.showRaisedToast("QR-код не содержит ссылку FOCSQ", Toast.LENGTH_SHORT)
                }
            },
        )
    }

    if (showNoHashesAlert) {
        V2DialogContainer(
            title = "Нет VK-хешей",
            subtitle = "Нельзя подключиться",
            icon = Icons.Filled.VpnKey,
            onDismiss = { showNoHashesAlert = false },
            actions = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Отмена",
                        onClick = { showNoHashesAlert = false },
                        style = V2PillStyle.Outline,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                    V2PillButton(
                        text = "Настройки",
                        onClick = {
                            showNoHashesAlert = false
                            onSettings()
                        },
                        style = V2PillStyle.Contrast,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                }
            },
        ) {
            Text(
                text = "Для подключения нужен действующий VK-токен или хеши. " +
                    "Откройте настройки и добавьте хеши из ссылки либо включите авто-режим.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                lineHeight = 20.sp,
            )
        }
    }

    editingProfile?.let { prof ->
        androidx.compose.ui.window.Dialog(onDismissRequest = {
            editingProfile = null
            creatingProfile = false
        }) {
            V2EditProfileModal(
                settingsStore = settingsStore,
                profile = prof,
                profileName = profileNames[prof] ?: defaultProfileName(prof),
                isNew = creatingProfile,
                onSaved = { savedProfile ->
                    if (creatingProfile && !tunnelBusy) {
                        scope.launch { settingsStore.saveActiveProfile(savedProfile) }
                    }
                },
                onDismiss = {
                    editingProfile = null
                    creatingProfile = false
                },
            )
        }
    }
}

private fun profileDisplayName(
    slot: Int,
    profileNames: Map<Int, String>,
    profileSubscriptions: Map<Int, com.csqtt.client.ProfileSubscriptionInfo>,
): String {
    val node = profileNames[slot] ?: defaultProfileName(slot)
    val info = profileSubscriptions[slot] ?: return node
    val title = subscriptionGroupTitle(info)
    return when {
        node == defaultProfileName(slot) -> title
        node.equals(title, ignoreCase = true) -> title
        else -> "$title — $node"
    }
}

private fun connectionHeadline(
    running: Boolean,
    starting: Boolean,
    stopping: Boolean,
    autoPaused: Boolean,
): String = when {
    stopping -> "Отключение..."
    starting -> "Подключение..."
    autoPaused -> "Пауза"
    running -> "Подключено"
    else -> "Отключено"
}

private fun connectionHint(
    running: Boolean,
    starting: Boolean,
    stopping: Boolean,
    autoPaused: Boolean,
    cooldown: Boolean,
    uptimeSeconds: Long?,
    configurationValid: Boolean,
): String = when {
    stopping -> "Остановка туннеля..."
    starting -> "Подключение..."
    autoPaused -> "Приостановлено (подключён домашний Wi-Fi)"
    running -> "Активно: ${formatTunnelUptime(uptimeSeconds)}"
    cooldown -> "Ожидание после сброса сети..."
    !configurationValid -> "Проверьте профиль перед подключением"
    else -> "Нажмите для подключения"
}

private fun formatTunnelUptime(seconds: Long?): String {
    if (seconds == null || seconds <= 0L) return "00:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

@Composable
private fun V2ProfileCard(
    name: String,
    profileIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
) {
    val colors = V2Theme.colors
    val displayName = name.ifBlank { defaultProfileName(profileIndex) }
    V2Card(
        modifier = modifier,
        shape = V2Shapes.Hero,
        color = colors.bottomSurface,
        contentPadding = PaddingValues(
            horizontal = 18.dp,
            vertical = ProfileCardVerticalPadding,
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ProfileCardRowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_v2_flame),
                contentDescription = null,
                tint = colors.textPrimary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = V2DisplayFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 0.5.sp,
                ),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                contentDescription = "Выбор профиля",
                tint = colors.textPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
