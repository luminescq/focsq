package com.csqtt.client.ui.legacy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.CsqttConstants
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes

@Composable
internal fun TurnTransportInfoDialog(mode: String? = null, onDismiss: () -> Unit) {
    val (title, text) = when (mode) {
        CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT -> "Транспорт UDP" to """
            UDP — обычный и рекомендуемый путь от устройства до VK TURN. Он даёт наименьшую задержку, не создаёт очередь TCP и лучше подходит для игр, звонков и коротких пакетов.

            Выбирайте его по умолчанию. Между VK TURN и вашим VPS также всегда используется UDP.
        """.trimIndent()
        CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS -> "Транспорт TCP" to """
            Этот режим нужен только если оператор режет или блокирует UDP, например в отдельных сетях Ростелекома.

            TCP действует только на участке между вашим устройством и выданным VK TURN-узлом. Между VK TURN и вашим VPS трафик всегда передаётся по UDP.

            Для обычного TURN-адреса это не TLS; если VK когда-либо выдаст turns-адрес, он будет использовать TLS. На UDP этот режим автоматически не переключается. TCP может увеличить ping и просадки при нагрузке из-за очереди TCP.
        """.trimIndent()
        else -> "Транспорт TURN" to """
            По умолчанию CSQTT использует UDP: это лучший выбор по скорости, задержке и отзывчивости.

            TCP — отдельный режим для сетей, где UDP ограничен. Он влияет только на путь устройства до VK TURN; участок между VK TURN и VPS всегда использует UDP. В TCP-режиме нет скрытого перехода на UDP на участке устройства до TURN.

            Изменение применяется при следующем подключении.
        """.trimIndent()
    }

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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
                    shape = CsqttShapes.Pill,
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                ) {
                    Text("Понятно", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
