@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.csqtt.client.ui.v2.screens.deploy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.csqtt.client.ui.v2.components.V2DialogContainer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.BuildConfig
import com.csqtt.client.CsqttConstants
import com.csqtt.client.DeployManager
import com.csqtt.client.DeployServerInfo
import com.csqtt.client.DeploySettingsSnapshot
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelService
import com.csqtt.client.showRaisedToast
import com.csqtt.client.isNewerVersion
import com.csqtt.client.ui.legacy.DEPLOY_HINT_MISSING_DEPLOY_OVERRIDES
import com.csqtt.client.ui.legacy.DEPLOY_HINT_MISSING_WEB_ENV
import com.csqtt.client.ui.legacy.DeployActionMode
import com.csqtt.client.ui.legacy.DeployInstance
import com.csqtt.client.ui.legacy.DeployPlan
import com.csqtt.client.ui.legacy.FoundServer
import com.csqtt.client.ui.legacy.PlanAction
import com.csqtt.client.ui.legacy.PlanKind
import com.csqtt.client.ui.legacy.ServerInventory
import com.csqtt.client.ui.legacy.ServerPlace
import com.csqtt.client.ui.legacy.bundledServerVersion
import com.csqtt.client.ui.legacy.comparableServerVersion
import com.csqtt.client.ui.legacy.deployInstance
import com.csqtt.client.ui.legacy.friendlyDeployError
import com.csqtt.client.ui.legacy.peerPortOrDefault
import com.csqtt.client.ui.legacy.performDeploy
import com.csqtt.client.ui.legacy.performServerCheck
import com.csqtt.client.ui.legacy.performUninstall
import com.csqtt.client.ui.legacy.planDeploy
import com.csqtt.client.ui.legacy.removalNotes
import com.csqtt.client.ui.legacy.removableServers
import com.csqtt.client.ui.v2.components.V2Card
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import androidx.compose.material.icons.filled.ContentCopy
import com.csqtt.client.ui.v2.components.V2TopBar
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Palette
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import com.csqtt.client.ui.v2.design.V2VersionFamily
import com.csqtt.client.ui.v2.dialogs.V2DeploySecretsDialog
import com.csqtt.client.ui.v2.dialogs.V2DockerInstallInfoDialog
import com.csqtt.client.ui.v2.dialogs.V2SshKeysDialog
import com.csqtt.client.ui.v2.dialogs.V2UninstallConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private fun isValidPort(value: String): Boolean = value.toIntOrNull() in 1..65535

private const val AUTO_INSPECT_INTERVAL_MS = 15 * 60 * 1000L

private data class EffectiveServerPorts(
    val ssh: Int,
    val peer: Int,
    val web: Int,
)

/** Draft values captured before the deploy options sheet seeds ports/runtime from a plan action. */
private data class DeployOptionsBackup(
    val manualPorts: Boolean,
    val peerPort: String,
    val webPort: String,
    val dockerInstall: Boolean,
) : java.io.Serializable

private data class DeployCredentials(
    val mainPass: String,
    val webLogin: String,
    val webPass: String,
)

private data class PendingDeployAfterSecrets(
    val mode: DeployActionMode,
    val instance: DeployInstance,
    val peerPort: Int?,
    val webPort: Int?,
)

private fun resolveServerPorts(
    manualPorts: Boolean,
    sshPort: String,
    peerPort: String,
    webPort: String,
): EffectiveServerPorts {
    if (!manualPorts) {
        return EffectiveServerPorts(
            ssh = CsqttConstants.Network.DEFAULT_SSH_PORT,
            peer = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT,
            web = CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT,
        )
    }
    return EffectiveServerPorts(
        ssh = sshPort.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: CsqttConstants.Network.DEFAULT_SSH_PORT,
        peer = peerPort.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT,
        web = webPort.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT,
    )
}

private fun isDeployMainPasswordValid(password: String): Boolean =
    password.length == 16 && password.matches(Regex("^[a-zA-Z0-9]{16}$"))

private fun hasWebPanelCredentials(login: String, password: String): Boolean =
    login.isNotBlank() && password.isNotBlank()

/** Last confirmed server state, persisted so the cards keep it across app restarts. */
private fun isFreshInspection(result: ServerInspectionResult?): Boolean =
    result != null &&
        result.checkedAtMs > 0L &&
        System.currentTimeMillis() - result.checkedAtMs < AUTO_INSPECT_INTERVAL_MS

private fun ServerInspectionResult?.confirmsDeployment(): Boolean {
    if (this == null || !isSuccess) return false
    if (serviceStatus in listOf("systemd_active", "docker_active", "process_running")) return true
    if (panelAvailable) return true
    return installedVersion.isNotBlank() &&
        installedVersion != "не определена" &&
        installedVersion != "unknown"
}

private fun DeploySettingsSnapshot.toInspectionResult(): ServerInspectionResult? =
    if (!isLoaded || lastInspectionAtMs <= 0L) {
        null
    } else {
        ServerInspectionResult(
            checkedAtMs = lastInspectionAtMs,
            isSuccess = lastInspectionOk,
            arch = lastInspectionArch,
            serviceStatus = lastInspectionServiceStatus,
            installedVersion = lastInspectionVersion,
            message = lastInspectionMessage,
            panelAvailable = lastPanelAvailable,
        )
    }

@Composable
fun V2DeployScreen(
    settingsStore: SettingsStore,
    version: String,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onVersionClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val colors = V2Theme.colors

    LaunchedEffect(Unit) { DeployManager.init(context) }

    val savedSettings by settingsStore.deploySettingsSnapshot.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedDeploySnapshot,
    )
    val isDeploying by DeployManager.isDeploying.collectAsStateWithLifecycle()
    val deployProgress by DeployManager.deployProgress.collectAsStateWithLifecycle()
    val currentStep by DeployManager.currentStep.collectAsStateWithLifecycle()
    val lastResult by DeployManager.lastResult.collectAsStateWithLifecycle()
    val deployServers by settingsStore.deployServers.collectAsStateWithLifecycle(initialValue = emptyList())
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()

    val savedIp = savedSettings.ip
    val savedLogin = savedSettings.sshLogin
    val savedPassword = savedSettings.sshPassword
    val savedWebLogin = savedSettings.webLogin
    val savedWebPassword = savedSettings.webPassword
    val savedMainPass = savedSettings.mainPassword
    val savedSshPort = savedSettings.sshPort
    val savedManualPorts = savedSettings.manualPortsEnabled
    val savedSshKeysMode = savedSettings.sshKeysMode
    val savedDockerInstall = savedSettings.dockerInstall
    val savedSshPrivateKey = savedSettings.sshPrivateKey
    val savedSshKeyPassphrase = savedSettings.sshKeyPassphrase
    val savedSshCertificate = savedSettings.sshCertificate
    val savedServerPeerPort = savedSettings.serverPeerPort
    val savedServerWebPort = savedSettings.serverWebPort
    val savedSecretPathUuid = savedSettings.secretPathUuid
    val savedServerConfigured = savedSettings.serverConfigured
    val rememberedInspection = savedSettings.toInspectionResult()

    val isInspecting by ServerMaintenanceState.isInspecting.collectAsStateWithLifecycle()
    val isPlanning by ServerMaintenanceState.isPlanning.collectAsStateWithLifecycle()
    val inspectionResult by ServerMaintenanceState.inspectionResult.collectAsStateWithLifecycle()
    val isChangingSecretPath by ServerMaintenanceState.isChangingSecretPath.collectAsStateWithLifecycle()
    val changeSecretPathStage by ServerMaintenanceState.changeSecretPathStage.collectAsStateWithLifecycle()

    val serverDeployed = savedServerConfigured ||
        rememberedInspection.confirmsDeployment() ||
        inspectionResult.confirmsDeployment()

    val bundledVersion = remember(context) { bundledServerVersion(context) }
    val bundledComparable = comparableServerVersion(bundledVersion)
    val latestInspection = inspectionResult ?: rememberedInspection
    val knownServerVersionRaw = latestInspection
        ?.takeIf { it.isSuccess }
        ?.installedVersion
        .orEmpty()
    val knownServerVersion = comparableServerVersion(knownServerVersionRaw)
    val updateAvailable = serverDeployed &&
        knownServerVersion.isNotBlank() &&
        bundledComparable.isNotBlank() &&
        isNewerVersion(knownServerVersion, bundledComparable)

    var showConfigSheet by rememberSaveable { mutableStateOf(false) }
    var showActionsMenu by rememberSaveable { mutableStateOf(false) }
    var showServerMenu by rememberSaveable { mutableStateOf(false) }
    var addingServer by rememberSaveable { mutableStateOf(false) }
    var serverCardExpanded by rememberSaveable { mutableStateOf(true) }

    var serverName by rememberSaveable(savedSettings.profile) { mutableStateOf(savedSettings.serverName) }
    val serverTitle = serverName.ifBlank { "Сервер CSQTT" }

    var host by rememberSaveable(savedSettings.profile) { mutableStateOf(savedIp) }
    var sshLogin by rememberSaveable(savedSettings.profile) { mutableStateOf(savedLogin) }
    var sshPassword by rememberSaveable(savedSettings.profile) { mutableStateOf(savedPassword) }
    var sshPort by rememberSaveable(savedSettings.profile) {
        mutableStateOf(
            if (savedIp.isBlank()) {
                ""
            } else {
                savedSshPort.ifBlank { CsqttConstants.Network.DEFAULT_SSH_PORT.toString() }
            },
        )
    }
    var peerPort by rememberSaveable(savedSettings.profile) { mutableStateOf(savedServerPeerPort.toString()) }
    var webPort by rememberSaveable(savedSettings.profile) { mutableStateOf(savedServerWebPort.toString()) }
    var dockerInstall by rememberSaveable(savedSettings.profile) { mutableStateOf(savedDockerInstall) }
    var manualPorts by rememberSaveable(savedSettings.profile) { mutableStateOf(savedManualPorts) }
    var sshKeysMode by rememberSaveable(savedSettings.profile) { mutableStateOf(savedSshKeysMode) }

    var deployPlan by rememberSaveable { mutableStateOf<DeployPlan?>(null) }
    var pendingPlanAction by rememberSaveable { mutableStateOf<PlanAction?>(null) }
    var pendingDestructiveAction by rememberSaveable { mutableStateOf<PlanAction?>(null) }
    var removalCandidates by rememberSaveable { mutableStateOf<List<FoundServer>?>(null) }
    var removalNotesUi by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var pendingRemovalServer by rememberSaveable { mutableStateOf<FoundServer?>(null) }
    var previousActiveProfile by rememberSaveable { mutableStateOf(-1) }
    var addingServerSwitchedProfile by rememberSaveable { mutableStateOf(false) }
    var dismissedResult by rememberSaveable { mutableStateOf("") }
    var plannedInventory by remember { mutableStateOf<ServerInventory?>(null) }
    var deployOptionsBackup by rememberSaveable { mutableStateOf<DeployOptionsBackup?>(null) }
    // Live results per profile: a failed check is not persisted, so without this cache switching
    // away and back would resurrect the stale persisted "success" (bug 7).
    val liveInspections = remember { mutableMapOf<Int, ServerInspectionResult>() }

    var showSecretsDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var pendingDeployAfterSecrets by remember { mutableStateOf<PendingDeployAfterSecrets?>(null) }
    var remoteWebCreds by remember { mutableStateOf<Pair<String, String>?>(null) }
    var fetchingWebCreds by remember { mutableStateOf(false) }
    var showDeployOptions by rememberSaveable { mutableStateOf(false) }
    var deployOptionsModeKey by rememberSaveable(savedSettings.profile) {
        mutableStateOf(DeployActionMode.Install.name)
    }
    var showDeleteAppServerDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var showSshKeysDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var showDockerInfoDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var showRegenConfirmDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }

    // Track the last values copied from the store. The draft itself is restored after a page
    // switch, so syncing only on an actual change keeps unsaved edits instead of resetting them.
    var syncedHost by rememberSaveable { mutableStateOf(savedIp) }
    var syncedSshLogin by rememberSaveable { mutableStateOf(savedLogin) }
    var syncedSshPassword by rememberSaveable { mutableStateOf(savedPassword) }
    var syncedSshPort by rememberSaveable { mutableStateOf(savedSshPort) }
    var syncedPeerPort by rememberSaveable { mutableStateOf(savedServerPeerPort) }
    var syncedWebPort by rememberSaveable { mutableStateOf(savedServerWebPort) }
    var syncedDockerInstall by rememberSaveable { mutableStateOf(savedDockerInstall) }
    var syncedManualPorts by rememberSaveable { mutableStateOf(savedManualPorts) }
    var syncedSshKeysMode by rememberSaveable { mutableStateOf(savedSshKeysMode) }

    LaunchedEffect(savedIp) {
        if (savedIp != syncedHost) {
            syncedHost = savedIp
            host = savedIp
            ServerMaintenanceState.inspectionResult.value = null
        }
    }
    LaunchedEffect(savedLogin, savedPassword) {
        if (savedLogin != syncedSshLogin) {
            syncedSshLogin = savedLogin
            sshLogin = savedLogin
        }
        if (savedPassword != syncedSshPassword) {
            syncedSshPassword = savedPassword
            sshPassword = savedPassword
        }
    }
    LaunchedEffect(savedSshPort, savedServerPeerPort, savedServerWebPort) {
        if (addingServer) return@LaunchedEffect
        if (savedSshPort != syncedSshPort) {
            syncedSshPort = savedSshPort
            sshPort = savedSshPort.ifBlank { CsqttConstants.Network.DEFAULT_SSH_PORT.toString() }
        }
        if (savedServerPeerPort != syncedPeerPort) {
            syncedPeerPort = savedServerPeerPort
            peerPort = savedServerPeerPort.toString()
        }
        if (savedServerWebPort != syncedWebPort) {
            syncedWebPort = savedServerWebPort
            webPort = savedServerWebPort.toString()
        }
    }
    LaunchedEffect(savedSettings.profile, savedSettings.serverName) {
        if (savedSettings.serverName.isNotBlank() && serverName.isBlank()) {
            serverName = savedSettings.serverName
        }
    }
    LaunchedEffect(savedDockerInstall) {
        if (savedDockerInstall != syncedDockerInstall) {
            syncedDockerInstall = savedDockerInstall
            dockerInstall = savedDockerInstall
        }
    }
    LaunchedEffect(savedManualPorts) {
        if (savedManualPorts != syncedManualPorts) {
            syncedManualPorts = savedManualPorts
            manualPorts = savedManualPorts
        }
    }
    LaunchedEffect(savedSshKeysMode) {
        if (savedSshKeysMode != syncedSshKeysMode) {
            syncedSshKeysMode = savedSshKeysMode
            sshKeysMode = savedSshKeysMode
        }
    }
    // Restore the last confirmed server state so the pills keep showing it after a restart.
    // A live result for this profile wins: an unsuccessful check is not persisted, so the store
    // alone would restore an older success after switching back (bug 7).
    LaunchedEffect(savedSettings.profile, savedSettings.isLoaded, savedSettings.lastInspectionAtMs) {
        if (savedSettings.isLoaded) {
            val remembered = savedSettings.toInspectionResult()
            val current = liveInspections[savedSettings.profile] ?: remembered
            ServerMaintenanceState.inspectionResult.value = current
            if (!savedSettings.serverConfigured && current.confirmsDeployment()) {
                settingsStore.saveServerConfigured(true)
            } else if (savedSettings.serverConfigured &&
                current != null &&
                current.isSuccess &&
                !current.confirmsDeployment()
            ) {
                settingsStore.saveServerConfigured(false)
            }
        }
    }
    LaunchedEffect(savedSettings.profile, savedSecretPathUuid, savedSettings.isLoaded) {
        if (savedSettings.isLoaded && savedSecretPathUuid.isBlank()) {
            settingsStore.regenerateDeploySecretPathUuid()
        }
    }

    val mainPasswordConfigured = isDeployMainPasswordValid(savedMainPass)
    val webPanelConfigured = hasWebPanelCredentials(savedWebLogin, savedWebPassword)
    val sshKeysFilled = if (savedSshPrivateKey.isNotBlank()) 1 else 0
    val sshAuthReady = sshLogin.isNotBlank() &&
        if (sshKeysMode) sshKeysFilled == 1 else sshPassword.isNotBlank()
    val portsValid = !manualPorts || listOf(sshPort, peerPort, webPort).all(::isValidPort)
    val actionsBusy = isDeploying || isInspecting || isPlanning || isChangingSecretPath
    val effectiveSshPassword = if (sshKeysMode) "" else sshPassword
    val effectivePrivateKey = if (sshKeysMode) savedSshPrivateKey else ""
    val effectiveKeyPassphrase = if (sshKeysMode) savedSshKeyPassphrase else ""
    val effectiveCertificate = if (sshKeysMode) savedSshCertificate else ""

    fun openSecretsDialog() {
        showSecretsDialog = true
        val ports = resolveServerPorts(manualPorts, sshPort, peerPort, webPort)
        if (host.isNotBlank() && sshAuthReady && !fetchingWebCreds) {
            fetchingWebCreds = true
            ServerMaintenanceState.scope.launch {
                try {
                    remoteWebCreds = ServerMaintenanceOperations.readPanelCredentials(
                        context = context,
                        host = host,
                        user = sshLogin,
                        pass = effectiveSshPassword,
                        port = ports.ssh,
                        privateKey = effectivePrivateKey,
                        keyPassphrase = effectiveKeyPassphrase,
                        certificate = effectiveCertificate,
                    )
                } catch (e: Exception) {
                    remoteWebCreds = null
                } finally {
                    fetchingWebCreds = false
                }
            }
        }
    }

    fun showDeployToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            context.showRaisedToast(message, Toast.LENGTH_LONG)
        }
    }

    fun resetServerDraft() {
        serverName = savedSettings.serverName
        host = savedIp
        sshLogin = savedLogin
        sshPassword = savedPassword
        sshPort = savedSshPort.ifBlank { CsqttConstants.Network.DEFAULT_SSH_PORT.toString() }
        peerPort = savedServerPeerPort.toString()
        webPort = savedServerWebPort.toString()
        dockerInstall = savedDockerInstall
        manualPorts = savedManualPorts
        sshKeysMode = savedSshKeysMode
    }

    fun clearServerDraft() {
        serverName = ""
        host = ""
        sshLogin = ""
        sshPassword = ""
        sshPort = ""
        peerPort = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT.toString()
        webPort = CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT.toString()
        dockerInstall = false
        manualPorts = false
        sshKeysMode = false
        ServerMaintenanceState.inspectionResult.value = null
    }

    fun openAddServerSheet() {
        if (actionsBusy) return
        scope.launch {
            previousActiveProfile = savedSettings.profile
            if (savedIp.isNotBlank()) {
                if (tunnelRunning) {
                    showDeployToast("Остановите туннель перед добавлением сервера")
                    return@launch
                }
                val slot = settingsStore.findFirstFreeDeployProfile()
                if (slot == null) {
                    showDeployToast("Нет свободных профилей для нового сервера")
                    return@launch
                }
                addingServer = true
                clearServerDraft()
                settingsStore.saveActiveProfile(slot)
                addingServerSwitchedProfile = true
            } else {
                addingServer = true
                addingServerSwitchedProfile = false
                clearServerDraft()
            }
            showConfigSheet = true
        }
    }

    fun inspectServer(silent: Boolean = false) {
        if (actionsBusy) return
        val ports = resolveServerPorts(manualPorts, sshPort, peerPort, webPort)
        ServerMaintenanceState.isInspecting.value = true
        ServerMaintenanceState.scope.launch {
            try {
                val result = ServerMaintenanceOperations.inspectServer(
                    context = context,
                    host = host,
                    user = sshLogin,
                    pass = effectiveSshPassword,
                    port = ports.ssh,
                    webPort = ports.web,
                    panelSecretPath = savedSecretPathUuid,
                    privateKey = effectivePrivateKey,
                    keyPassphrase = effectiveKeyPassphrase,
                    certificate = effectiveCertificate,
                )
                ServerMaintenanceState.inspectionResult.value = result
                liveInspections[savedSettings.profile] = result
                if (result.isSuccess) {
                    settingsStore.saveDeployInspection(
                        checkedAtMs = result.checkedAtMs,
                        arch = result.arch,
                        serviceStatus = result.serviceStatus,
                        installedVersion = result.installedVersion,
                        message = result.message,
                        panelAvailable = result.panelAvailable,
                    )
                    if (result.confirmsDeployment()) {
                        if (!savedSettings.serverConfigured) {
                            settingsStore.saveServerConfigured(true)
                        }
                    } else if (savedSettings.serverConfigured) {
                        settingsStore.saveServerConfigured(false)
                    }
                    if (!silent) {
                        showDeployToast(
                            "VPS: ${result.message}" +
                                if (result.panelAvailable) ", панель доступна" else ", панель недоступна",
                        )
                    }
                } else if (!silent) {
                    showDeployToast("Ошибка проверки VPS: ${result.message}")
                }
            } finally {
                ServerMaintenanceState.isInspecting.value = false
            }
        }
    }

    fun validateDeployBase(): Boolean {
        when {
            host.isBlank() -> {
                showDeployToast("Укажите IP-адрес сервера")
                showConfigSheet = true
                return false
            }
            !sshAuthReady -> {
                showDeployToast("Укажите данные SSH для подключения к серверу")
                showConfigSheet = true
                return false
            }
            !portsValid -> {
                showDeployToast("Проверьте правильность портов")
                showConfigSheet = true
                return false
            }
        }
        return true
    }

    fun buildDeployPlan(inventory: ServerInventory): DeployPlan {
        val ports = resolveServerPorts(manualPorts, sshPort, peerPort, webPort)
        return planDeploy(
            inventory = inventory,
            manualPorts = if (manualPorts) {
                com.csqtt.client.ui.legacy.EffectiveServerPorts(
                    ssh = ports.ssh,
                    peer = ports.peer,
                    web = ports.web,
                )
            } else {
                null
            },
            profilePeerPort = ports.peer,
            dockerInstall = dockerInstall,
            appVersion = bundledComparable.ifBlank { BuildConfig.VERSION_NAME.removePrefix("v") },
        )
    }

    fun loadInventory(onLoaded: (ServerInventory) -> Unit) {
        if (actionsBusy) return
        if (!validateDeployBase()) return
        val ports = resolveServerPorts(manualPorts, sshPort, peerPort, webPort)
        ServerMaintenanceState.isPlanning.value = true
        ServerMaintenanceState.scope.launch {
            try {
                val inventory = performServerCheck(
                    context = context,
                    host = host,
                    user = sshLogin,
                    pass = effectiveSshPassword,
                    port = ports.ssh,
                    privateKey = effectivePrivateKey,
                    keyPassphrase = effectiveKeyPassphrase,
                    certificate = effectiveCertificate,
                )
                onLoaded(inventory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = friendlyDeployError(e.message)
                TunnelManager.addDeployErrorLog(message)
                showDeployToast("Не удалось проверить сервер: $message")
            } finally {
                ServerMaintenanceState.isPlanning.value = false
            }
        }
    }

    fun reinstallActionFor(server: FoundServer): PlanAction {
        val peer = server.peerPortOrDefault()
        val web = server.webPort ?: if (server.place == ServerPlace.SIDE) {
            CsqttConstants.Network.DEFAULT_SIDE_SERVER_WEB_PORT
        } else {
            CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT
        }
        return PlanAction(
            kind = PlanKind.REPLACE,
            instance = server.deployInstance(),
            peerPort = peer,
            webPort = web,
            title = if (server.place == ServerPlace.SIDE) {
                "Переустановить: порт $peer"
            } else {
                "Переустановить"
            },
            detail = "CSQTT ${server.version ?: "(версия неизвестна)"} будет удалён вместе с базой и " +
                "конфигурацией, затем установлен заново" +
                (if (bundledComparable.isNotBlank()) " v$bundledComparable" else "") +
                " на порт $peer. Это действие необратимо.",
        )
    }

    fun requestReinstallPlan(inventory: ServerInventory) {
        if (inventory.servers.any { it.place == ServerPlace.PANEL }) {
            showDeployToast("Этим сервером управляет swg-panel — переустановка недоступна")
            return
        }
        val foreignRunning = inventory.servers.any { it.place == ServerPlace.OTHER && it.installed }
        // A standard-place reinstall would stop the foreign server too, so it is withheld then.
        val candidates = inventory.servers.filter {
            it.installed &&
                (it.place == ServerPlace.SIDE || (it.place == ServerPlace.STANDARD && !foreignRunning))
        }
        if (candidates.isEmpty()) {
            val standardBlocked = foreignRunning &&
                inventory.servers.any { it.place == ServerPlace.STANDARD && it.installed }
            showDeployToast(
                if (standardBlocked) {
                    "Переустановка остановит сторонний сервер csqtt на этом VPS — сначала остановите его"
                } else {
                    "На сервере не найдена установка CSQTT"
                },
            )
            return
        }
        if (candidates.size == 1) {
            pendingDestructiveAction = reinstallActionFor(candidates.single())
            return
        }
        val actions = candidates.sortedBy { it.place.ordinal }.map(::reinstallActionFor)
        deployPlan = DeployPlan(
            servers = inventory.servers,
            actions = actions,
            notes = listOf(
                "На сервере две установки CSQTT. Выберите ту, которую нужно переустановить — " +
                    "вторая останется без изменений.",
            ),
        )
    }

    fun requestDeployPlan(mode: DeployActionMode) {
        loadInventory { inventory ->
            plannedInventory = inventory
            val plan = buildDeployPlan(inventory)
            if (mode == DeployActionMode.Reinstall) {
                requestReinstallPlan(inventory)
                return@loadInventory
            }
            val installed = inventory.servers
                .firstOrNull { it.installed && (it.place == ServerPlace.STANDARD || it.place == ServerPlace.SIDE) }
                ?.version
                .orEmpty()
            val installedComparable = comparableServerVersion(installed)
            val isDowngrade = installedComparable.isNotBlank() &&
                bundledComparable.isNotBlank() &&
                isNewerVersion(bundledComparable, installedComparable)
            if (mode == DeployActionMode.Update && isDowngrade) {
                showDeployToast(
                    "На сервере версия v$installedComparable новее встроенной v$bundledComparable",
                )
                return@loadInventory
            }
            val isSameVersion = installedComparable.isNotBlank() &&
                bundledComparable.isNotBlank() &&
                installedComparable == bundledComparable
            val adjustedPlan = if (isSameVersion) {
                plan.copy(
                    actions = plan.actions.map { action ->
                        if (action.kind == PlanKind.UPDATE) {
                            action.copy(
                                title = "Переустановить бинарник",
                                detail = "Бинарник v$installedComparable будет заменён с сохранением " +
                                    "конфигурации и базы.",
                            )
                        } else {
                            action
                        }
                    },
                )
            } else {
                plan
            }
            deployPlan = if (isDowngrade) {
                adjustedPlan.copy(
                    actions = adjustedPlan.actions.filterNot { it.kind == PlanKind.UPDATE },
                    notes = (
                        adjustedPlan.notes +
                            "Встроенная сборка v$bundledComparable старее установленной " +
                            "v$installedComparable — обновление недоступно."
                        ).distinct(),
                )
            } else {
                adjustedPlan
            }
        }
    }

    LaunchedEffect(
        savedSettings.profile,
        savedSettings.isLoaded,
        savedIp,
        savedSettings.lastInspectionAtMs,
        showConfigSheet,
        addingServer,
    ) {
        if (!savedSettings.isLoaded || addingServer || showConfigSheet) return@LaunchedEffect
        if (savedIp.isBlank() || host != savedIp || !sshAuthReady) return@LaunchedEffect
        if (isDeploying || isInspecting || isPlanning || isChangingSecretPath) return@LaunchedEffect

        val now = System.currentTimeMillis()
        val cacheFresh = savedSettings.lastInspectionAtMs > 0L &&
            now - savedSettings.lastInspectionAtMs < AUTO_INSPECT_INTERVAL_MS
        if (cacheFresh) return@LaunchedEffect
        val autoKey = "${savedSettings.profile}:$savedIp"
        val lastAttempt = ServerMaintenanceState.lastAutoInspectAttemptMs
        if (ServerMaintenanceState.lastAutoInspectKey == autoKey &&
            lastAttempt > 0L &&
            now - lastAttempt < AUTO_INSPECT_INTERVAL_MS
        ) {
            return@LaunchedEffect
        }

        ServerMaintenanceState.lastAutoInspectKey = autoKey
        ServerMaintenanceState.lastAutoInspectAttemptMs = now
        inspectServer(silent = true)
    }

    val saveServerSettings: () -> Unit = {
        val wasNew = savedIp.isBlank()
        scope.launch {
            val ports = resolveServerPorts(manualPorts, sshPort, peerPort, webPort)
            settingsStore.saveDeployServerName(serverName)
            settingsStore.saveDeploy(host)
            settingsStore.saveDeploySecrets(savedMainPass, sshLogin, sshPassword, savedWebLogin, savedWebPassword)
            settingsStore.savePorts(ports.peer, ports.web, ports.ssh.toString())
            settingsStore.saveManualPortsEnabled(manualPorts)
            settingsStore.saveSshKeysMode(sshKeysMode)
            settingsStore.saveDockerInstall(dockerInstall)
            showDeployToast("Данные сервера сохранены")
            if (wasNew && host.isNotBlank()) {
                inspectServer()
            }
        }
    }

    fun launchDeploy(
        mode: DeployActionMode,
        instance: DeployInstance = DeployInstance.MAIN,
        peerPortOverride: Int? = null,
        webPortOverride: Int? = null,
        credentialsOverride: DeployCredentials? = null,
    ) {
        if (DeployManager.isDeploying.value || isInspecting || isPlanning || isChangingSecretPath) return
        val requiresCredentials = mode != DeployActionMode.Update
        val mainPass = credentialsOverride?.mainPass ?: savedMainPass
        val webLogin = credentialsOverride?.webLogin ?: savedWebLogin
        val webPass = credentialsOverride?.webPass ?: savedWebPassword
        when {
            host.isBlank() -> {
                showDeployToast("Укажите IP-адрес сервера")
                showConfigSheet = true
                return
            }
            !sshAuthReady -> {
                showDeployToast("Укажите данные SSH для подключения к серверу")
                showConfigSheet = true
                return
            }
            !portsValid -> {
                showDeployToast("Проверьте правильность портов")
                showConfigSheet = true
                return
            }
            requiresCredentials &&
                (!isDeployMainPasswordValid(mainPass) || !hasWebPanelCredentials(webLogin, webPass)) -> {
                pendingDeployAfterSecrets = PendingDeployAfterSecrets(
                    mode = mode,
                    instance = instance,
                    peerPort = peerPortOverride,
                    webPort = webPortOverride,
                )
                if (!isDeployMainPasswordValid(mainPass)) {
                    showDeployToast("Пароль туннеля должен содержать ровно 16 символов [0-9A-Za-z]")
                }
                if (!hasWebPanelCredentials(webLogin, webPass)) {
                    showDeployToast("Укажите логин и пароль web-панели")
                }
                openSecretsDialog()
                return
            }
        }

        val basePorts = resolveServerPorts(manualPorts, sshPort, peerPort, webPort)
        val ports = basePorts.copy(
            peer = peerPortOverride ?: basePorts.peer,
            web = webPortOverride ?: basePorts.web,
        )
        val actionLabel = when (mode) {
            DeployActionMode.Install -> "Установка"
            DeployActionMode.Update -> "Обновление"
            DeployActionMode.Reinstall -> "Переустановка"
        }

        dismissedResult = ""
        DeployManager.startDeploy()
        DeployManager.scope.launch {
            try {
                context.startForegroundService(
                    Intent(context, TunnelService::class.java).apply { action = "DEPLOY_START" },
                )
                settingsStore.saveDeploy(host)
                settingsStore.savePorts(ports.peer, ports.web, ports.ssh.toString())
                settingsStore.saveManualPortsEnabled(manualPorts)
                settingsStore.saveSshKeysMode(sshKeysMode)
                settingsStore.saveDockerInstall(dockerInstall)
                if (requiresCredentials) {
                    settingsStore.saveDeploySecrets(
                        mainPass,
                        sshLogin,
                        sshPassword,
                        webLogin,
                        webPass,
                    )
                }

                var deployDockerMode = dockerInstall

                if (mode == DeployActionMode.Update) {
                    DeployManager.updateProgress(0.01f, "Проверка версии на сервере…")
                    val inspection = ServerMaintenanceOperations.inspectServer(
                        context = context,
                        host = host,
                        user = sshLogin,
                        pass = effectiveSshPassword,
                        port = ports.ssh,
                        webPort = ports.web,
                        panelSecretPath = savedSecretPathUuid,
                        privateKey = effectivePrivateKey,
                        keyPassphrase = effectiveKeyPassphrase,
                        certificate = effectiveCertificate,
                    )
                    if (!inspection.isSuccess) {
                        DeployManager.stopDeploy(inspection.message)
                        showDeployToast("Не удалось проверить версию: ${inspection.message}")
                        return@launch
                    }
                    deployDockerMode = when (inspection.serviceStatus) {
                        "docker_active" -> true
                        "systemd_active", "process_running" -> false
                        else -> dockerInstall
                    }
                    if (deployDockerMode != dockerInstall) {
                        settingsStore.saveDockerInstall(deployDockerMode)
                    }
                    val updateBundledVersion = bundledVersion
                    if (updateBundledVersion.isBlank()) {
                        DeployManager.stopDeploy("Не удалось определить версию серверной сборки")
                        showDeployToast("Не удалось определить версию серверной сборки в приложении")
                        return@launch
                    }
                    val installedVersion = comparableServerVersion(inspection.installedVersion)
                    val bundledUpdateVersion = comparableServerVersion(updateBundledVersion)
                    if (installedVersion.isNotBlank() &&
                        isNewerVersion(bundledUpdateVersion, installedVersion)
                    ) {
                        DeployManager.stopDeploy("На сервере более новая версия")
                        showDeployToast(
                            "На сервере версия v$installedVersion новее встроенной v$bundledUpdateVersion",
                        )
                        return@launch
                    }
                    if (installedVersion.isBlank()) {
                        TunnelManager.addDeployWarningLog(
                            "Версия на сервере не определена — будет загружена сборка v$updateBundledVersion",
                        )
                    } else if (isNewerVersion(installedVersion, bundledUpdateVersion)) {
                        DeployManager.updateProgress(
                            0.03f,
                            "Сервер устарел: v$installedVersion → v$updateBundledVersion",
                        )
                    } else {
                        DeployManager.updateProgress(
                            0.03f,
                            "Обновление бинарника v$installedVersion (конфигурация сохранится)",
                        )
                    }
                }

                suspend fun runDeploy(
                    seedWebEnv: Boolean,
                    seedDeployOverrides: Boolean,
                ): Boolean = performDeploy(
                    context = context,
                    host = host,
                    user = sshLogin,
                    pass = effectiveSshPassword,
                    port = ports.ssh,
                    mainPass = mainPass,
                    webLogin = webLogin,
                    webPass = webPass,
                    peerPort = ports.peer,
                    webPort = ports.web,
                    onProgress = DeployManager::updateProgress,
                    privateKey = effectivePrivateKey,
                    keyPassphrase = effectiveKeyPassphrase,
                    certificate = effectiveCertificate,
                    installInDocker = deployDockerMode,
                    secretPath = if (mode == DeployActionMode.Update && !seedWebEnv) {
                        ""
                    } else {
                        savedSecretPathUuid.ifBlank { "" }
                    },
                    mode = mode,
                    instance = instance,
                    seedWebEnv = seedWebEnv,
                    seedDeployOverrides = seedDeployOverrides,
                )

                var success = runDeploy(
                    seedWebEnv = false,
                    seedDeployOverrides = false,
                )
                if (!success && mode == DeployActionMode.Update) {
                    val hint = DeployManager.lastFailureHint.value
                    val seedWebEnv = hint == DEPLOY_HINT_MISSING_WEB_ENV
                    val seedOverrides = hint == DEPLOY_HINT_MISSING_DEPLOY_OVERRIDES
                    if (seedWebEnv || seedOverrides) {
                        DeployManager.startDeploy()
                        DeployManager.updateProgress(0.02f, "Восстановление deploy-конфигурации…")
                        TunnelManager.addDeployWarningLog(
                            "В обновлении отсутствует deploy-конфигурация — создаю её из данных профиля",
                        )
                        success = runDeploy(
                            seedWebEnv = seedWebEnv,
                            seedDeployOverrides = seedOverrides,
                        )
                    }
                }
                if (success) {
                    settingsStore.saveServerConfigured(true)
                    val deployedVersion = comparableServerVersion(bundledServerVersion(context))
                    if (deployedVersion.isNotBlank()) {
                        val deployed = ServerInspectionResult(
                            checkedAtMs = System.currentTimeMillis(),
                            isSuccess = true,
                            arch = inspectionResult?.arch
                                ?.takeIf { it.isNotBlank() && it != "unknown" }
                                .orEmpty(),
                            serviceStatus = if (deployDockerMode) "docker_active" else "systemd_active",
                            installedVersion = deployedVersion,
                            message = if (deployDockerMode) {
                                "Docker-контейнер активен"
                            } else {
                                "Служба systemd активна"
                            },
                            panelAvailable = true,
                        )
                        settingsStore.saveDeployInspection(
                            checkedAtMs = deployed.checkedAtMs,
                            arch = deployed.arch,
                            serviceStatus = deployed.serviceStatus,
                            installedVersion = deployed.installedVersion,
                            message = deployed.message,
                            panelAvailable = deployed.panelAvailable,
                        )
                        liveInspections[savedSettings.profile] = deployed
                        ServerMaintenanceState.inspectionResult.value = deployed
                    }
                    if (mode != DeployActionMode.Update) {
                        settingsStore.saveConnectionPassword(mainPass)
                        TunnelManager.addDeployInfoLog("Пароль подключения синхронизирован с паролем сервера")
                    }
                    showDeployToast("$actionLabel успешно завершена")
                } else {
                    showDeployToast(DeployManager.lastResult.value.ifBlank { "Ошибка: $actionLabel" })
                }
            } catch (e: CancellationException) {
                DeployManager.stopDeploy("$actionLabel отменена")
                throw e
            } catch (e: Exception) {
                val message = friendlyDeployError(e.message)
                DeployManager.writeError(
                    "Deploy UI error (${e.javaClass.simpleName}): ${e.message}\n" +
                        e.stackTraceToString().take(1200),
                )
                TunnelManager.addDeployErrorLog(message)
                DeployManager.stopDeploy(message)
                showDeployToast(message)
            } finally {
                runCatching {
                    context.startService(Intent(context, TunnelService::class.java).apply { action = "DEPLOY_STOP" })
                }.onFailure {
                    DeployManager.writeError("Не удалось остановить foreground-режим деплоя: ${it.message}")
                }
            }
        }
    }

    fun startDeploy(mode: DeployActionMode) {
        if (actionsBusy) return
        if (mode == DeployActionMode.Update &&
            knownServerVersion.isNotBlank() &&
            bundledComparable.isNotBlank() &&
            isNewerVersion(bundledComparable, knownServerVersion)
        ) {
            showDeployToast(
                "На сервере версия v$knownServerVersion новее встроенной v$bundledComparable",
            )
            return
        }
        requestDeployPlan(mode)
    }

    fun choosePlanAction(action: PlanAction) {
        deployPlan = null
        when (action.kind) {
            PlanKind.UPDATE -> launchDeploy(
                mode = DeployActionMode.Update,
                instance = action.instance,
                peerPortOverride = action.peerPort,
                webPortOverride = action.webPort,
            )
            PlanKind.REPLACE -> pendingDestructiveAction = action
            PlanKind.INSTALL, PlanKind.INSTALL_NEXT_TO -> {
                pendingPlanAction = action
                deployOptionsBackup = DeployOptionsBackup(manualPorts, peerPort, webPort, dockerInstall)
                peerPort = action.peerPort.toString()
                webPort = action.webPort.toString()
                // Manual mode is only needed when the plan ports differ from the defaults that
                // resolveServerPorts(false, …) would otherwise use. For a default install the
                // toggle must stay as it was, otherwise cancelling the sheet leaves it on (bug 5).
                val defaults = resolveServerPorts(false, sshPort, peerPort, webPort)
                if (action.peerPort != defaults.peer || action.webPort != defaults.web) {
                    manualPorts = true
                }
                // Prefer the runtime the plan was built from, not the last (possibly stale)
                // inspection result (bug 6).
                val place = when (action.instance) {
                    DeployInstance.MAIN -> ServerPlace.STANDARD
                    DeployInstance.SIDE -> ServerPlace.SIDE
                }
                when (plannedInventory?.servers?.firstOrNull { it.installed && it.place == place }?.runtime) {
                    "docker" -> dockerInstall = true
                    "systemd", "process" -> dockerInstall = false
                }
                deployOptionsModeKey = DeployActionMode.Install.name
                showDeployOptions = true
            }
        }
    }

    fun startUninstall() {
        if (actionsBusy) return
        loadInventory { inventory ->
            val plan = buildDeployPlan(inventory)
            val servers = removableServers(inventory)
            val removalReasons = removalNotes(inventory)
            val notes = (removalReasons + plan.notes).distinct()
            if (servers.isEmpty()) {
                showDeployToast(
                    removalReasons.firstOrNull()
                        ?: "На сервере не найдено установок CSQTT, которые можно удалить",
                )
                return@loadInventory
            }
            removalNotesUi = notes
            removalCandidates = servers
        }
    }

    fun launchUninstall(server: FoundServer) {
        if (actionsBusy) return
        val ports = resolveServerPorts(manualPorts, sshPort, peerPort, webPort)
        dismissedResult = ""
        DeployManager.startDeploy()
        DeployManager.scope.launch {
            try {
                context.startForegroundService(
                    Intent(context, TunnelService::class.java).apply { action = "DEPLOY_START" },
                )
                val success = performUninstall(
                    context = context,
                    host = host,
                    user = sshLogin,
                    pass = effectiveSshPassword,
                    port = ports.ssh,
                    peerPort = server.peerPortOrDefault(),
                    onProgress = DeployManager::updateProgress,
                    privateKey = effectivePrivateKey,
                    keyPassphrase = effectiveKeyPassphrase,
                    certificate = effectiveCertificate,
                    instance = server.deployInstance(),
                )
                if (success) {
                    val remaining = removalCandidates.orEmpty().filterNot { it == server }
                    removalCandidates = remaining.ifEmpty { null }
                    removalNotesUi = emptyList()
                    if (remaining.isEmpty()) {
                        settingsStore.saveServerConfigured(false)
                        settingsStore.clearDeployInspection()
                        ServerMaintenanceState.inspectionResult.value = null
                        liveInspections.remove(savedSettings.profile)
                    } else {
                        inspectServer(silent = true)
                    }
                    showDeployToast("CSQTT успешно удален с сервера")
                } else {
                    showDeployToast(DeployManager.lastResult.value.ifBlank { "Ошибка удаления" })
                }
            } catch (e: CancellationException) {
                DeployManager.stopDeploy("Удаление отменено")
                throw e
            } catch (e: Exception) {
                val message = friendlyDeployError(e.message)
                DeployManager.writeError(
                    "Uninstall UI error (${e.javaClass.simpleName}): ${e.message}\n" +
                        e.stackTraceToString().take(1200),
                )
                TunnelManager.addDeployErrorLog(message)
                DeployManager.stopDeploy(message)
                showDeployToast(message)
            } finally {
                runCatching {
                    context.startService(Intent(context, TunnelService::class.java).apply { action = "DEPLOY_STOP" })
                }.onFailure {
                    DeployManager.writeError("Не удалось остановить foreground-режим удаления: ${it.message}")
                }
            }
        }
    }

    fun panelUrl(): String {
        val ports = resolveServerPorts(manualPorts, sshPort, peerPort, webPort)
        val cleanPath = savedSecretPathUuid.trim().trimStart('/')
        val panelPath = if (cleanPath.isNotBlank()) "/$cleanPath" else ""
        return "https://${host.trim()}:${ports.web}$panelPath"
    }

    fun copyPanelLink() {
        val effectiveHost = host.trim()
        val text = if (effectiveHost.isNotBlank()) panelUrl() else savedSecretPathUuid
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("csqtt_web_panel", text))
        showDeployToast("Ссылка скопирована")
    }

    fun openPanel() {
        if (host.isBlank()) {
            showDeployToast("Сначала укажите IP или домен сервера")
            return
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(panelUrl()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            showDeployToast("Не удалось открыть браузер")
        }
    }

    fun changeSecretPath() {
        if (actionsBusy) return
        if (host.isBlank() || !sshAuthReady) {
            showDeployToast("Укажите данные SSH для подключения к серверу")
            showConfigSheet = true
            return
        }
        val ports = resolveServerPorts(manualPorts, sshPort, peerPort, webPort)
        val candidatePath = UUID.randomUUID().toString()
        val privateKey = if (sshKeysMode) savedSshPrivateKey else ""
        val passphrase = if (sshKeysMode) savedSshKeyPassphrase else ""
        val certificate = if (sshKeysMode) savedSshCertificate else ""
        val password = if (sshKeysMode) "" else sshPassword
        ServerMaintenanceState.changeSecretPathStage.value = "Подключение к серверу…"
        ServerMaintenanceState.isChangingSecretPath.value = true
        ServerMaintenanceState.scope.launch {
            try {
                val (success, message) = ServerMaintenanceOperations.changeWebSecretPath(
                    context = context,
                    host = host,
                    user = sshLogin,
                    pass = password,
                    port = ports.ssh,
                    webPort = ports.web,
                    newSecretPath = candidatePath,
                    privateKey = privateKey,
                    keyPassphrase = passphrase,
                    certificate = certificate,
                )
                if (success) {
                    settingsStore.saveDeploySecretPathUuid(candidatePath)
                    val now = System.currentTimeMillis()
                    val base = ServerMaintenanceState.inspectionResult.value ?: rememberedInspection
                    val updated = (base ?: ServerInspectionResult(checkedAtMs = now)).copy(
                        checkedAtMs = now,
                        isSuccess = true,
                        panelAvailable = true,
                    )
                    ServerMaintenanceState.inspectionResult.value = updated
                    settingsStore.saveDeployInspection(
                        checkedAtMs = now,
                        arch = updated.arch,
                        serviceStatus = updated.serviceStatus,
                        installedVersion = updated.installedVersion,
                        message = updated.message,
                        panelAvailable = true,
                    )
                    if (!savedSettings.serverConfigured) {
                        settingsStore.saveServerConfigured(true)
                    }
                    showDeployToast(message)
                } else {
                    showDeployToast("Не удалось изменить путь: $message")
                }
            } finally {
                ServerMaintenanceState.isChangingSecretPath.value = false
                ServerMaintenanceState.changeSecretPathStage.value = ""
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = V2Sizes.ScreenPadding),
        ) {
            V2TopBar(
                version = version,
                title = "Сервер",
                onSettings = onSettings,
                onVersionClick = onVersionClick,
                actions = {
                    Box {
                        V2CircleIconButton(
                            icon = Icons.Outlined.Dns,
                            contentDescription = "Серверы",
                            onClick = { showServerMenu = true },
                        )
                        DropdownMenu(
                            expanded = showServerMenu,
                            onDismissRequest = { showServerMenu = false },
                            modifier = Modifier.widthIn(min = 280.dp, max = 320.dp),
                            shape = RoundedCornerShape(20.dp),
                            containerColor = colors.bottomSurface,
                            tonalElevation = 0.dp,
                            shadowElevation = 18.dp,
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                deployServers.forEach { server ->
                                    val isActive = server.profile == savedSettings.profile
                                    V2DeployActionMenuItem(
                                        icon = Icons.Outlined.Dns,
                                        title = server.name,
                                        subtitle = if (isActive) "${server.ip} · текущий" else server.ip,
                                        tint = if (isActive) colors.accent else null,
                                        enabled = !actionsBusy,
                                        onClick = {
                                            showServerMenu = false
                                            if (!isActive) {
                                                if (tunnelRunning) {
                                                    showDeployToast("Остановите туннель перед сменой сервера")
                                                } else {
                                                    scope.launch {
                                                        settingsStore.saveActiveProfile(server.profile)
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                                V2DeployActionMenuItem(
                                    icon = Icons.Filled.Add,
                                    title = "Добавить сервер",
                                    subtitle = "Новый сервер и профиль",
                                    enabled = !actionsBusy,
                                    onClick = {
                                        showServerMenu = false
                                        openAddServerSheet()
                                    },
                                )
                            }
                        }
                    }
                },
            )

            if (!savedSettings.isLoaded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.accent)
                }
            } else if (deployServers.isEmpty() && host.isBlank() && !addingServer) {
                // 1. ПУСТОЙ ЭКРАН: Кнопка посередине "Добавить сервер"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(colors.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Dns,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(32.dp),
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Сервер не добавлен",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = V2DisplayFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                ),
                                color = colors.textPrimary,
                            )
                            Text(
                                text = "Добавьте данные VPS для деплоя и управления",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = V2BodyFamily,
                                    fontSize = 14.sp,
                                ),
                                color = colors.textMuted,
                                textAlign = TextAlign.Center,
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        V2PillButton(
                            text = "Добавить сервер",
                            onClick = { openAddServerSheet() },
                            icon = Icons.Filled.Add,
                            style = V2PillStyle.Contrast,
                            height = 54.dp,
                            horizontalPadding = 24.dp,
                            fontSize = 15.sp,
                        )
                    }
                }
            } else {
                // 2. В СПИСКЕ ЕСТЬ СЕРВЕР: карточка сервера с шилдиками и результатом диагностики внутри
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = V2Spacing.Xxl),
                    verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    // Плашка с сервером + шилдики (IP, Развернут, Версия) + корзина удаления из приложения
                    if (host.isNotBlank()) {
                        V2ServerItemCard(
                            title = serverTitle,
                            ip = host,
                            configured = serverDeployed,
                            version = bundledVersion,
                            expanded = serverCardExpanded,
                            remembered = rememberedInspection,
                            result = inspectionResult,
                            isInspecting = isInspecting,
                            webLogin = savedWebLogin,
                            webPass = savedWebPassword,
                            onClick = { serverCardExpanded = !serverCardExpanded },
                            onDeleteFromApp = { showDeleteAppServerDialog = true },
                            onOpenPanel = {
                                val panelLast = inspectionResult ?: rememberedInspection
                                val panelKnownUnavailable = isFreshInspection(panelLast) &&
                                    panelLast?.isSuccess == true &&
                                    panelLast.panelAvailable.not()
                                if (host.isBlank()) {
                                    showDeployToast("Сначала укажите IP или домен сервера")
                                } else if (!serverDeployed) {
                                    showDeployToast("Сервер еще не развернут")
                                } else if (panelKnownUnavailable) {
                                    showDeployToast("Панель недоступна по последней проверке")
                                } else {
                                    openPanel()
                                }
                            },
                            onCopyIp = {
                                if (host.isNotBlank()) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("csqtt_server_ip", host.trim()))
                                    showDeployToast("IP скопирован")
                                }
                            },
                            onCopyPanelLink = {
                                if (host.isBlank()) {
                                    showDeployToast("Сначала укажите IP адрес сервера")
                                } else if (!serverDeployed) {
                                    showDeployToast("Сервер еще не развернут")
                                } else {
                                    copyPanelLink()
                                }
                            },
                            actionsBusy = actionsBusy,
                            actionsContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm)) {
                                    val primaryText = when {
                                        isDeploying -> "Деплой…"
                                        isPlanning -> "Анализ сервера…"
                                        isInspecting -> "Проверка…"
                                        isChangingSecretPath -> "Смена пути…"
                                        !serverDeployed -> "Установить"
                                        updateAvailable -> "Обновить до v$bundledComparable"
                                        else -> "Проверка сервера"
                                    }
                                    val primaryIcon = when {
                                        actionsBusy -> null
                                        !serverDeployed -> Icons.Filled.Download
                                        updateAvailable -> Icons.Filled.CloudUpload
                                        else -> Icons.Filled.Refresh
                                    }
                                    val primaryStyle = V2PillStyle.Contrast
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        V2PillButton(
                                            text = primaryText,
                                            onClick = {
                                                when {
                                                    !serverDeployed -> startDeploy(DeployActionMode.Install)
                                                    updateAvailable -> startDeploy(DeployActionMode.Update)
                                                    else -> inspectServer()
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            icon = primaryIcon,
                                            style = primaryStyle,
                                            enabled = !actionsBusy && host.isNotBlank(),
                                            loading = actionsBusy,
                                            height = 52.dp,
                                            horizontalPadding = 12.dp,
                                            fontSize = 14.sp,
                                        )

                                        Box {
                                            Surface(
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(V2Shapes.Field)
                                                    .clickable(enabled = !actionsBusy) { showActionsMenu = true },
                                                shape = V2Shapes.Field,
                                                color = colors.bottomSurface,
                                                contentColor = colors.accent,
                                            ) {
                                                Box(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.MoreVert,
                                                        contentDescription = "Действия с сервером",
                                                        tint = if (actionsBusy) colors.textMuted else colors.textPrimary,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                            }

                                            DropdownMenu(
                                                expanded = showActionsMenu,
                                                onDismissRequest = { showActionsMenu = false },
                                                modifier = Modifier.widthIn(min = 280.dp, max = 320.dp),
                                                shape = RoundedCornerShape(20.dp),
                                                containerColor = colors.bottomSurface,
                                                tonalElevation = 0.dp,
                                                shadowElevation = 18.dp,
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    V2DeployActionMenuItem(
                                                        icon = Icons.Filled.Refresh,
                                                        title = "Проверить сервер",
                                                        subtitle = "Обновить состояние и версию",
                                                        enabled = !actionsBusy,
                                                        onClick = {
                                                            showActionsMenu = false
                                                            inspectServer()
                                                        },
                                                    )
                                                    if (!serverDeployed) {
                                                        V2DeployActionMenuItem(
                                                            icon = Icons.Filled.Download,
                                                            title = "Установить",
                                                            subtitle = "Проверить сервер и установить CSQTT",
                                                            enabled = !actionsBusy,
                                                            onClick = {
                                                                showActionsMenu = false
                                                                startDeploy(DeployActionMode.Install)
                                                            },
                                                        )
                                                    } else {
                                                        V2DeployActionMenuItem(
                                                            icon = Icons.Filled.CloudUpload,
                                                            title = if (updateAvailable) {
                                                                "Обновить до v$bundledComparable"
                                                            } else {
                                                                "Обновить"
                                                            },
                                                            subtitle = "Проверить план и заменить бинарник",
                                                            enabled = !actionsBusy,
                                                            onClick = {
                                                                showActionsMenu = false
                                                                startDeploy(DeployActionMode.Update)
                                                            },
                                                        )
                                                        V2DeployActionMenuItem(
                                                            icon = Icons.Filled.Replay,
                                                            title = "Переустановить",
                                                            subtitle = "Удалить базу и установить заново",
                                                            enabled = !actionsBusy,
                                                            onClick = {
                                                                showActionsMenu = false
                                                                startDeploy(DeployActionMode.Reinstall)
                                                            },
                                                        )
                                                        V2DeployActionMenuItem(
                                                            icon = Icons.Filled.Refresh,
                                                            title = "Сменить путь панели",
                                                            subtitle = "Применить новый путь на сервере",
                                                            enabled = !actionsBusy,
                                                            onClick = {
                                                                showActionsMenu = false
                                                                showRegenConfirmDialog = true
                                                            },
                                                        )
                                                    }
                                                    V2DeployActionMenuItem(
                                                        icon = Icons.Filled.Settings,
                                                        title = "Параметры сервера",
                                                        subtitle = "Изменить название, IP, логин или пароль",
                                                        enabled = !actionsBusy,
                                                        onClick = {
                                                            showActionsMenu = false
                                                            addingServer = false
                                                            showConfigSheet = true
                                                        },
                                                    )
                                                    if (serverDeployed) {
                                                        V2DeployActionMenuItem(
                                                            icon = Icons.Filled.Delete,
                                                            title = "Удалить CSQTT с сервера",
                                                            subtitle = "Полная деинсталляция службы с VPS",
                                                            tint = colors.error,
                                                            enabled = !actionsBusy,
                                                            onClick = {
                                                                showActionsMenu = false
                                                                startUninstall()
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        )

                        if (isDeploying) {
                            V2DeployProgressCard(
                                progress = deployProgress,
                                currentStep = currentStep,
                                onCancel = {
                                    context.startService(
                                        Intent(context, TunnelService::class.java).apply {
                                            action = "DEPLOY_CANCEL"
                                        },
                                    )
                                },
                            )
                        }

                        if (!isDeploying && lastResult.isNotBlank() && lastResult != dismissedResult) {
                            V2DeployResultCard(
                                result = lastResult,
                                onDismiss = {
                                    dismissedResult = lastResult
                                    DeployManager.clearResult()
                                },
                            )
                        }
                    }

                    deployServers
                        .filterNot { host.isNotBlank() && it.profile == savedSettings.profile }
                        .forEach { server ->
                            V2CompactServerCard(
                                server = server,
                                current = server.profile == savedSettings.profile,
                                fallbackVersion = bundledComparable,
                                onClick = {
                                    if (!actionsBusy) {
                                        if (tunnelRunning) {
                                            showDeployToast("Остановите туннель перед сменой сервера")
                                        } else {
                                            scope.launch {
                                                settingsStore.saveActiveProfile(server.profile)
                                            }
                                        }
                                    }
                                },
                            )
                        }
                }
            }
        }

    }

    // Модалка (ModalBottomSheet) для заполнения и сохранения данных сервера
    if (showConfigSheet) {
        V2ServerConfigSheet(
            serverName = serverName,
            onServerNameChange = { serverName = it },
            host = host,
            onHostChange = { host = it },
            sshLogin = sshLogin,
            onSshLoginChange = { sshLogin = it },
            sshPassword = sshPassword,
            onSshPasswordChange = { sshPassword = it },
            sshPort = sshPort,
            onSshPortChange = { sshPort = it },
            sshKeysMode = sshKeysMode,
            onSshKeysModeChange = { enabled -> sshKeysMode = enabled },
            sshKeysFilled = sshKeysFilled,
            isDeploying = isDeploying,
            onOpenSshKeys = { showSshKeysDialog = true },
            onOpenSecrets = { openSecretsDialog() },
            onSave = {
                saveServerSettings()
                showConfigSheet = false
                addingServer = false
                addingServerSwitchedProfile = false
                previousActiveProfile = -1
            },
            onDeleteServer = if (!addingServer && host.isNotBlank()) {
                {
                    showConfigSheet = false
                    addingServer = false
                    addingServerSwitchedProfile = false
                    previousActiveProfile = -1
                    showDeleteAppServerDialog = true
                }
            } else null,
            onDismiss = {
                val restoreProfile = if (addingServerSwitchedProfile && previousActiveProfile >= 0) {
                    previousActiveProfile
                } else {
                    null
                }
                showConfigSheet = false
                addingServer = false
                addingServerSwitchedProfile = false
                previousActiveProfile = -1
                resetServerDraft()
                if (restoreProfile != null) {
                    scope.launch {
                        settingsStore.clearDeployProfile()
                        settingsStore.saveActiveProfile(restoreProfile)
                    }
                }
            },
            connectionOnly = addingServer,
        )
    }

    if (showDeployOptions) {
        val deployMode = runCatching { DeployActionMode.valueOf(deployOptionsModeKey) }
            .getOrDefault(DeployActionMode.Install)
        V2DeployOptionsSheet(
            mode = deployMode,
            host = host,
            sshPort = sshPort,
            peerPort = peerPort,
            onPeerPortChange = { peerPort = it },
            webPort = webPort,
            onWebPortChange = { webPort = it },
            manualPorts = manualPorts,
            onManualPortsChange = { enabled ->
                manualPorts = enabled
                if (!enabled) {
                    peerPort = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT.toString()
                    webPort = CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT.toString()
                }
            },
            dockerInstall = dockerInstall,
            onDockerInstallChange = { dockerInstall = it },
            mainPasswordConfigured = mainPasswordConfigured,
            webPanelConfigured = webPanelConfigured,
            onOpenSecrets = { openSecretsDialog() },
            onOpenDockerInfo = { showDockerInfoDialog = true },
            onConfirm = {
                val action = pendingPlanAction
                val effective = resolveServerPorts(manualPorts, sshPort, peerPort, webPort)
                val peer = if (manualPorts) effective.peer else action?.peerPort ?: effective.peer
                val web = if (manualPorts) effective.web else action?.webPort ?: effective.web
                showDeployOptions = false
                pendingPlanAction = null
                deployOptionsBackup = null
                plannedInventory = null
                launchDeploy(
                    mode = deployMode,
                    instance = action?.instance ?: DeployInstance.MAIN,
                    peerPortOverride = peer,
                    webPortOverride = web,
                )
            },
            onDismiss = {
                showDeployOptions = false
                pendingPlanAction = null
                plannedInventory = null
                deployOptionsBackup?.let { backup ->
                    manualPorts = backup.manualPorts
                    peerPort = backup.peerPort
                    webPort = backup.webPort
                    dockerInstall = backup.dockerInstall
                }
                deployOptionsBackup = null
            },
        )
    }

    // Диалог подтверждения удаления сервера из приложения
    if (showDeleteAppServerDialog) {
        V2DialogContainer(
            title = "Удалить из приложения?",
            subtitle = "Удаление сохранённых данных сервера",
            icon = Icons.Filled.Delete,
            onDismiss = { showDeleteAppServerDialog = false },
            actions = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Отмена",
                        onClick = { showDeleteAppServerDialog = false },
                        style = V2PillStyle.Contrast,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                    V2PillButton(
                        text = "Удалить",
                        onClick = {
                            showDeleteAppServerDialog = false
                            scope.launch {
                                clearServerDraft()
                                liveInspections.remove(savedSettings.profile)
                                settingsStore.clearDeployProfile()
                                showDeployToast("Сервер и сохранённые секреты удалены из приложения")
                            }
                        },
                        style = V2PillStyle.Danger,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                }
            },
        ) {
            Text(
                text = "Будут удалены сохранённые IP, логин, SSH-ключи, пароли, порты и путь панели. " +
                    "Служба и файлы на самом сервере затронуты не будут.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                lineHeight = 20.sp,
            )
        }
    }

    deployPlan?.let { plan ->
        V2DeployPlanSheet(
            host = host,
            plan = plan,
            profilePeerPort = resolveServerPorts(manualPorts, sshPort, peerPort, webPort).peer,
            onChoose = { action -> choosePlanAction(action) },
            onDismiss = { deployPlan = null },
        )
    }

    removalCandidates?.let { servers ->
        V2ServerRemovalSheet(
            host = host,
            servers = servers,
            notes = removalNotesUi,
            onChoose = { server -> pendingRemovalServer = server },
            onDismiss = {
                removalCandidates = null
                removalNotesUi = emptyList()
            },
        )
    }

    pendingDestructiveAction?.let { action ->
        V2UninstallConfirmDialog(
            onDismiss = { pendingDestructiveAction = null },
            onConfirm = {
                val confirmed = action
                pendingDestructiveAction = null
                launchDeploy(
                    mode = DeployActionMode.Reinstall,
                    instance = confirmed.instance,
                    peerPortOverride = confirmed.peerPort,
                    webPortOverride = confirmed.webPort,
                )
            },
            title = "Переустановка сервера",
            description = action.detail,
            confirmLabel = "Переустановить",
        )
    }

    pendingRemovalServer?.let { server ->
        V2UninstallConfirmDialog(
            onDismiss = { pendingRemovalServer = null },
            onConfirm = {
                val confirmed = server
                pendingRemovalServer = null
                launchUninstall(confirmed)
            },
            title = "Удаление CSQTT",
            description = "Будет удалён CSQTT на порту ${server.peerPortOrDefault()} " +
                "(${server.name}). Служба, бинарник и конфигурация будут удалены.\n\nЭто действие необратимо.",
            confirmLabel = "Удалить",
        )
    }

    if (showSecretsDialog) {
        V2DeploySecretsDialog(
            settingsStore = settingsStore,
            initialMainPass = savedMainPass,
            initialSshLogin = sshLogin,
            initialSshPass = sshPassword,
            initialWebLogin = savedWebLogin,
            initialWebPass = savedWebPassword,
            remoteWebCreds = remoteWebCreds,
            onSaved = { mainPass, webLogin, webPass ->
                val pending = pendingDeployAfterSecrets
                pendingDeployAfterSecrets = null
                if (pending != null) {
                    launchDeploy(
                        mode = pending.mode,
                        instance = pending.instance,
                        peerPortOverride = pending.peerPort,
                        webPortOverride = pending.webPort,
                        credentialsOverride = DeployCredentials(mainPass, webLogin, webPass),
                    )
                }
            },
            onDismiss = {
                showSecretsDialog = false
                pendingDeployAfterSecrets = null
                remoteWebCreds = null
            },
        )
    }

    if (showSshKeysDialog) {
        V2SshKeysDialog(
            initialPrivateKey = savedSshPrivateKey,
            initialPassphrase = savedSshKeyPassphrase,
            initialCertificate = savedSshCertificate,
            onSave = { privateKey, keyPassphrase, certificate ->
                showSshKeysDialog = false
                scope.launch { settingsStore.saveSshKeys(privateKey, keyPassphrase, certificate) }
            },
            onDismiss = { showSshKeysDialog = false },
        )
    }

    if (showDockerInfoDialog) {
        V2DockerInstallInfoDialog(onDismiss = { showDockerInfoDialog = false })
    }

    if (showRegenConfirmDialog) {
        V2DialogContainer(
            title = "Смена пути панели",
            subtitle = "Перезапуск службы на сервере",
            icon = Icons.Filled.Refresh,
            onDismiss = { showRegenConfirmDialog = false },
            actions = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Отмена",
                        onClick = { showRegenConfirmDialog = false },
                        style = V2PillStyle.Contrast,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                    V2PillButton(
                        text = "Сменить",
                        onClick = {
                            showRegenConfirmDialog = false
                            changeSecretPath()
                        },
                        style = V2PillStyle.Danger,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                }
            },
        ) {
            Text(
                text = "Новый путь будет применён на сервере, служба CSQTT перезапустится. " +
                    "Старая ссылка на панель перестанет работать. Продолжить?",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                lineHeight = 20.sp,
            )
        }
    }
}

/** Компактная плашка сохранённого сервера, который сейчас не выбран. */
@Composable
private fun V2CompactServerCard(
    server: DeployServerInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    fallbackVersion: String = "",
) {
    val colors = V2Theme.colors
    val cleanVersion = comparableServerVersion(server.installedVersion).ifBlank {
        server.installedVersion
            .replace(Regex("(?i)^(?:csqtt|focsq)\\s*"), "")
            .replace(Regex("^[vV]\\s*([vV]\\s*)?"), "")
            .trim()
            .ifBlank {
                if (server.configured) "—" else fallbackVersion.ifBlank { "—" }
            }
    }
    V2Card(
        modifier = modifier.fillMaxWidth(),
        shape = V2Shapes.Hero,
        color = colors.bottomSurface,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = if (current) "${server.name} · текущий" else server.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = V2VersionFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    ),
                    color = if (current) colors.accent else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Открыть сервер",
                    tint = colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                V2IpPill(ip = server.ip.ifBlank { "IP не указан" })
                V2StatusPill(configured = server.configured)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                V2ServerVersionPill(versionText = cleanVersion)
            }
        }
    }
}

/** Плашка с сервером + круглые шилдики (IP, статус, версия) + результат диагностики + кнопка корзины */
@Composable
private fun V2ServerItemCard(
    title: String,
    ip: String,
    configured: Boolean,
    version: String,
    expanded: Boolean,
    remembered: ServerInspectionResult?,
    onClick: () -> Unit,
    onDeleteFromApp: () -> Unit,
    onOpenPanel: () -> Unit,
    onCopyIp: () -> Unit,
    onCopyPanelLink: () -> Unit,
    result: ServerInspectionResult?,
    isInspecting: Boolean,
    webLogin: String = "",
    webPass: String = "",
    modifier: Modifier = Modifier,
    actionsBusy: Boolean = false,
    actionsContent: @Composable () -> Unit = {},
) {
    val colors = V2Theme.colors
    val known = result?.takeIf { it.isSuccess } ?: remembered?.takeIf { it.isSuccess }
    val knownVersion = known?.installedVersion?.takeIf {
        it.isNotBlank() && it != "—" && it != "не определена"
    }
    val rawVersion = knownVersion ?: if (configured) "" else version
    val cleanVersion = comparableServerVersion(rawVersion).ifBlank {
        rawVersion
            .replace(Regex("(?i)^(?:csqtt|focsq)\\s*"), "")
            .replace(Regex("^[vV]\\s*([vV]\\s*)?"), "")
            .trim()
            .ifBlank { if (configured) "—" else version.ifBlank { "—" } }
    }
    val panelAvailable = known?.takeIf(::isFreshInspection)?.panelAvailable

    V2Card(
        modifier = modifier.fillMaxWidth(),
        shape = V2Shapes.Hero,
        color = colors.bottomSurface,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Верхняя строка: иконка, имя сервера, корзина удаления, шеврон
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = V2VersionFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Кнопка корзины (удаление сервера из приложения)
                Surface(
                    shape = CircleShape,
                    color = colors.field,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(
                            role = Role.Button,
                            onClick = onDeleteFromApp,
                        ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Удалить из приложения",
                            tint = colors.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
                    tint = colors.textMuted,
                    modifier = Modifier.size(22.dp),
                )
            }

            // Первый ряд круглых шилдиков (IP и статус развёртывания)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Шилдик IP
                V2IpPill(
                    ip = ip.ifBlank { "IP не указан" },
                    onCopy = onCopyIp,
                )

                // Шилдик статуса развёртывания
                V2StatusPill(configured = configured)
            }

            // Второй ряд круглых шилдиков (версия сервера и кнопка веб-панели с актуальным путём)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Шилдик версии сервера
                V2ServerVersionPill(versionText = cleanVersion)

                // Плашка веб-панели
                V2OpenPanelPill(
                    configured = configured,
                    panelAvailable = panelAvailable,
                    onClick = onOpenPanel,
                    onLongClick = onCopyPanelLink,
                )
            }

// Результат диагностики VPS прямо внутри плашки сервера
            AnimatedVisibility(
                visible = expanded && (isInspecting || result != null),
                enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(180)),
                exit = fadeOut(tween(140)) + shrinkVertically(animationSpec = tween(140)),
            ) {
                if (isInspecting) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.field)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = "Опрос SSH…",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = V2BodyFamily,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = colors.accent,
                        )
                    }
                } else if (result != null) {
                    if (!result.isSuccess) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.field)
                                .padding(12.dp),
                        ) {
                            Text(
                                text = "Не удалось подключиться: ${result.message}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = V2BodyFamily,
                                    fontSize = 13.sp,
                                ),
                                color = colors.textSecondary,
                            )
                        }
                    } else {
                        V2ServerStateBlock(
                            inspection = result,
                            bundledVersion = version,
                            webLogin = webLogin,
                            webPass = webPass,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(180)),
                exit = fadeOut(tween(140)) + shrinkVertically(animationSpec = tween(140)),
            ) {
                actionsContent()
            }
        }
    }
}

/** Круглый шилдик IP-адреса */
@Composable
private fun V2IpPill(
    ip: String,
    onCopy: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(V2Shapes.Pill)
            .background(V2Palette.InkDark)
            .then(
                if (onCopy != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClick = onCopy,
                    )
                } else Modifier,
            )
            .padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(V2Palette.OnInkDark),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "IP",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = V2DisplayFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                ),
                color = V2Palette.InkDark,
                modifier = Modifier.offset(x = (-0.5).dp),
            )
        }
        Text(
            text = ip,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = V2DisplayFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
            ),
            color = V2Palette.OnInkDark,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onCopy != null) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null,
                tint = V2Palette.OnInkDark.copy(alpha = 0.55f),
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** Круглый шилдик статуса "Развёрнут" / "Не развёрнут" */
@Composable
private fun V2StatusPill(
    configured: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    Surface(
        shape = V2Shapes.Pill,
        color = if (configured) colors.accentSoft else colors.surfaceElevated,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (configured) colors.accent else colors.textMuted),
            )
            Text(
                text = if (configured) "Развёрнут" else "Не развёрнут",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = V2DisplayFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                ),
                color = if (configured) colors.textPrimary else colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** Круглый шилдик версии сервера */
@Composable
private fun V2ServerVersionPill(
    versionText: String,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    Surface(
        shape = V2Shapes.Pill,
        color = colors.field,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "v",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = V2DisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    ),
                    color = colors.accent,
                )
            }
            Text(
                text = versionText,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = V2VersionFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                ),
                color = colors.textPrimary,
                maxLines = 1,
            )
        }
    }
}

/** Кнопка-шилдик перехода на веб-панель сервера с актуальным путём */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun V2OpenPanelPill(
    configured: Boolean,
    panelAvailable: Boolean? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    val isUnavailable = configured && panelAvailable == false
    val active = configured && !isUnavailable
    Surface(
        shape = V2Shapes.Pill,
        color = if (active) colors.accentSoft else colors.field,
        modifier = modifier
            .clip(V2Shapes.Pill)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (active) colors.accent else colors.surfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = if (active) colors.bottomSurface else colors.textMuted,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = if (isUnavailable) "Панель недоступна" else "Веб-панель",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = V2DisplayFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                ),
                color = if (active) colors.textPrimary else colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun V2DeployProgressCard(
    progress: Float,
    currentStep: String,
    onCancel: () -> Unit,
) {
    val colors = V2Theme.colors
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (progress <= 0.01f) {
            snap()
        } else {
            tween(durationMillis = 320, easing = FastOutSlowInEasing)
        },
        label = "deploy_progress",
    )

    V2Card(
        modifier = Modifier.fillMaxWidth(),
        shape = V2Shapes.Hero,
        color = colors.bottomSurface,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(V2Shapes.Pill)
                        .background(colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Build,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Text(
                    text = currentStep.ifBlank { "Подготовка…" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = V2DisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = V2DisplayFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = colors.accent,
                )
            }
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(V2Shapes.Pill),
                color = colors.accent,
                trackColor = colors.outline.copy(alpha = 0.35f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Можно свернуть экран — установка идёт в фоне",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 11.sp,
                    ),
                    color = colors.textMuted,
                )
                V2PillButton(
                    text = "Отменить",
                    onClick = onCancel,
                    style = V2PillStyle.Outline,
                    height = 36.dp,
                    horizontalPadding = 12.dp,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun V2DeployResultCard(
    result: String,
    onDismiss: () -> Unit,
) {
    val colors = V2Theme.colors
    val success = result == "success"
    val cancelled = !success && result.contains("отмен", ignoreCase = true)
    val tint = when {
        success -> colors.success
        cancelled -> colors.textSecondary
        else -> colors.error
    }

    V2Card(
        modifier = Modifier.fillMaxWidth(),
        shape = V2Shapes.Hero,
        color = tint.copy(alpha = 0.12f),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (success) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = when {
                        success -> "Готово"
                        cancelled -> "Отменено"
                        else -> "Ошибка установки"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = V2DisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = if (success || cancelled) colors.textPrimary else colors.error,
                )
                Text(
                    text = if (success) {
                        "Операция завершена. Состояние сервера обновлено."
                    } else {
                        result
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    ),
                    color = colors.textSecondary,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            V2CircleIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Скрыть",
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun V2ServerStateBlock(
    inspection: ServerInspectionResult,
    bundledVersion: String,
    webLogin: String = "",
    webPass: String = "",
) {
    val colors = V2Theme.colors
    var panelVisible by rememberSaveable { mutableStateOf(false) }
    val status = inspection.serviceStatus
    val isActive = status in listOf("systemd_active", "docker_active", "process_running")
    val installed = comparableServerVersion(inspection.installedVersion)
    val bundled = comparableServerVersion(bundledVersion)
    val updateAvailable = installed.isNotBlank() &&
        bundled.isNotBlank() &&
        isNewerVersion(installed, bundled)
    val time = formatInspectionTime(inspection.checkedAtMs)
    val serviceValue = when (status) {
        "systemd_active" -> "systemd активна"
        "docker_active" -> "Docker активен"
        "process_running" -> "процесс запущен"
        else -> "не запущена"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.field)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Состояние",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = V2DisplayFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                ),
                color = colors.textPrimary,
            )
            if (updateAvailable) {
                Text(
                    text = "доступно обновление",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = V2BodyFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = colors.accent,
                )
            }
        }

        OverviewRow(
            label = "Служба",
            value = serviceValue,
            valueColor = when {
                isActive -> colors.success
                else -> colors.error
            },
        )
        OverviewRow(
            label = "Версия",
            value = when {
                installed.isBlank() -> "неизвестна"
                updateAvailable && bundled.isNotBlank() -> "$installed → $bundled"
                else -> installed
            },
            valueColor = if (updateAvailable) colors.accent else colors.textPrimary,
        )
        OverviewRow(
            label = "Проверено",
            value = time.ifBlank { "—" },
            valueColor = colors.textMuted,
        )

        val panelCredsSet = webLogin.isNotBlank() || webPass.isNotBlank()
        val panelValue = when {
            !panelCredsSet -> "не задана"
            panelVisible -> listOfNotNull(
                webLogin.takeIf { it.isNotBlank() },
                webPass.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            else -> "••••••••"
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = panelCredsSet,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { panelVisible = !panelVisible },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Панель",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = V2BodyFamily),
                color = colors.textMuted,
            )
            Text(
                text = panelValue,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (panelVisible) colors.textPrimary else colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (panelCredsSet) {
                Icon(
                    imageVector = if (panelVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (panelVisible) "Скрыть данные панели" else "Показать данные панели",
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun OverviewRow(
    label: String,
    value: String,
    valueColor: Color,
) {
    val colors = V2Theme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = V2BodyFamily),
            color = colors.textMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = V2BodyFamily,
                fontWeight = FontWeight.SemiBold,
            ),
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val DEPLOY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DEPLOY_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun formatInspectionTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val checked = Instant.ofEpochMilli(timestampMs).atZone(zone)
    val today = Instant.now().atZone(zone).toLocalDate()
    return if (checked.toLocalDate() == today) {
        checked.format(DEPLOY_TIME_FORMATTER)
    } else {
        checked.format(DEPLOY_DATE_TIME_FORMATTER)
    }
}

@Composable
private fun V2DeployActionMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val colors = V2Theme.colors
    val titleColor = when {
        !enabled -> colors.textMuted
        tint != null -> tint
        else -> colors.textPrimary
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.field),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) (tint ?: colors.accent) else colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = V2DisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = titleColor,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 12.sp,
                    ),
                    color = colors.textMuted,
                )
            }
        }
    }
}
