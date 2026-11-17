package com.csqtt.client.ui.legacy

import com.csqtt.client.showRaisedToast
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.csqtt.client.TunnelService
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.DeployManager
import com.csqtt.client.DeploySettingsSnapshot
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.BuildConfig
import com.csqtt.client.CsqttConstants
import com.csqtt.client.R
import com.csqtt.client.ui.legacy.dialogs.DeploySecretsDialog
import com.csqtt.client.ui.legacy.dialogs.SshKeysDialog
import com.csqtt.client.ui.legacy.dialogs.UninstallConfirmDialog
import com.csqtt.client.ui.legacy.components.CsqttBanner
import com.csqtt.client.ui.legacy.components.CsqttScreen
import com.csqtt.client.ui.legacy.components.CsqttSettingRow
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes
import com.csqtt.client.ui.legacy.design.CsqttSpacing
import com.csqtt.client.ui.legacy.design.CsqttTheme
import com.csqtt.client.ui.legacy.utils.TUNNEL_PASSWORD_RULE
import com.csqtt.client.ui.legacy.utils.isValidTunnelPassword
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where a deploy installs (deploy.sh CSQTT_INSTANCE), picked by the server check (ServerCheck.kt), never by the user:
 * MAIN — the standard place (csqtt1, /etc/csqtt, csqtt.service), SIDE — a second, independent server (csqtt25) next to
 * an older stock one, which keeps serving its own clients.
 */
internal enum class DeployInstance(val wire: String, val defaultPeerPort: Int, val defaultWebPort: Int) {
    SIDE("side", CsqttConstants.Network.DEFAULT_SIDE_SERVER_PEER_PORT, CsqttConstants.Network.DEFAULT_SIDE_SERVER_WEB_PORT),
    MAIN("main", CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT, CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT),
}

@Immutable
internal data class DeployUiState(
    val host: String = "",
    val sshLogin: String = "",
    val sshPassword: String = "",
    val manualPorts: Boolean = false,
    val sshPort: String = CsqttConstants.Network.DEFAULT_SSH_PORT.toString(),
    val peerPort: String = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT.toString(),
    val webPort: String = CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT.toString(),
    val mainPasswordConfigured: Boolean = false,
    val webPanelConfigured: Boolean = false,
    val sshKeysMode: Boolean = false,
    val dockerInstall: Boolean = false,
    val secretPathEnabled: Boolean = false,
    val secretPathUuid: String = "",
    val sshKeysFilled: Int = 0,
    val isDeploying: Boolean = false,
    val progress: Float = 0f,
    val currentStep: String = "",
    val showValidation: Boolean = false,
) {
    val portsValid: Boolean
        get() = !manualPorts || listOf(sshPort, peerPort, webPort).all(::isValidPort)

    val authorizationConfigured: Boolean
        get() = webPanelConfigured && mainPasswordConfigured

    val sshAuthReady: Boolean
        get() = sshLogin.isNotBlank() && if (sshKeysMode) sshKeysFilled == 1 else sshPassword.isNotBlank()

    val canInstall: Boolean
        get() = host.isNotBlank() && sshAuthReady && authorizationConfigured && portsValid

    val canUninstall: Boolean
        get() = host.isNotBlank() && sshAuthReady && portsValid

    val panelDisplayUrl: String
        get() {
            val h = host.ifBlank { "IP" }
            // the panel of the server this profile points at (a deploy stores the server's ports on success)
            val p = resolveServerPorts(manualPorts, sshPort, peerPort, webPort).web
            val u = if (secretPathUuid.isNotBlank()) {
                if (secretPathUuid.startsWith("/")) secretPathUuid else "/$secretPathUuid"
            } else {
                "/uuid"
            }
            return "https://$h:$p$u"
        }
}

internal sealed interface DeployAction {
    data class HostChanged(val value: String) : DeployAction
    data class LoginChanged(val value: String) : DeployAction
    data class PasswordChanged(val value: String) : DeployAction
    data class ManualPortsChanged(val enabled: Boolean) : DeployAction
    data class SshKeysModeChanged(val enabled: Boolean) : DeployAction
    data class DockerInstallChanged(val enabled: Boolean) : DeployAction
    data object RegenerateSecretPath : DeployAction
    data object EditSshKeys : DeployAction
    data object DockerInfo : DeployAction
    data class SshPortChanged(val value: String) : DeployAction
    data class PeerPortChanged(val value: String) : DeployAction
    data class WebPortChanged(val value: String) : DeployAction
    data object EditAuthorization : DeployAction
    data object Install : DeployAction
    data object Uninstall : DeployAction
    data object OpenControlPanel : DeployAction
    data object PanelInfo : DeployAction
}

internal fun passwordToSynchronizeAfterDeploy(deploySucceeded: Boolean, mainPassword: String): String? =
    mainPassword.takeIf { deploySucceeded && it.isNotBlank() }

internal fun isDeployMainPasswordValid(password: String): Boolean = isValidTunnelPassword(password)

internal fun isValidPort(value: String): Boolean = value.toIntOrNull() in 1..65535

@Immutable
internal data class EffectiveServerPorts(
    val ssh: Int,
    val peer: Int,
    val web: Int,
)

internal fun resolveServerPorts(
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
        ssh = sshPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: CsqttConstants.Network.DEFAULT_SSH_PORT,
        peer = peerPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT,
        web = webPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT,
    )
}

internal fun hasWebPanelCredentials(login: String, password: String): Boolean =
    login.isNotBlank() && password.isNotBlank()

private fun showDeployToast(context: android.content.Context, message: String) {
    Handler(Looper.getMainLooper()).post {
        context.showRaisedToast(message, Toast.LENGTH_LONG)
    }
}

@Composable
internal fun DeployTab(
    settingsStore: SettingsStore,
    savedSettings: DeploySettingsSnapshot,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { DeployManager.init(context) }

    if (!savedSettings.isLoaded) return

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
    val savedSecretPathEnabled = savedSettings.secretPathEnabled
    val savedSecretPathUuid = savedSettings.secretPathUuid
    val isDeploying by DeployManager.isDeploying.collectAsStateWithLifecycle()
    val deployProgress by DeployManager.deployProgress.collectAsStateWithLifecycle()
    val currentStep by DeployManager.currentStep.collectAsStateWithLifecycle()

    var host by rememberSaveable(savedSettings.profile) { mutableStateOf(savedIp) }
    var sshLogin by rememberSaveable(savedSettings.profile) { mutableStateOf(savedLogin) }
    var sshPassword by rememberSaveable(savedSettings.profile) { mutableStateOf(savedPassword) }
    var sshPort by rememberSaveable(savedSettings.profile) {
        mutableStateOf(savedSshPort.ifBlank { CsqttConstants.Network.DEFAULT_SSH_PORT.toString() })
    }
    var peerPort by rememberSaveable(savedSettings.profile) { mutableStateOf(savedServerPeerPort.toString()) }
    var webPort by rememberSaveable(savedSettings.profile) { mutableStateOf(savedServerWebPort.toString()) }
    var dockerInstall by rememberSaveable(savedSettings.profile) { mutableStateOf(savedDockerInstall) }
    var secretPathEnabled by rememberSaveable(savedSettings.profile) { mutableStateOf(savedSecretPathEnabled) }
    var generalEdited by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var credentialsEdited by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var portsEdited by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var showValidation by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var showSecretsDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var showPanelInfoDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var showSshKeysDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var showDockerInfoDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    // the server check's result while its dialog is open (a rotation closes it: the check is run again)
    var checkPlan by remember(savedSettings.profile) { mutableStateOf<DeployPlan?>(null) }
    var removable by remember(savedSettings.profile) { mutableStateOf<List<FoundServer>?>(null) }
    var removeNotes by remember(savedSettings.profile) { mutableStateOf<List<String>>(emptyList()) }
    var pendingReplace by remember(savedSettings.profile) { mutableStateOf<PlanAction?>(null) }
    var pendingRemove by remember(savedSettings.profile) { mutableStateOf<FoundServer?>(null) }

    LaunchedEffect(savedSettings.profile, savedIp) {
        if (!generalEdited) {
            host = savedIp
        }
    }
    LaunchedEffect(savedSettings.profile, savedLogin, savedPassword) {
        if (!credentialsEdited) {
            sshLogin = savedLogin
            sshPassword = savedPassword
        }
    }
    LaunchedEffect(savedSettings.profile, savedSshPort, savedServerPeerPort, savedServerWebPort) {
        if (!portsEdited) {
            sshPort = savedSshPort.ifBlank { CsqttConstants.Network.DEFAULT_SSH_PORT.toString() }
            peerPort = savedServerPeerPort.toString()
            webPort = savedServerWebPort.toString()
        }
    }
    LaunchedEffect(savedSettings.profile, savedDockerInstall) {
        dockerInstall = savedDockerInstall
    }
    LaunchedEffect(savedSettings.profile, savedSecretPathUuid) {
        if (savedSecretPathUuid.isBlank()) {
            settingsStore.regenerateDeploySecretPathUuid()
        }
    }

    LaunchedEffect(savedSettings.profile, host, generalEdited) {
        if (!generalEdited) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        settingsStore.saveDeploy(host)
    }
    LaunchedEffect(savedSettings.profile, sshLogin, sshPassword, credentialsEdited, savedMainPass, savedWebLogin, savedWebPassword) {
        if (!credentialsEdited) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        settingsStore.saveDeploySecrets(savedMainPass, sshLogin, sshPassword, savedWebLogin, savedWebPassword)
    }
    LaunchedEffect(savedSettings.profile, sshPort, peerPort, webPort, portsEdited) {
        if (!portsEdited) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        val ssh = sshPort.toIntOrNull()
        val peer = peerPort.toIntOrNull()
        val web = webPort.toIntOrNull()
        if (ssh in 1..65535 && peer in 1..65535 && web in 1..65535) {
            settingsStore.savePorts(peer!!, web!!, ssh.toString())
        }
    }

    val state = DeployUiState(
        host = host,
        sshLogin = sshLogin,
        sshPassword = sshPassword,
        manualPorts = savedManualPorts,
        sshPort = sshPort,
        peerPort = peerPort,
        webPort = webPort,
        mainPasswordConfigured = isDeployMainPasswordValid(savedMainPass),
        webPanelConfigured = hasWebPanelCredentials(savedWebLogin, savedWebPassword),
        sshKeysMode = savedSshKeysMode,
        dockerInstall = dockerInstall,
        secretPathEnabled = secretPathEnabled,
        secretPathUuid = savedSecretPathUuid,
        sshKeysFilled = if (savedSshPrivateKey.isNotBlank()) 1 else 0,
        isDeploying = isDeploying,
        progress = deployProgress,
        currentStep = currentStep,
        showValidation = showValidation,
    )

    val sshKeyArgs = Pair(if (savedSshKeysMode) savedSshPrivateKey else "", if (savedSshKeysMode) savedSshKeyPassphrase else "")

    // The read-only server check (deploy.sh inventory) every install and removal starts with: it decides what is offered.
    fun runServerCheck(onChecked: (ServerInventory) -> Unit) {
        if (DeployManager.isDeploying.value) return
        val ssh = resolveServerPorts(savedManualPorts, sshPort, peerPort, webPort).ssh
        DeployManager.startDeploy()
        DeployManager.updateProgress(0.1f, "Проверка сервера…")
        DeployManager.scope.launch {
            try {
                val inventory = performServerCheck(
                    context = context,
                    host = host,
                    user = sshLogin,
                    pass = if (savedSshKeysMode) "" else sshPassword,
                    port = ssh,
                    privateKey = sshKeyArgs.first,
                    keyPassphrase = sshKeyArgs.second,
                )
                DeployManager.stopDeploy()
                withContext(Dispatchers.Main) { onChecked(inventory) }
            } catch (e: CancellationException) {
                DeployManager.stopDeploy("Проверка отменена")
                throw e
            } catch (e: Exception) {
                val message = friendlyDeployError(e.message)
                DeployManager.writeError("Server check error (${e.javaClass.simpleName}): ${e.message}")
                TunnelManager.addDeployErrorLog(message)
                DeployManager.stopDeploy(message)
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        }
    }

    fun startDeploy(chosen: PlanAction) {
        if (DeployManager.isDeploying.value) return
        if (!state.canInstall || !isDeployMainPasswordValid(savedMainPass)) return
        val instance = chosen.instance
        val deployProfile = savedSettings.profile
        val effectiveSshPort = resolveServerPorts(savedManualPorts, sshPort, peerPort, webPort).ssh
        val effectivePeerPort = chosen.peerPort
        val effectiveWebPort = chosen.webPort
        val deployPrivateKey = if (savedSshKeysMode) savedSshPrivateKey else ""
        val deployKeyPassphrase = if (savedSshKeysMode) savedSshKeyPassphrase else ""
        val deployCertificate = if (savedSshKeysMode) savedSshCertificate else ""
        val deployPassword = if (savedSshKeysMode) "" else sshPassword
        val effectiveMainPass = savedMainPass
        scope.launch {
            settingsStore.saveDeploy(host)
            settingsStore.saveDeploySecrets(effectiveMainPass, sshLogin, sshPassword, savedWebLogin, savedWebPassword)
            // the ports as the form holds them (they are the profile's connection port): the deployed server's ports
            // are written by saveDeployedServer only once the deploy succeeded
            if (savedManualPorts) {
                settingsStore.savePorts(
                    peerPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: effectivePeerPort,
                    webPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: effectiveWebPort,
                    effectiveSshPort.toString(),
                )
            }
        }
        DeployManager.startDeploy()
        DeployManager.scope.launch {
            try {
                context.startForegroundService(Intent(context, TunnelService::class.java).apply { action = "DEPLOY_START" })
                val success = performDeploy(
                    context = context,
                    host = host,
                    user = sshLogin,
                    pass = deployPassword,
                    port = effectiveSshPort,
                    mainPass = effectiveMainPass,
                    webLogin = savedWebLogin,
                    webPass = savedWebPassword,
                    peerPort = effectivePeerPort,
                    webPort = effectiveWebPort,
                    onProgress = DeployManager::updateProgress,
                    privateKey = deployPrivateKey,
                    keyPassphrase = deployKeyPassphrase,
                    certificate = deployCertificate,
                    installInDocker = dockerInstall,
                    secretPath = savedSecretPathUuid.ifBlank { "" },
                    instance = instance,
                )
                if (success) {
                    passwordToSynchronizeAfterDeploy(success, effectiveMainPass)?.let { mainPassword ->
                        settingsStore.saveConnectionPassword(mainPassword, deployProfile)
                        TunnelManager.addDeployInfoLog("Пароль подключения синхронизирован с паролем сервера")
                    }
                    settingsStore.saveDeployedServer(deployProfile, host, effectivePeerPort, effectiveWebPort)
                    // the port fields are the profile's connection port: they follow the server just deployed. Once a
                    // field was edited they stopped following the store and kept the old ports, and a later edit of any
                    // port field saved the old peer port back, moving the connection off the new server
                    withContext(Dispatchers.Main) {
                        peerPort = effectivePeerPort.toString()
                        webPort = effectiveWebPort.toString()
                        portsEdited = false
                    }
                    if (effectivePeerPort != CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT) {
                        TunnelManager.addDeployInfoLog("Подключение настроено на порт сервера $effectivePeerPort")
                    }
                    showDeployToast(context, if (chosen.kind == PlanKind.UPDATE) "Сервер обновлён" else "Сервер установлен")
                } else {
                    val message = DeployManager.lastResult.value.ifBlank { "Ошибка установки" }
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
            } catch (e: CancellationException) {
                DeployManager.stopDeploy("Установка отменена")
                throw e
            } catch (e: Exception) {
                val message = friendlyDeployError(e.message)
                DeployManager.writeError(
                    "Deploy UI error (${e.javaClass.simpleName}): ${e.message}\n" +
                        e.stackTraceToString().take(1200)
                )
                TunnelManager.addDeployErrorLog(message)
                DeployManager.stopDeploy(message)
                scope.launch { snackbarHostState.showSnackbar(message) }
            } finally {
                runCatching {
                    context.startService(Intent(context, TunnelService::class.java).apply { action = "DEPLOY_STOP" })
                }.onFailure { DeployManager.writeError("Не удалось остановить foreground-режим деплоя: ${it.message}") }
            }
        }
    }

    fun startUninstall(server: FoundServer) {
        if (DeployManager.isDeploying.value) return
        val instance = server.deployInstance()
        val effectiveSshPort = resolveServerPorts(savedManualPorts, sshPort, peerPort, webPort).ssh
        val effectivePeerPort = server.peerPortOrDefault()
        val uninstallPrivateKey = if (savedSshKeysMode) savedSshPrivateKey else ""
        val uninstallKeyPassphrase = if (savedSshKeysMode) savedSshKeyPassphrase else ""
        val uninstallCertificate = if (savedSshKeysMode) savedSshCertificate else ""
        val uninstallPassword = if (savedSshKeysMode) "" else sshPassword
        DeployManager.startDeploy()
        DeployManager.scope.launch {
            try {
                context.startForegroundService(Intent(context, TunnelService::class.java).apply { action = "DEPLOY_START" })
                val success = performUninstall(
                    context = context,
                    host = host,
                    user = sshLogin,
                    pass = uninstallPassword,
                    port = effectiveSshPort,
                    peerPort = effectivePeerPort,
                    onProgress = DeployManager::updateProgress,
                    privateKey = uninstallPrivateKey,
                    keyPassphrase = uninstallKeyPassphrase,
                    certificate = uninstallCertificate,
                    instance = instance,
                )
                if (success) {
                    showDeployToast(context, "Сервер удалён")
                } else {
                    val message = DeployManager.lastResult.value.ifBlank { "Ошибка удаления" }
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
            } catch (e: CancellationException) {
                DeployManager.stopDeploy("Удаление отменено")
                throw e
            } catch (e: Exception) {
                val message = friendlyDeployError(e.message)
                DeployManager.writeError(
                    "Uninstall UI error (${e.javaClass.simpleName}): ${e.message}\n" +
                        e.stackTraceToString().take(1200)
                )
                TunnelManager.addDeployErrorLog(message)
                DeployManager.stopDeploy(message)
                scope.launch { snackbarHostState.showSnackbar(message) }
            } finally {
                runCatching {
                    context.startService(Intent(context, TunnelService::class.java).apply { action = "DEPLOY_STOP" })
                }.onFailure { DeployManager.writeError("Не удалось остановить foreground-режим удаления: ${it.message}") }
            }
        }
    }

    DeployScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onAction = { action ->
            when (action) {
                is DeployAction.HostChanged -> {
                    host = action.value.filterNot(Char::isWhitespace)
                    generalEdited = true
                }
                is DeployAction.LoginChanged -> {
                    sshLogin = action.value.filterNot(Char::isWhitespace)
                    credentialsEdited = true
                }
                is DeployAction.PasswordChanged -> {
                    sshPassword = sanitizeSshPassword(action.value)
                    credentialsEdited = true
                }
                is DeployAction.ManualPortsChanged -> {
                    if (!action.enabled) {
                        sshPort = CsqttConstants.Network.DEFAULT_SSH_PORT.toString()
                        peerPort = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT.toString()
                        webPort = CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT.toString()
                        portsEdited = false
                    }
                    scope.launch { settingsStore.saveManualPortsEnabled(action.enabled) }
                }
                is DeployAction.SshKeysModeChanged -> scope.launch {
                    settingsStore.saveSshKeysMode(action.enabled)
                }
                is DeployAction.DockerInstallChanged -> {
                    dockerInstall = action.enabled
                    scope.launch { settingsStore.saveDockerInstall(action.enabled) }
                }
                DeployAction.RegenerateSecretPath -> scope.launch {
                    val newUuid = settingsStore.regenerateDeploySecretPathUuid()
                    showDeployToast(context, "Секретный путь перегенерирован")
                }
                DeployAction.EditSshKeys -> showSshKeysDialog = true
                DeployAction.DockerInfo -> showDockerInfoDialog = true
                is DeployAction.SshPortChanged -> {
                    sshPort = action.value.filter(Char::isDigit).take(5)
                    portsEdited = true
                }
                is DeployAction.PeerPortChanged -> {
                    peerPort = action.value.filter(Char::isDigit).take(5)
                    portsEdited = true
                }
                is DeployAction.WebPortChanged -> {
                    webPort = action.value.filter(Char::isDigit).take(5)
                    portsEdited = true
                }
                DeployAction.EditAuthorization -> showSecretsDialog = true
                DeployAction.Install -> {
                    showValidation = true
                    if (state.canInstall) {
                        runServerCheck { inventory ->
                            checkPlan = planDeploy(
                                inventory = inventory,
                                manualPorts = if (savedManualPorts) resolveServerPorts(true, sshPort, peerPort, webPort) else null,
                                profilePeerPort = resolveServerPorts(savedManualPorts, sshPort, peerPort, webPort).peer,
                                dockerInstall = dockerInstall,
                                appVersion = BuildConfig.VERSION_NAME.removePrefix("v"),
                            )
                        }
                    } else {
                        if (!state.mainPasswordConfigured) {
                            scope.launch { snackbarHostState.showSnackbar("Пароль туннеля: $TUNNEL_PASSWORD_RULE") }
                        } else if (!state.webPanelConfigured) {
                            scope.launch { snackbarHostState.showSnackbar("Укажите логин и пароль web-панели в разделе «Авторизация»") }
                        } else if (!state.sshAuthReady) {
                            scope.launch { snackbarHostState.showSnackbar("Укажите данные SSH для подключения к серверу") }
                        } else if (state.host.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("Укажите IP сервера или домен") }
                        } else if (!state.portsValid) {
                            scope.launch { snackbarHostState.showSnackbar("Проверьте правильность портов") }
                        }
                    }
                }
                DeployAction.Uninstall -> if (state.canUninstall) runServerCheck { removable = removableServers(it); removeNotes = removalNotes(it) }
                DeployAction.PanelInfo -> showPanelInfoDialog = true
                DeployAction.OpenControlPanel -> {
                    if (host.isBlank()) {
                        scope.launch { snackbarHostState.showSnackbar("Сначала укажите IP сервера") }
                    } else {
                        val effectiveWebPort = resolveServerPorts(
                            savedManualPorts,
                            sshPort,
                            peerPort,
                            webPort,
                        ).web
                        val path = if (savedSettings.secretPathUuid.isNotBlank()) {
                            "/${savedSettings.secretPathUuid.trimStart('/')}"
                        } else {
                            ""
                        }
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, "https://$host:$effectiveWebPort$path".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            scope.launch { snackbarHostState.showSnackbar("Не удалось открыть панель управления") }
                        }
                    }
                }
            }
        },
    )

    if (showSecretsDialog) {
        DeploySecretsDialog(
            settingsStore = settingsStore,
            initialMainPass = savedMainPass,
            initialSshLogin = sshLogin,
            initialSshPass = sshPassword,
            initialWebLogin = savedWebLogin,
            initialWebPass = savedWebPassword,
            onSaved = {},
            onDismiss = { showSecretsDialog = false },
        )
    }

    val profilePeer = resolveServerPorts(savedManualPorts, sshPort, peerPort, webPort).peer
    checkPlan?.let { plan ->
        ServerCheckDialog(
            title = "На сервере",
            servers = plan.servers.map { describeServer(it, profilePeer) },
            emptyText = "csqtt не найден — сервер чистый",
            notes = plan.notes,
            choices = plan.actions.mapIndexed { index, action ->
                CheckChoice(
                    action.title,
                    action.detail,
                    when {
                        action.destructive -> CheckChoiceStyle.DESTRUCTIVE
                        index == 0 -> CheckChoiceStyle.RECOMMENDED
                        else -> CheckChoiceStyle.OTHER
                    },
                )
            },
            onChoose = { index ->
                val action = plan.actions[index]
                checkPlan = null
                if (action.destructive) pendingReplace = action else startDeploy(action)
            },
            onDismiss = { checkPlan = null },
        )
    }
    pendingReplace?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingReplace = null },
            title = { Text("Заменить сервер?") },
            text = { Text(action.detail) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingReplace = null
                        startDeploy(action)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Заменить") }
            },
            dismissButton = { TextButton(onClick = { pendingReplace = null }) { Text("Отмена") } },
        )
    }
    removable?.let { servers ->
        ServerCheckDialog(
            title = "Удаление с сервера",
            servers = emptyList(),
            emptyText = if (servers.isEmpty() && removeNotes.isEmpty()) "Серверов, установленных этим приложением, здесь нет" else "",
            notes = removeNotes,
            choices = servers.map { server ->
                val (name, detail) = describeServer(server, profilePeer)
                CheckChoice("Удалить: $name", detail, CheckChoiceStyle.DESTRUCTIVE)
            },
            onChoose = { index ->
                pendingRemove = servers[index]
                removable = null
            },
            onDismiss = { removable = null },
        )
    }
    pendingRemove?.let { server ->
        val (name, _) = describeServer(server, profilePeer)
        UninstallConfirmDialog(
            title = "Удаление: $name",
            text = if (server.place == ServerPlace.SIDE) {
                "Будет удалён второй сервер csqtt25: его бинарник, сервис или контейнер и только его правила firewall/NAT. " +
                    "Остальное на этом VPS не затрагивается. База пользователей остаётся в /etc/csqtt25.\n\nЭто действие необратимо."
            } else {
                "Будут удалены: бинарник, systemd-сервис или контейнер, конфигурация FOCSQ и только помеченные правила " +
                    "firewall/NAT для FOCSQ. Второй сервер (csqtt25), если он есть, не затрагивается. База пользователей " +
                    "остаётся в /etc/csqtt.\n\nЭто действие необратимо."
            },
            onDismiss = { pendingRemove = null },
            onConfirm = {
                pendingRemove = null
                startUninstall(server)
            },
        )
    }

    if (showPanelInfoDialog) {
        WebPanelInfoDialog(onDismiss = { showPanelInfoDialog = false })
    }

    if (showSshKeysDialog) {
        SshKeysDialog(
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
        DockerInstallInfoDialog(onDismiss = { showDockerInfoDialog = false })
    }
}
