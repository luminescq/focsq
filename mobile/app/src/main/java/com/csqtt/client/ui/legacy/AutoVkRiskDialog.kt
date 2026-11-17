package com.csqtt.client.ui.legacy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes
import kotlinx.coroutines.delay

@Composable
internal fun AutoVkRiskDialog(
    requireAcknowledgement: Boolean,
    onAcknowledge: () -> Unit,
    onDoNotRemind: (() -> Unit)? = null,
) {
    TimedWarningDialog(
        title = "Внимание!",
        paragraphs = listOf(
            "«Авто ВК» использует неофициальные браузерные методы VK API для создания звонка и получения TURN credentials без анонимных звонков. Сетевой стек PRIMP маскирует HTTP/TLS-профиль под обычный браузер.",
            "Разработчик не может исключить блокировку VK-аккаунта и честно оценить её вероятность. Используйте режим на свой риск; если важен основной аккаунт, выбирайте второстепенный. Рекомендуется включать «Авто ВК», когда «Авто» и «Авто API» не работают.",
        ),
        requireAcknowledgement = requireAcknowledgement,
        onAcknowledge = onAcknowledge,
        onDoNotRemind = onDoNotRemind,
    )
}

@Composable
internal fun TcpTransportRiskDialog(
    onAcknowledge: () -> Unit,
    onDoNotRemind: () -> Unit,
) {
    TimedWarningDialog(
        title = "Внимание!",
        paragraphs = listOf(
            "TCP — экспериментальный режим для сетей, в которых провайдер ограничивает UDP-трафик. TCP используется только между вашим устройством и серверами VK TURN; между VK TURN и вашим VPS трафик всегда идёт по UDP.",
            "Если UDP работает без ограничений, используйте его: UDP стабильнее, быстрее и по своей природе ближе к настоящим звонкам.",
        ),
        requireAcknowledgement = true,
        onAcknowledge = onAcknowledge,
        onDoNotRemind = onDoNotRemind,
    )
}

@Composable
private fun TimedWarningDialog(
    title: String,
    paragraphs: List<String>,
    requireAcknowledgement: Boolean,
    onAcknowledge: () -> Unit,
    onDoNotRemind: (() -> Unit)?,
) {
    var secondsLeft by rememberSaveable(requireAcknowledgement) {
        mutableIntStateOf(if (requireAcknowledgement) 8 else 0)
    }
    if (requireAcknowledgement) {
        LaunchedEffect(Unit) {
            while (secondsLeft > 0) {
                delay(1_000)
                secondsLeft -= 1
            }
        }
    }
    val actionsEnabled = secondsLeft == 0

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
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
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                paragraphs.forEach { paragraph ->
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodySmall.copy(hyphens = Hyphens.Auto),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onDoNotRemind != null) {
                        OutlinedButton(
                            onClick = onDoNotRemind,
                            enabled = actionsEnabled,
                            modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
                            shape = CsqttShapes.Pill,
                        ) {
                            Text("Не напоминать", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Button(
                        onClick = onAcknowledge,
                        enabled = actionsEnabled,
                        modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
                        shape = CsqttShapes.Pill,
                    ) {
                        Text("Понятно", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (!actionsEnabled) {
                        Text(
                            text = secondsLeft.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
