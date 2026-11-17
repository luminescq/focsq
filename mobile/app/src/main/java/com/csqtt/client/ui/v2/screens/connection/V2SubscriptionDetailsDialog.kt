package com.csqtt.client.ui.v2.screens.connection

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.SettingsStore
import com.csqtt.client.SubscriptionMeta
import com.csqtt.client.SubscriptionSync
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2DropdownSetting
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2SquareIconButton
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AUTO_UPDATE_OPTIONS = listOf(
    0 to "Отключено",
    2 to "2 минуты",
    30 to "30 минут",
    60 to "1 час",
    120 to "2 часа",
    360 to "6 часов",
    720 to "12 часов",
    1440 to "24 часа",
)

@Composable
internal fun V2SubscriptionDetailsDialog(
    settingsStore: SettingsStore,
    url: String,
    initialTitle: String,
    nodeCount: Int,
    tunnelBusy: Boolean,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val colors = V2Theme.colors

    val metaByUrl by settingsStore.subscriptionMeta.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )
    val meta = metaByUrl[url] ?: SubscriptionMeta(url = url, title = initialTitle)

    var titleInput by rememberSaveable(url) { mutableStateOf(meta.title.ifBlank { initialTitle }) }
    var urlInput by rememberSaveable(url) { mutableStateOf(url) }
    var intervalMinutes by rememberSaveable(url) { mutableStateOf(meta.autoUpdateMinutes) }
    var initialized by rememberSaveable(url) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var urlError by rememberSaveable { mutableStateOf<String?>(null) }
    var copied by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var lastUpdatedAt by rememberSaveable(url) { mutableStateOf(meta.lastUpdatedAt) }

    LaunchedEffect(meta) {
        if (!initialized) {
            titleInput = meta.title.ifBlank { initialTitle }
            intervalMinutes = meta.autoUpdateMinutes
            initialized = true
        }
        lastUpdatedAt = meta.lastUpdatedAt
    }

    fun allowHttp(): Boolean =
        meta.allowHttp || url.startsWith("http://", ignoreCase = true)

    fun validateUrl(value: String): String? {
        val uri = runCatching { java.net.URI(value.trim()) }.getOrNull()
        if (uri == null || uri.host.isNullOrBlank()) return "Некорректная ссылка подписки"
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> null
            "http" -> if (allowHttp()) null else "HTTP-ссылка не разрешена"
            else -> "Поддерживаются только HTTPS-ссылки"
        }
    }

    fun refresh() {
        if (busy) return
        if (tunnelBusy) {
            context.showRaisedToast(
                "Сначала остановите туннель, чтобы обновить подписку",
                Toast.LENGTH_SHORT,
            )
            return
        }
        val target = urlInput.trim()
        val error = validateUrl(target)
        urlError = error
        if (error != null) return

        busy = true
        scope.launch {
            if (target != url) {
                settingsStore.changeSubscriptionUrl(url, target)
            }
            val result = SubscriptionSync.refresh(settingsStore, target)
            busy = false
            context.showRaisedToast(
                if (result.success) result.message else "Не удалось обновить подписку: ${result.message}",
                Toast.LENGTH_LONG,
            )
        }
    }

    fun save() {
        if (busy) return
        val targetUrl = urlInput.trim()
        val error = validateUrl(targetUrl)
        urlError = error
        if (error != null) return

        val newTitle = titleInput.trim()
        val oldTitle = meta.title.ifBlank { initialTitle }
        busy = true
        scope.launch {
            if (targetUrl != url) {
                settingsStore.changeSubscriptionUrl(url, targetUrl)
            }
            settingsStore.saveSubscriptionAutoUpdate(targetUrl, intervalMinutes)
            if (newTitle != oldTitle) {
                settingsStore.renameSubscription(targetUrl, oldTitle, newTitle)
            }
            val stored = settingsStore.subscriptionMeta.first()[targetUrl]
            if (stored != null) lastUpdatedAt = stored.lastUpdatedAt
            busy = false
            onDismiss()
        }
    }

    V2DialogContainer(
        title = "Подписка",
        subtitle = "Настройки, обновление и удаление",
        onDismiss = onDismiss,
        actions = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(V2Spacing.Xs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Обновить",
                        onClick = { refresh() },
                        icon = Icons.Filled.Refresh,
                        style = V2PillStyle.Outline,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                    V2PillButton(
                        text = "Удалить",
                        onClick = { showDeleteConfirm = true },
                        icon = Icons.Filled.Delete,
                        style = V2PillStyle.Danger,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                }
                V2PillButton(
                    text = "Сохранить",
                    onClick = { save() },
                    style = V2PillStyle.Contrast,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    height = 50.dp,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
        ) {
            V2TextField(
                value = titleInput,
                onValueChange = { titleInput = it },
                placeholder = "Название подписки",
            )
            V2TextField(
                value = urlInput,
                onValueChange = {
                    urlInput = it.trim()
                    if (urlError != null) urlError = null
                },
                placeholder = "https://example.com/sub",
                isError = urlError != null,
                enabled = !busy,
                trailing = {
                    V2SquareIconButton(
                        icon = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = "Скопировать ссылку",
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("subscription", urlInput.trim()),
                            )
                            copied = true
                            context.showRaisedToast("Ссылка скопирована", Toast.LENGTH_SHORT)
                        },
                        size = 38.dp,
                        iconSize = 18.dp,
                        shape = V2Shapes.Field,
                        backgroundColor = androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = colors.textPrimary,
                    )
                },
            )
            urlError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }

            V2DropdownSetting(
                title = "Автообновление",
                value = AUTO_UPDATE_OPTIONS.firstOrNull { it.first == intervalMinutes }?.second
                    ?: "Отключено",
                options = AUTO_UPDATE_OPTIONS.map { it.second },
                enabled = !busy,
                onSelected = { label ->
                    intervalMinutes = AUTO_UPDATE_OPTIONS.firstOrNull { it.second == label }?.first ?: 0
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = formatSubscriptionUpdated(lastUpdatedAt),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 12.sp,
                    ),
                    color = colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Узлов: $nodeCount",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    ),
                    color = colors.textSecondary,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        V2DialogContainer(
            title = "Удалить подписку?",
            subtitle = "Все узлы подписки будут удалены.",
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
                            if (tunnelBusy) {
                                context.showRaisedToast(
                                    "Сначала остановите туннель, чтобы удалить подписку",
                                    Toast.LENGTH_SHORT,
                                )
                                return@V2PillButton
                            }
                            scope.launch {
                                settingsStore.clearSubscription(url)
                                context.showRaisedToast("Подписка удалена", Toast.LENGTH_SHORT)
                                onDeleted()
                                onDismiss()
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
                text = "Узлы, импортированные из этой подписки, будут удалены. Действие необратимо.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
    }
}

internal fun formatSubscriptionUpdated(timestamp: Long): String {
    if (timestamp <= 0L) return "Ещё не обновлялась"
    val now = System.currentTimeMillis()
    val diff = (now - timestamp).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "Обновлена только что"
        diff < 60L * 60_000L -> "Обновлена ${diff / 60_000L} мин назад"
        diff < 24L * 60L * 60_000L -> "Обновлена ${diff / (60L * 60_000L)} ч назад"
        else -> SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
