@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.csqtt.client.ui.v2.screens.connection

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import android.content.ClipboardManager
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.csqtt.client.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ImportedConnectionSettings
import com.csqtt.client.ProfileConnectionSnapshot
import com.csqtt.client.SettingsStore
import com.csqtt.client.SubscriptionFetchResult
import com.csqtt.client.SubscriptionImporter
import com.csqtt.client.SubscriptionPreview
import com.csqtt.client.normalizeSubscriptionUrl
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.legacy.utils.CsqttLink
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2Switch
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

internal data class ProfileEndpoint(val host: String, val port: Int)

private fun normalizeEndpointHost(raw: String): String =
    raw.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase(Locale.ROOT)

internal fun CsqttLink.endpoint(): ProfileEndpoint =
    ProfileEndpoint(normalizeEndpointHost(host), port)

internal fun ProfileConnectionSnapshot.endpoint(): ProfileEndpoint? {
    parseCsqttLink(csqttLink)?.let { return it.endpoint() }
    val rawPeer = peer.trim()
    if (rawPeer.isEmpty()) return null
    val hostPort = Regex("^(.*):(\\d{1,5})$").matchEntire(rawPeer)
    return if (hostPort != null) {
        val port = hostPort.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        ProfileEndpoint(normalizeEndpointHost(hostPort.groupValues[1]), port)
    } else {
        ProfileEndpoint(normalizeEndpointHost(rawPeer), serverPeerPort)
    }
}

/** Endpoint (host + port) of every occupied profile, mapped to its slot. */
internal suspend fun SettingsStore.knownProfileEndpoints(): Map<ProfileEndpoint, Int> {
    val owners = linkedMapOf<ProfileEndpoint, Int>()
    occupiedProfiles.first().sorted().forEach { profile ->
        readProfileConnectionSettings(profile).endpoint()?.let { owners.putIfAbsent(it, profile) }
    }
    return owners
}

/**
 * Parses any csqtt:// link (plain or encrypted) into separate peer/port, password,
 * VK credentials and worker limit before saving it as a regular owner profile.
 */
internal suspend fun SettingsStore.saveImportedLink(
    profile: Int,
    rawLink: String,
    customName: String? = null,
): Boolean {
    val parsed = parseCsqttLink(rawLink) ?: return false
    if (parsed.password.isBlank()) return false
    saveImportedConnection(
        profile = profile,
        imported = ImportedConnectionSettings(
            host = parsed.host,
            port = parsed.port,
            password = parsed.password,
            hashes = parsed.hashes,
            vkAccessToken = parsed.vkAccessToken,
            power = parsed.power,
            name = customName?.trim()?.ifBlank { null } ?: parsed.host,
        ),
    )
    return true
}

@Composable
fun V2AddProfileMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onManual: () -> Unit,
    onFromClipboard: () -> Unit,
    onFromSubscription: () -> Unit,
    modifier: Modifier = Modifier,
    onScanQr: () -> Unit = {},
) {
    val colors = V2Theme.colors

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Scrim with smooth fade
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest,
                    ),
            )
        }

        // Unfolding popup menu with scale & spring
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) +
                scaleIn(
                    initialScale = 0.75f,
                    transformOrigin = TransformOrigin(0.88f, 0f),
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 600f),
                ) +
                expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ),
            exit = fadeOut(tween(130)) +
                scaleOut(
                    targetScale = 0.85f,
                    transformOrigin = TransformOrigin(0.88f, 0f),
                    animationSpec = tween(130),
                ) +
                shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = tween(130),
                ),
            modifier = modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = com.csqtt.client.ui.v2.design.V2Sizes.TopBarHeight + 6.dp,
                    end = com.csqtt.client.ui.v2.design.V2Sizes.ScreenPadding,
                ),
        ) {
            Surface(
                modifier = Modifier.widthIn(min = 280.dp, max = 320.dp),
                shape = RoundedCornerShape(20.dp),
                color = colors.bottomSurface,
                contentColor = colors.textPrimary,
                tonalElevation = 0.dp,
                shadowElevation = 18.dp,
                border = null,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    V2AddProfileMenuItem(
                        iconPainter = painterResource(R.drawable.ic_qr_scan),
                        title = "Сканировать QR",
                        subtitle = "Импорт ссылки через камеру",
                        onClick = onScanQr,
                    )
                    V2AddProfileMenuItem(
                        icon = Icons.Filled.Edit,
                        title = "Вручную",
                        subtitle = "Настроить параметры подключения",
                        onClick = onManual,
                    )
                    V2AddProfileMenuItem(
                        icon = Icons.Filled.ContentPaste,
                        title = "Из буфера",
                        subtitle = "Импорт ссылки csqtt://",
                        onClick = onFromClipboard,
                    )
                    V2AddProfileMenuItem(
                        icon = Icons.Filled.CloudDownload,
                        title = "По подписке",
                        subtitle = "Загрузить список по ссылке",
                        onClick = onFromSubscription,
                    )
                }
            }
        }
    }
}

@Composable
private fun V2AddProfileMenuItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
) {
    val colors = V2Theme.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        contentColor = colors.textPrimary,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        color = colors.surfaceElevated,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                } else if (iconPainter != null) {
                    Icon(
                        painter = iconPainter,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = V2BodyFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 12.sp,
                    ),
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun V2SubscriptionImportDialog(
    settingsStore: SettingsStore,
    onDismiss: () -> Unit,
    onImported: (Int, String) -> Unit,
    initialUrl: String = "",
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val colors = V2Theme.colors

    var urlInput by rememberSaveable { mutableStateOf(initialUrl) }
    var useHttp by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var urlError by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<SubscriptionPreview?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var fetchedUrl by remember { mutableStateOf("") }

    fun load() {
        if (busy) return
        val normalized = normalizeSubscriptionUrl(urlInput, allowHttp = useHttp)
        val host = runCatching { java.net.URI(normalized).host }.getOrNull()
        if (normalized.isEmpty() || host.isNullOrBlank()) {
            urlError = true
            return
        }

        busy = true
        urlError = false
        fetchError = null
        preview = null
        scope.launch {
            when (val result = SubscriptionImporter.fetch(normalized, allowHttp = useHttp)) {
                is SubscriptionFetchResult.Success -> {
                    preview = result.preview
                    fetchedUrl = normalized
                }
                is SubscriptionFetchResult.Failure -> fetchError = result.message
            }
            busy = false
        }
    }

    fun doImport() {
        val current = preview ?: return
        if (busy) return
        busy = true
        scope.launch {
            val summary = SubscriptionImporter.importNodes(
                settingsStore,
                fetchedUrl,
                current,
                allowHttp = useHttp,
            )
            busy = false
            val first = summary.firstProfile
            if (first == null) {
                fetchError = "Все узлы уже добавлены или нет свободных профилей"
                context.showRaisedToast("Не удалось добавить узлы подписки", Toast.LENGTH_LONG)
                return@launch
            }
            onImported(first, summary.message())
        }
    }

    V2DialogContainer(
        title = "Импорт подписки",
        onDismiss = onDismiss,
        subtitle = if (preview == null) {
            "Вставьте ссылку на подписку"
        } else {
            "Проверьте узлы перед импортом"
        },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                V2PillButton(
                    text = "Отмена",
                    onClick = onDismiss,
                    style = V2PillStyle.Outline,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    height = 50.dp,
                )
                V2PillButton(
                    text = if (preview == null) "Загрузить" else "Импорт",
                    onClick = { if (preview == null) load() else doImport() },
                    style = V2PillStyle.Contrast,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    height = 50.dp,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(V2Spacing.Md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                V2TextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it.filterNot(Char::isWhitespace)
                        if (urlError) urlError = false
                        if (preview != null || fetchError != null) {
                            preview = null
                            fetchError = null
                        }
                    },
                    placeholder = "https://example.com/sub",
                    isError = urlError,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )

                Surface(
                    modifier = Modifier
                        .size(54.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !busy,
                            onClick = {
                                if (urlInput.isBlank()) {
                                    val primaryClip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                        ?.primaryClip
                                    val clip = if (primaryClip != null && primaryClip.itemCount > 0) {
                                        primaryClip.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                                    } else {
                                        ""
                                    }
                                    if (clip.isNotBlank()) {
                                        urlInput = clip.filterNot(Char::isWhitespace)
                                        if (urlError) urlError = false
                                        context.showRaisedToast("Ссылка вставлена", Toast.LENGTH_SHORT)
                                    } else {
                                        context.showRaisedToast("Буфер обмена пуст", Toast.LENGTH_SHORT)
                                    }
                                } else {
                                    load()
                                }
                            },
                        ),
                    shape = V2Shapes.Field,
                    color = colors.field,
                    contentColor = colors.accent,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) {
                            LoadingIndicator(
                                modifier = Modifier.size(26.dp),
                                color = colors.accent,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Link,
                                contentDescription = if (urlInput.isBlank()) "Вставить ссылку" else "Загрузить",
                                tint = if (urlInput.isNotBlank()) colors.accent else colors.textMuted,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(V2Shapes.Row)
                    .background(colors.field)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !busy,
                    ) { useHttp = !useHttp }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Скачивать по HTTP",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = V2BodyFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    ),
                    color = if (!busy) colors.textPrimary else colors.textMuted,
                )
                V2Switch(
                    checked = useHttp,
                    onCheckedChange = { useHttp = it },
                    enabled = !busy,
                )
            }
            if (urlError) {
                Text(
                    text = "Укажите корректный URL",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
            fetchError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
            preview?.let { data ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = data.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = V2BodyFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colors.textPrimary,
                        maxLines = 2,
                    )
                    data.nodes.forEach { node ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudDownload,
                                contentDescription = null,
                                tint = colors.textMuted,
                                modifier = Modifier.size(16.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = node.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = V2BodyFamily,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                )
                                Text(
                                    text = buildString {
                                        append(node.host)
                                        append(':')
                                        append(node.port)
                                        node.power?.let {
                                            append(" · ")
                                            append(it)
                                            append(" потоков")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (data.skipped > 0) {
                        Text(
                            text = "Пропущено узлов: ${data.skipped}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}
