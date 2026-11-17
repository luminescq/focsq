@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csqtt.client.ui.v2.screens.deploy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.CsqttConstants
import com.csqtt.client.ui.legacy.sanitizeSshPassword
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2SwitchSetting
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.components.V2TransparentIconButton
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch

private fun isValidPort(value: String): Boolean = value.toIntOrNull() in 1..65535

@Composable
fun V2ServerConfigSheet(
    serverName: String,
    onServerNameChange: (String) -> Unit,
    host: String,
    onHostChange: (String) -> Unit,
    sshLogin: String,
    onSshLoginChange: (String) -> Unit,
    sshPassword: String,
    onSshPasswordChange: (String) -> Unit,
    sshPort: String,
    onSshPortChange: (String) -> Unit,
    sshKeysMode: Boolean,
    onSshKeysModeChange: (Boolean) -> Unit,
    sshKeysFilled: Int,
    isDeploying: Boolean,
    onOpenSshKeys: () -> Unit,
    onOpenSecrets: () -> Unit,
    onSave: () -> Unit,
    onDeleteServer: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    connectionOnly: Boolean = false,
) {
    val colors = V2Theme.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val hostInvalid = host.isBlank()
    val loginInvalid = sshLogin.isBlank()
    val passwordInvalid = !sshKeysMode && sshPassword.isBlank()
    val keysInvalid = sshKeysMode && sshKeysFilled < 1
    val portInvalid = sshPort.isNotBlank() && !isValidPort(sshPort)
    val canSave = !isDeploying && !hostInvalid && !loginInvalid && !passwordInvalid &&
        !keysInvalid && !portInvalid

    fun animateDismiss(onDone: () -> Unit = onDismiss) {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            onDone()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = colors.bottomSurface,
        contentColor = colors.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(V2Shapes.Pill)
                    .background(colors.textMuted.copy(alpha = 0.4f)),
            )
        },
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Dns,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = when {
                            host.isBlank() -> "Добавить сервер"
                            connectionOnly -> "Подключение"
                            else -> "Параметры сервера"
                        },
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = V2DisplayFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                        ),
                        color = colors.textPrimary,
                    )
                }

                V2CircleIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Server name
            V2TextField(
                value = serverName,
                onValueChange = onServerNameChange,
                placeholder = "Название сервера",
                enabled = !isDeploying,
                modifier = Modifier.fillMaxWidth(),
            )

            // IP & Login
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                V2TextField(
                    value = host,
                    onValueChange = { onHostChange(it.filterNot(Char::isWhitespace)) },
                    placeholder = "IP-адрес или домен",
                    enabled = !isDeploying,
                    isError = showErrors && hostInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                V2TextField(
                    value = sshLogin,
                    onValueChange = { onSshLoginChange(it.filterNot(Char::isWhitespace)) },
                    placeholder = "Логин (root)",
                    enabled = !isDeploying,
                    isError = showErrors && loginInvalid,
                    modifier = Modifier.weight(1f),
                )
            }

            // Password or Keys + Secrets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                if (sshKeysMode) {
                    V2PillButton(
                        text = if (sshKeysFilled > 0) "SSH ключ задан" else "Указать SSH ключ",
                        onClick = onOpenSshKeys,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Key,
                        style = if (sshKeysFilled < 1) V2PillStyle.Contrast else V2PillStyle.Outline,
                        height = 54.dp,
                        horizontalPadding = 12.dp,
                    )
                } else {
                    V2TextField(
                        value = sshPassword,
                        onValueChange = { onSshPasswordChange(sanitizeSshPassword(it)) },
                        placeholder = "Пароль SSH",
                        enabled = !isDeploying,
                        isError = showErrors && passwordInvalid,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier.weight(1f),
                        trailing = {
                            V2TransparentIconButton(
                                icon = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль",
                                onClick = { passwordVisible = !passwordVisible },
                            )
                        },
                    )
                }
                if (!connectionOnly) {
                    V2PillButton(
                        text = "Авторизация",
                        onClick = onOpenSecrets,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Lock,
                        style = V2PillStyle.Outline,
                        height = 54.dp,
                        horizontalPadding = 12.dp,
                    )
                }
            }

            if (showErrors && keysInvalid) {
                Text(
                    text = "Укажите SSH-ключ для подключения",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }

            // Switches
            V2SwitchSetting(
                title = "SSH ключи вместо пароля",
                checked = sshKeysMode,
                enabled = !isDeploying,
                onCheckedChange = onSshKeysModeChange,
            )

            V2TextField(
                value = sshPort,
                onValueChange = { onSshPortChange(it.filter(Char::isDigit).take(5)) },
                placeholder = "SSH порт ${CsqttConstants.Network.DEFAULT_SSH_PORT}",
                enabled = !isDeploying,
                isError = showErrors && portInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Action Buttons
            if (onDeleteServer != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Удалить",
                        onClick = {
                            animateDismiss { onDeleteServer() }
                        },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Delete,
                        style = V2PillStyle.Danger,
                        enabled = !isDeploying,
                        height = 52.dp,
                        horizontalPadding = 8.dp,
                    )
                    V2PillButton(
                        text = "Сохранить",
                        onClick = {
                            showErrors = true
                            if (canSave) {
                                animateDismiss { onSave() }
                            }
                        },
                        modifier = Modifier.weight(2f),
                        icon = Icons.Filled.Check,
                        style = V2PillStyle.Contrast,
                        enabled = !isDeploying,
                        height = 52.dp,
                        horizontalPadding = 8.dp,
                    )
                }
            } else {
                V2PillButton(
                    text = "Сохранить",
                    onClick = {
                        showErrors = true
                        if (canSave) {
                            animateDismiss { onSave() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Filled.Check,
                    style = V2PillStyle.Contrast,
                    enabled = !isDeploying,
                    height = 52.dp,
                    horizontalPadding = 8.dp,
                )
            }
        }
    }
}
