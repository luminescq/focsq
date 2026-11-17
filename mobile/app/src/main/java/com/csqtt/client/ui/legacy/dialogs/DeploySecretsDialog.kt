package com.csqtt.client.ui.legacy.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.SettingsStore
import com.csqtt.client.ui.legacy.appSectionCardBorderColor
import com.csqtt.client.ui.legacy.appSectionCardColor
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes
import com.csqtt.client.ui.legacy.utils.TUNNEL_PASSWORD_RULE
import com.csqtt.client.ui.legacy.utils.generate16CharPassword
import com.csqtt.client.ui.legacy.utils.isValidTunnelPassword
import kotlinx.coroutines.launch

@Composable
fun DeploySecretsDialog(
    settingsStore: SettingsStore,
    initialMainPass: String,
    initialSshLogin: String,
    initialSshPass: String,
    initialWebLogin: String,
    initialWebPass: String,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var mainPassInput by rememberSaveable {
        mutableStateOf(initialMainPass.ifBlank { generate16CharPassword() })
    }
    var webLoginInput by rememberSaveable { mutableStateOf(initialWebLogin) }
    var webPassInput by rememberSaveable { mutableStateOf(initialWebPass) }
    var passwordsVisible by rememberSaveable { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = CsqttShapes.Dialog,
            color = appSectionCardColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, appSectionCardBorderColor()),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = "Авторизация",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Text(
                    text = "Придумайте логин и пароль для web панели (латиница и цифры) и пароль туннеля.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )

                val allowedChars = Regex("^[a-zA-Z0-9]+$")
                val invalidHint: @Composable () -> Unit = {
                    Text("Разрешена «Латиница» и «Цифры»", color = MaterialTheme.colorScheme.error)
                }
                val isWebLoginValid = webLoginInput.isNotEmpty() && webLoginInput.matches(allowedChars)
                val isWebPassValid = webPassInput.isNotEmpty() && webPassInput.matches(allowedChars)
                val isMainPassValid = isValidTunnelPassword(mainPassInput)

                Text(
                    text = "Пароль подключения к туннелю",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                OutlinedTextField(
                    value = mainPassInput,
                    onValueChange = {
                        mainPassInput = it.filter { c -> !c.isWhitespace() }
                    },
                    label = { Text("Пароль туннеля", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    placeholder = { Text("укажите или сгенерируйте", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CsqttShapes.Control,
                    isError = !isMainPassValid,
                    supportingText = if (mainPassInput.isNotEmpty() && !isMainPassValid) {
                        { Text(TUNNEL_PASSWORD_RULE, color = MaterialTheme.colorScheme.error) }
                    } else null,
                    trailingIcon = {
                        IconButton(onClick = { mainPassInput = generate16CharPassword() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Сгенерировать пароль (16 символов)",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )

                Text(
                    text = "Данные доступа к web панели",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                OutlinedTextField(
                    value = webLoginInput,
                    onValueChange = {
                        webLoginInput = it.filter { c -> !c.isWhitespace() }
                    },
                    label = { Text("Логин WEB (придумайте)", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    placeholder = { Text("придумайте логин", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CsqttShapes.Control,
                    isError = !isWebLoginValid,
                    supportingText = if (webLoginInput.isNotEmpty() && !isWebLoginValid) invalidHint else null,
                )

                OutlinedTextField(
                    value = webPassInput,
                    onValueChange = {
                        webPassInput = it.filter { c -> !c.isWhitespace() }
                    },
                    label = { Text("Пароль WEB (придумайте)", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    placeholder = { Text("придумайте пароль", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CsqttShapes.Control,
                    isError = !isWebPassValid,
                    supportingText = if (webPassInput.isNotEmpty() && !isWebPassValid) invalidHint else null,
                    visualTransformation = if (passwordsVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )

                Button(
                    onClick = {
                        scope.launch {
                            settingsStore.saveDeploySecrets(mainPassInput, initialSshLogin, initialSshPass, webLoginInput, webPassInput)
                            settingsStore.saveConnectionPassword(mainPassInput)
                            onSaved()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
                    shape = CsqttShapes.Pill,
                    enabled = isWebLoginValid && isWebPassValid && isMainPassValid,
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                ) {
                    Text("Сохранить", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
