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
internal fun WorkModeInfoDialog(mode: String? = null, onDismiss: () -> Unit) {
    val (title, text) = when (mode) {
        CsqttConstants.VkAuth.MODE_CAPTCHA -> "Режим «Капча»" to """
            • Старый метод получения credentials через эмуляцию браузерного клиента.
            • При таком способе VK может потребовать прохождение капчи.
            • Если решить её автоматически не получится, приложение запросит ручное подтверждение в WebView.
        """.trimIndent()
        CsqttConstants.VkAuth.MODE_CALLS -> "Режим «Авто»" to """
            • Получение TURN-сессий напрямую через протокол и гостевую цепочку VK Calls.
            • Обычно позволяет работать без капчи.
            • Поддерживает масштабирование до 100 независимых воркеров.
        """.trimIndent()
        CsqttConstants.VkAuth.MODE_AUTO_JS -> "Режим «Авто ВК»" to """
            • Один аккаунт создаёт и удерживает один звонок через метод vchat.startConversation.
            • Calls-сессии обслуживают группы потоков с общим максимумом 100 воркеров.
            • Получает credentials через официальный метод get_conversation_params под авторизованным аккаунтом VK.
            • Анонимные звонки не используются. Режим хешей автоматически фиксируется на «Авто ВК».
            • Создатель удерживает звонок до отключения туннеля.
            • Если отдельная сессия не выдаст креды, её группа перейдёт на «Авто» без капчи.
        """.trimIndent()
        else -> "Режимы работы" to """
            • Капча — считается первым и самым старым методом получения credentials, не советуется к штатному использованию.

            • Авто — уже очень хороший гостевой режим через VK Calls, почти всегда не требует капчи и работает без проблем (до 100 потоков).

            • Авто ВК — новый режим получения credentials через get_conversation_params. Использует залогиненный аккаунт ВК, анонимные звонки не используются. Советуем использовать этот режим, когда с режимом «Авто» наблюдаются проблемы. Режим самый стабильный, работает только в паре с Авто ВК методом получения ВК хешей.
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
