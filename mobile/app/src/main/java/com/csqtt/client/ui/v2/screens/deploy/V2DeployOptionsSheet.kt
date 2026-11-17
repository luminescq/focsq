@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.csqtt.client.ui.v2.screens.deploy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.CsqttConstants
import com.csqtt.client.ui.legacy.DeployActionMode
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2SwitchSetting
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch

private fun isValidDeployPort(value: String): Boolean = value.toIntOrNull() in 1..65535

/**
 * Deploy-time options: manual ports, Docker mode and authorization. Connection data
 * (host, SSH login/password, SSH port) is collected separately when the server is added.
 */
@Composable
internal fun V2DeployOptionsSheet(
    mode: DeployActionMode,
    host: String,
    sshPort: String,
    peerPort: String,
    onPeerPortChange: (String) -> Unit,
    webPort: String,
    onWebPortChange: (String) -> Unit,
    manualPorts: Boolean,
    onManualPortsChange: (Boolean) -> Unit,
    dockerInstall: Boolean,
    onDockerInstallChange: (Boolean) -> Unit,
    mainPasswordConfigured: Boolean,
    webPanelConfigured: Boolean,
    onOpenSecrets: () -> Unit,
    onOpenDockerInfo: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = V2Theme.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val portsValid = !manualPorts ||
        (isValidDeployPort(peerPort) && isValidDeployPort(webPort))
    val authReady = mainPasswordConfigured && webPanelConfigured
    val canConfirm = portsValid && authReady

    fun animateDismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
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
                        imageVector = Icons.Filled.Memory,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                    Column {
                        Text(
                            text = if (mode == DeployActionMode.Reinstall) {
                                "Параметры переустановки"
                            } else {
                                "Параметры установки"
                            },
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = V2DisplayFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 19.sp,
                            ),
                            color = colors.textPrimary,
                        )
                        Text(
                            text = buildString {
                                append(host.ifBlank { "Сервер" })
                                if (sshPort.isNotBlank()) append(" · SSH $sshPort")
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = V2BodyFamily,
                                fontSize = 12.sp,
                            ),
                            color = colors.textMuted,
                        )
                    }
                }

                V2CircleIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }

            V2SwitchSetting(
                title = "Ручные порты (Peer, Web)",
                checked = manualPorts,
                onCheckedChange = onManualPortsChange,
            )

            AnimatedVisibility(
                visible = manualPorts,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2TextField(
                        value = peerPort,
                        onValueChange = { onPeerPortChange(it.filter(Char::isDigit).take(5)) },
                        placeholder = "Peer ${CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT}",
                        isError = !isValidDeployPort(peerPort),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    V2TextField(
                        value = webPort,
                        onValueChange = { onWebPortChange(it.filter(Char::isDigit).take(5)) },
                        placeholder = "Web ${CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT}",
                        isError = !isValidDeployPort(webPort),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            V2SwitchSetting(
                title = "Установить в Docker",
                checked = dockerInstall,
                onCheckedChange = onDockerInstallChange,
                onInfoClick = onOpenDockerInfo,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(V2Shapes.Row)
                    .background(colors.field)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = if (authReady) colors.accent else colors.warning,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (authReady) "Авторизация настроена" else "Нужна авторизация",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = V2BodyFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        ),
                        color = colors.textPrimary,
                    )
                    V2PillButton(
                        text = if (authReady) "Изменить" else "Настроить",
                        onClick = onOpenSecrets,
                        style = if (authReady) V2PillStyle.Outline else V2PillStyle.Contrast,
                        height = 38.dp,
                        horizontalPadding = 14.dp,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    text = buildString {
                        append("Пароль туннеля: ")
                        append(if (mainPasswordConfigured) "задан" else "не задан")
                        append(" · Web-панель: ")
                        append(if (webPanelConfigured) "задана" else "не задана")
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 12.sp,
                    ),
                    color = if (authReady) colors.textMuted else colors.error,
                )
            }

            if (mode == DeployActionMode.Reinstall) {
                Text(
                    text = "Переустановка удалит базу, конфигурацию и сертификаты на сервере.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    ),
                    color = colors.error,
                )
            }

            V2PillButton(
                text = if (mode == DeployActionMode.Reinstall) "Переустановить" else "Установить",
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.Check,
                style = V2PillStyle.Contrast,
                enabled = canConfirm,
                height = 52.dp,
                horizontalPadding = 8.dp,
            )
        }
    }
}
