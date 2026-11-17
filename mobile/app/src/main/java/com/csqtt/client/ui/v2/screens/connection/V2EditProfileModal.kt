package com.csqtt.client.ui.v2.screens.connection

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.CsqttConstants
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.WorkerCountPolicy
import com.csqtt.client.defaultProfileName
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.v2.components.V2PowerSlider
import com.csqtt.client.ui.legacy.utils.TUNNEL_PASSWORD_RULE
import com.csqtt.client.ui.legacy.utils.formatCsqttLink
import com.csqtt.client.ui.legacy.utils.isValidTunnelPassword
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.v2.components.SuppressAutofill
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import com.csqtt.client.ui.v2.dialogs.V2WorkersInfoDialog
import kotlinx.coroutines.launch

/**
 * Profile editor dialog, styled to the unified V2 design system.
 */
@Composable
fun V2EditProfileModal(
    settingsStore: SettingsStore,
    profile: Int,
    profileName: String,
    onDismiss: () -> Unit,
    isNew: Boolean = false,
    onSaved: (Int) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val colors = V2Theme.colors

    var loaded by rememberSaveable(profile) { mutableStateOf(false) }
    var nameInput by rememberSaveable(profile) {
        mutableStateOf(
            if (isNew) "Узел #${(1000..9999).random()}" else profileName,
        )
    }
    var peerInput by rememberSaveable(profile) { mutableStateOf("") }
    var portInput by rememberSaveable(profile) { mutableStateOf("") }
    var passwordInput by rememberSaveable(profile) { mutableStateOf("") }
    var passwordUnreadable by rememberSaveable(profile) { mutableStateOf(false) }
    var passwordVisible by rememberSaveable(profile) { mutableStateOf(false) }
    var workers by rememberSaveable(profile) { mutableFloatStateOf(10f) }
    var profileHashMode by rememberSaveable(profile) {
        mutableStateOf(CsqttConstants.VkAutoHash.MODE_MANUAL)
    }
    var profileVkAuthMode by rememberSaveable(profile) {
        mutableStateOf(CsqttConstants.VkAuth.MODE_CALLS)
    }
    var profileHashes by rememberSaveable(profile) { mutableStateOf("") }
    var linkInput by rememberSaveable(profile) { mutableStateOf("") }
    var participantMode by rememberSaveable(profile) { mutableStateOf(false) }
    var subscriptionTitle by rememberSaveable(profile) { mutableStateOf("") }
    var subscriptionPower by rememberSaveable(profile) { mutableIntStateOf(0) }

    var peerError by remember { mutableStateOf(false) }
    var portError by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showWorkersInfoDialog by remember { mutableStateOf(false) }

    val activeProfile by settingsStore.activeProfile.collectAsStateWithLifecycle(initialValue = 0)
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val serverWorkers by TunnelManager.serverAssignedWorkers.collectAsStateWithLifecycle()
    val workersLocked = tunnelRunning && profile == activeProfile
    val fixedWorkers = serverWorkers.takeIf { workersLocked && it in 3..100 }

    fun deleteProfile() {
        scope.launch {
            settingsStore.clearProfile(profile)
            onDismiss()
            context.showRaisedToast("Профиль удалён", Toast.LENGTH_SHORT)
        }
    }

    LaunchedEffect(profile) {
        if (loaded) return@LaunchedEffect
        val snapshot = settingsStore.readProfileConnectionSettings(profile)
        profileHashMode = snapshot.vkHashMode
        profileVkAuthMode = snapshot.vkAuthMode
        profileHashes = snapshot.vkHashes
        subscriptionTitle = snapshot.subscriptionTitle
        subscriptionPower = snapshot.subscriptionPower
        peerInput = snapshot.peer
        portInput = snapshot.serverPeerPort.toString()
        passwordInput = snapshot.connectionPassword
        passwordUnreadable = snapshot.connectionPasswordState ==
            com.csqtt.client.StoredSecretState.Unreadable
        linkInput = snapshot.csqttLink
        participantMode = snapshot.csqttLinkMode
        val loadedLink = if (snapshot.csqttLinkMode) parseCsqttLink(snapshot.csqttLink) else null
        if (loadedLink != null) {
            peerInput = loadedLink.host
            portInput = loadedLink.port.toString()
            passwordInput = loadedLink.password
        }
        val effectiveMode = effectiveHashMode(
            participantMode = snapshot.csqttLinkMode,
            participantHasToken = !loadedLink?.vkAccessToken.isNullOrEmpty(),
            participantHashCount = loadedLink?.hashes.orEmpty().size,
            profileHashMode = snapshot.vkHashMode,
            profileVkAuthMode = snapshot.vkAuthMode,
        )
        val effectiveCount = if (snapshot.csqttLinkMode && !loadedLink?.hashes.isNullOrEmpty()) {
            loadedLink.hashes.size
        } else {
            countVkHashes(snapshot.vkHashes)
        }
        val hashCap = WorkerCountPolicy.maximumForProfile(effectiveMode, effectiveCount)
        val cap = snapshot.subscriptionPower
            .takeIf { it in 10..100 }
            ?.let { minOf(it, hashCap) }
            ?: hashCap
        workers = snapshot.workersPerHash.toFloat().coerceIn(5f, cap.toFloat())
        loaded = true
    }

    val parsedParticipantLink = remember(linkInput) {
        if (participantMode) parseCsqttLink(linkInput) else null
    }

    val participantHashes = parsedParticipantLink?.hashes.orEmpty()
    val participantHasToken = !parsedParticipantLink?.vkAccessToken.isNullOrEmpty()
    val effectiveHashMode = effectiveHashMode(
        participantMode = participantMode,
        participantHasToken = participantHasToken,
        participantHashCount = participantHashes.size,
        profileHashMode = profileHashMode,
        profileVkAuthMode = profileVkAuthMode,
    )
    val effectiveHashCount = if (participantMode && participantHashes.isNotEmpty()) {
        participantHashes.size
    } else {
        countVkHashes(profileHashes)
    }
    val hashPowerCap = WorkerCountPolicy.maximumForProfile(effectiveHashMode, effectiveHashCount)
    val workersCap = subscriptionPower
        .takeIf { it in 10..100 }
        ?.let { minOf(it, hashPowerCap) }
        ?: hashPowerCap

    LaunchedEffect(workersCap) {
        if (!workersLocked && workers > workersCap) {
            workers = workersCap.toFloat()
        }
    }

    fun save() {
        val name = nameInput.trim()
        val toastName = name.ifBlank { defaultProfileName(profile) }
        if (participantMode) {
            val host = peerInput.trim()
            val port = portInput.trim().toIntOrNull()
            val password = passwordInput

            peerError = host.isBlank()
            portError = port == null || port !in 1..65535
            passwordError = !isValidTunnelPassword(password)

            if (peerError || portError || passwordError) return

            val rebuiltLink = formatCsqttLink(
                host = host,
                peer = port!!,
                password = password,
                hashes = parsedParticipantLink?.hashes.orEmpty(),
                token = parsedParticipantLink?.vkAccessToken.orEmpty(),
                power = parsedParticipantLink?.power,
            )
            val workerCount = workers.toInt().coerceAtMost(workersCap)
            scope.launch {
                settingsStore.saveProfileName(profile, name)
                settingsStore.saveProfileConnectionSettings(
                    profile = profile,
                    peer = host,
                    serverPeerPort = port,
                    connectionPassword = password,
                    workersPerHash = workerCount,
                )
                settingsStore.saveProfileParticipantLink(profile, rebuiltLink)
                settingsStore.saveProfileWorkersPerHash(profile, workerCount)
                settingsStore.saveProfileParticipantMode(profile, true)
                onSaved(profile)
                onDismiss()
                context.showRaisedToast(
                    if (isNew) "Профиль «$toastName» добавлен" else "Профиль «$toastName» сохранён",
                    Toast.LENGTH_SHORT,
                )
            }
        } else {
            val peer = peerInput.trim()
            val port = portInput.trim().toIntOrNull()
            val password = passwordInput

            peerError = peer.isBlank()
            portError = port == null || port !in 1..65535
            passwordError = !isValidTunnelPassword(password)

            if (peerError || portError || passwordError) return

            scope.launch {
                settingsStore.saveProfileName(profile, name)
                settingsStore.saveProfileConnectionSettings(
                    profile = profile,
                    peer = peer,
                    serverPeerPort = port!!,
                    connectionPassword = password,
                    workersPerHash = workers.toInt().coerceAtMost(workersCap),
                )
                settingsStore.saveProfileParticipantMode(profile, false)
                onSaved(profile)
                onDismiss()
                context.showRaisedToast(
                    if (isNew) "Профиль «$toastName» добавлен" else "Профиль «$toastName» сохранён",
                    Toast.LENGTH_SHORT,
                )
            }
        }
    }

    V2DialogContainer(
        title = if (isNew) "Новый профиль" else "Редактирование",
        subtitle = when {
            isNew -> "Заполните параметры подключения"
            participantMode -> "Профиль участника"
            subscriptionTitle.isNotBlank() -> "Узел подписки"
            else -> "Профиль владельца"
        },
        onDismiss = onDismiss,
        actions = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(V2Spacing.Xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Отмена",
                        onClick = onDismiss,
                        style = V2PillStyle.Outline,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                    V2PillButton(
                        text = "Сохранить",
                        onClick = { save() },
                        style = V2PillStyle.Contrast,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                }
                if (!isNew) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(V2Shapes.Pill)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { showDeleteConfirm = true },
                            )
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Удалить профиль",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = V2DisplayFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            ),
                            color = colors.error,
                        )
                    }
                }
            }
        },
    ) {
        SuppressAutofill()
        if (!loaded) {
            Text(
                text = "Загрузка…",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = V2Spacing.Xxl),
            )
            return@V2DialogContainer
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
        ) {
            V2TextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                placeholder = "Имя профиля",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Md),
            ) {
                V2TextField(
                    value = peerInput,
                    onValueChange = {
                        peerInput = it
                        if (peerError) peerError = false
                    },
                    placeholder = "IP или домен",
                    isError = peerError,
                    modifier = Modifier.weight(1f),
                )
                V2TextField(
                    value = portInput,
                    onValueChange = {
                        portInput = it.filter(Char::isDigit)
                        if (portError) portError = false
                    },
                    placeholder = "Порт",
                    isError = portError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.55f),
                )
            }
            V2TextField(
                value = passwordInput,
                onValueChange = {
                    passwordInput = it.filterNot(Char::isWhitespace)
                    if (passwordError) passwordError = false
                },
                placeholder = if (passwordUnreadable) {
                    "Пароль нечитаем — введите заново"
                } else {
                    "Пароль подключения"
                },
                isError = passwordError,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (passwordError) {
                Text(
                    text = TUNNEL_PASSWORD_RULE,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
            if (participantMode) {
                val hashes = parsedParticipantLink?.hashes.orEmpty()
                val hasToken = !parsedParticipantLink?.vkAccessToken.isNullOrEmpty()
                Text(
                    text = when {
                        hashes.isNotEmpty() -> "VK: ${hashes.size} хеш(ей) из ссылки сохранятся"
                        hasToken -> "VK-токен из ссылки сохранится"
                        else -> "VK-данные будут взяты из настроек профиля"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { passwordVisible = !passwordVisible },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                Icon(
                    imageVector = if (passwordVisible) {
                        Icons.Outlined.VisibilityOff
                    } else {
                        Icons.Outlined.Visibility
                    },
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (passwordVisible) "Скрыть пароль" else "Показать пароль",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textSecondary,
                )
            }

            V2PowerSlider(
                value = workers,
                maximum = workersCap.toFloat(),
                enabled = !workersLocked,
                onValueChange = { workers = it },
                onInfo = { showWorkersInfoDialog = true },
                fixedWorkers = fixedWorkers,
            )
            Text(
                text = when {
                    effectiveHashMode != CsqttConstants.VkAutoHash.MODE_MANUAL ->
                        "Авто-режим: до $workersCap потоков без привязки к хешам"
                    subscriptionPower in 10..100 && subscriptionPower < hashPowerCap ->
                        "Лимит подписки: до $workersCap потоков"
                    effectiveHashCount == 0 ->
                        "Добавьте VK-хеши в настройках: 1 хеш = ${CsqttConstants.Tunnel.WORKERS_PER_VK_HASH} потоков"
                    else ->
                        "$effectiveHashCount хеш(ей) × ${CsqttConstants.Tunnel.WORKERS_PER_VK_HASH} = до $workersCap потоков"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }

    if (showWorkersInfoDialog) {
        V2WorkersInfoDialog(onDismiss = { showWorkersInfoDialog = false })
    }

    if (showDeleteConfirm) {
        V2DialogContainer(
            title = "Удалить профиль?",
            subtitle = "Конфигурация профиля «${nameInput.ifBlank { defaultProfileName(profile) }}» будет удалена.",
            icon = Icons.Filled.Delete,
            onDismiss = { showDeleteConfirm = false },
            actions = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Отмена",
                        onClick = { showDeleteConfirm = false },
                        style = V2PillStyle.Outline,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                    V2PillButton(
                        text = "Удалить",
                        onClick = {
                            showDeleteConfirm = false
                            deleteProfile()
                        },
                        style = V2PillStyle.Danger,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                }
            },
        ) {
            Text(
                text = "Вы уверены, что хотите удалить этот профиль? Действие необратимо.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
    }
}

private fun countVkHashes(raw: String): Int = raw
    .split(Regex("[,\\s\\n]+"))
    .map(String::trim)
    .filter { it.length >= 16 }
    .distinct()
    .size

private fun effectiveHashMode(
    participantMode: Boolean,
    participantHasToken: Boolean,
    participantHashCount: Int,
    profileHashMode: String,
    profileVkAuthMode: String,
): String = when {
    participantMode && participantHasToken -> CsqttConstants.VkAutoHash.MODE_AUTO_API
    participantMode && participantHashCount > 0 -> CsqttConstants.VkAutoHash.MODE_MANUAL
    profileVkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS ->
        CsqttConstants.VkAutoHash.MODE_AUTO_JS
    else -> profileHashMode
}
