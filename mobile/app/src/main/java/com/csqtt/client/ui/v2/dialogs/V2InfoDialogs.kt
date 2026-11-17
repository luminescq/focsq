package com.csqtt.client.ui.v2.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
import com.csqtt.client.CsqttConstants
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.delay

@Composable
fun V2HashModeInfoDialog(mode: String? = null, onDismiss: () -> Unit) {
    val colors = V2Theme.colors
    val (title, text) = when (mode) {
        CsqttConstants.VkAutoHash.MODE_MANUAL -> "Режим «Ручной»" to """
            • Вы сами создаёте звонки или группы с вечными звонками в VK, копируете ссылку и вставляете её в окно «VK Хеши».
            • Можно использовать до 6 разных хешей для распределения нагрузки.
            • Полный ручной контроль над источниками пула звонков без необходимости входа в VK ID.
        """.trimIndent()
        CsqttConstants.VkAutoHash.MODE_AUTO_API -> "Режим «Авто API»" to """
            • Приложение создаёт необходимые звонки через официальный метод VK API calls.start.
            • Количество хешей выбирается автоматически — от 1 до 6 по числу потоков.
            • При остановке туннеля звонки корректно завершаются через calls.forceFinish.
            • VK access token хранится в защищённом Android Keystore.
        """.trimIndent()
        CsqttConstants.VkAutoHash.MODE_AUTO_JS -> "Режим «Авто ВК»" to """
            • Rust повторяет браузерную цепочку VK Calls: получает call-token через messages.getCallToken, открывает сессию OK Calls и создаёт один звонок через vchat.startConversation.
            • session_key и идентификаторы звонков остаются внутри Rust-процесса и не выводятся в журнал.
            • Источник TURN-кредов задаётся отдельно режимом работы: «Капча», «Авто» или account-сессии «Авто ВК».
            • В режимах работы «Капча» и «Авто» создатель выходит после готовности TURN-наборов; в режиме «Авто ВК» удерживает звонок до отключения.
            • WSS и WebTransport не запускаются, поскольку для получения join_link и TURN-хешей они не требуются.
        """.trimIndent()
        else -> "Режимы ВК Хешей" to """
            • Ручной — несмотря на жёлтый индикатор, является стабильным и проверенно рабочим.

            • Авто API — использует вечный VK токен для создания звонков через официальный API calls.start, работает стабильно и хорошо. Создаёт звонки и использует их; после отключения звонки завершаются.

            • Авто ВК — использует браузерную цепочку API (методы calls.getConversationParams), также требует VK токен. Стабильный метод на случай возникновения проблем с первыми двумя.
        """.trimIndent()
    }

    V2DialogContainer(
        title = title,
        onDismiss = onDismiss,
        actions = {
            V2PillButton(
                text = "Понятно",
                onClick = onDismiss,
                style = V2PillStyle.Contrast,
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp,
            )
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = V2BodyFamily,
                lineHeight = 18.sp,
            ),
            color = colors.textSecondary,
        )
    }
}

@Composable
fun V2WorkModeInfoDialog(mode: String? = null, onDismiss: () -> Unit) {
    val colors = V2Theme.colors
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
        """.trimIndent()
        else -> "Режимы работы" to """
            • Капча — считается первым и самым старым методом получения credentials, не советуется к штатному использованию.

            • Авто — гостевой режим через VK Calls, почти всегда не требует капчи и работает отлично (до 100 потоков).

            • Авто ВК — режим получения credentials через get_conversation_params с авторизованным аккаунтом ВК.
        """.trimIndent()
    }

    V2DialogContainer(
        title = title,
        onDismiss = onDismiss,
        actions = {
            V2PillButton(
                text = "Понятно",
                onClick = onDismiss,
                style = V2PillStyle.Contrast,
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp,
            )
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = V2BodyFamily,
                lineHeight = 18.sp,
            ),
            color = colors.textSecondary,
        )
    }
}

@Composable
fun V2WorkersInfoDialog(onDismiss: () -> Unit) {
    val colors = V2Theme.colors
    V2DialogContainer(
        title = "Настройка потоков",
        onDismiss = onDismiss,
        actions = {
            V2PillButton(
                text = "Понятно",
                onClick = onDismiss,
                style = V2PillStyle.Contrast,
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp,
            )
        },
    ) {
        Text(
            text = """
                • Каждый поток TURN имеет пропускную способность около 2 Мбит/с.
                • Рекомендуется выбирать количество потоков исходя из реальной скорости вашего интернет-подключения:
                  — 10 потоков: для скорости 20 Мбит/с
                  — 20 потоков: для скорости 40 Мбит/с
                  — 30 потоков: для скорости 60 Мбит/с
                  — 50 потоков: для скорости 100 Мбит/с
                  — и далее по нарастающей.
            """.trimIndent(),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = V2BodyFamily,
                lineHeight = 18.sp,
            ),
            color = colors.textSecondary,
        )
    }
}

@Composable
fun V2ExtraWorkersInfoDialog(onDismiss: () -> Unit) {
    val colors = V2Theme.colors
    V2DialogContainer(
        title = "Экстра потоки",
        onDismiss = onDismiss,
        actions = {
            V2PillButton(
                text = "Понятно",
                onClick = onDismiss,
                style = V2PillStyle.Contrast,
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp,
            )
        },
    ) {
        Text(
            text = """
                • Функция повышает максимальное количество потоков с ${CsqttConstants.Tunnel.DEFAULT_MAX_WORKERS} до ${CsqttConstants.Tunnel.MAX_WORKERS}.
                • Значения выбираются кратно ${CsqttConstants.Tunnel.WORKERS_PER_GROUP} потокам.
                • В режиме «Авто ВК» функция недоступна и отключается автоматически.
            """.trimIndent(),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = V2BodyFamily,
                lineHeight = 18.sp,
            ),
            color = colors.textSecondary,
        )
    }
}

@Composable
fun V2TurnTransportInfoDialog(mode: String? = null, onDismiss: () -> Unit) {
    val colors = V2Theme.colors
    val (title, text) = when (mode) {
        CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT -> "Транспорт UDP" to """
            UDP — основной и рекомендуемый путь от устройства до VK TURN. Он даёт наименьшую задержку, не создаёт очередей TCP и лучше всего подходит для игр, стримов и звонков.

            Между VK TURN и вашим VPS также всегда используется UDP.
        """.trimIndent()
        CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS -> "Транспорт TCP" to """
            Этот режим нужен, если оператор режет или блокирует UDP-трафик (например, в отдельных сетях Ростелекома).

            TCP действует только на участке между вашим устройством и узлом VK TURN. Между VK TURN и вашим VPS трафик всегда передаётся по UDP.
        """.trimIndent()
        else -> "Транспорт TURN" to """
            По умолчанию CSQTT использует UDP: это лучший выбор по скорости, задержке и отзывчивости.

            TCP — отдельный режим для сетей, где UDP ограничен оператором.
        """.trimIndent()
    }

    V2DialogContainer(
        title = title,
        onDismiss = onDismiss,
        actions = {
            V2PillButton(
                text = "Понятно",
                onClick = onDismiss,
                style = V2PillStyle.Contrast,
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp,
            )
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = V2BodyFamily,
                lineHeight = 18.sp,
            ),
            color = colors.textSecondary,
        )
    }
}

@Composable
fun V2AutoVkRiskDialog(
    requireAcknowledgement: Boolean,
    onAcknowledge: () -> Unit,
    onDoNotRemind: (() -> Unit)? = null,
) {
    V2TimedWarningDialog(
        title = "Внимание: Режим «Авто ВК»",
        paragraphs = listOf(
            "«Авто ВК» использует браузерные методы VK API для создания звонка и получения TURN credentials без анонимных звонков. Сетевой стек PRIMP маскирует HTTP/TLS-профиль под обычный браузер.",
            "Разработчик не может полностью исключить риск ограничений аккаунта со стороны платформы. Используйте режим на свой риск; если важен основной аккаунт, выбирайте второстепенный.",
        ),
        requireAcknowledgement = requireAcknowledgement,
        onAcknowledge = onAcknowledge,
        onDoNotRemind = onDoNotRemind,
    )
}

@Composable
fun V2TcpTransportRiskDialog(
    onAcknowledge: () -> Unit,
    onDoNotRemind: () -> Unit,
) {
    V2TimedWarningDialog(
        title = "Внимание: TCP транспорт",
        paragraphs = listOf(
            "TCP — экспериментальный режим для сетей, в которых провайдер ограничивает UDP-трафик. TCP используется только между вашим устройством и серверами VK TURN; между VK TURN и вашим VPS трафик всегда идёт по UDP.",
            "Если UDP работает без ограничений, используйте его: UDP стабильнее, быстрее и не подвержен задержкам из-за очереди TCP.",
        ),
        requireAcknowledgement = true,
        onAcknowledge = onAcknowledge,
        onDoNotRemind = onDoNotRemind,
    )
}

@Composable
private fun V2TimedWarningDialog(
    title: String,
    paragraphs: List<String>,
    requireAcknowledgement: Boolean,
    onAcknowledge: () -> Unit,
    onDoNotRemind: (() -> Unit)?,
) {
    val colors = V2Theme.colors
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

    V2DialogContainer(
        title = title,
        onDismiss = { if (actionsEnabled) onAcknowledge() },
        actions = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                if (onDoNotRemind != null) {
                    V2PillButton(
                        text = "Не напоминать",
                        onClick = onDoNotRemind,
                        style = V2PillStyle.Outline,
                        enabled = actionsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        height = 50.dp,
                    )
                }
                V2PillButton(
                    text = if (!actionsEnabled) "Подождите ($secondsLeft сек)" else "Понятно",
                    onClick = onAcknowledge,
                    style = V2PillStyle.Contrast,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    height = 50.dp,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            paragraphs.forEach { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        lineHeight = 18.sp,
                    ),
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
fun V2WifiAutoPauseInfoDialog(onDismiss: () -> Unit) {
    val colors = V2Theme.colors
    V2DialogContainer(
        title = "Автопауза при Wi-Fi",
        onDismiss = onDismiss,
        actions = {
            V2PillButton(
                text = "Понятно",
                onClick = onDismiss,
                style = V2PillStyle.Contrast,
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp,
            )
        },
    ) {
        Text(
            text = "При подключении к Wi-Fi туннель FOCSQ автоматически переводится на паузу, " +
                "снижая нагрузку и экономя трафик. По умолчанию пауза срабатывает на любой сети — " +
                "в разделе «Сети для паузы» можно выбрать конкретные Wi-Fi. " +
                "При переключении обратно на мобильную сеть туннель возобновляет работу.",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = V2BodyFamily,
                lineHeight = 18.sp,
            ),
            color = colors.textSecondary,
        )
    }
}
