package com.csqtt.client.ui.v2.screens.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.DeployManager
import com.csqtt.client.LogEntry
import com.csqtt.client.LogLevel
import com.csqtt.client.TunnelManager
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.v2.components.V2Card
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2TopBar
import com.csqtt.client.ui.v2.design.TagColors
import com.csqtt.client.ui.v2.design.V2MonoFamily
import com.csqtt.client.ui.v2.design.V2Palette
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

private val LogTimeWidth = 56.dp

@Composable
fun V2LogsScreen(
    version: String,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onVersionClick: (() -> Unit)? = null,
) {
    val colors = V2Theme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = V2Sizes.ScreenPadding),
    ) {
        V2TopBar(
            version = version,
            title = "Логи",
            onSettings = onSettings,
            onVersionClick = onVersionClick,
        )
        V2Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            color = colors.bottomSurface,
            contentColor = colors.terminalText,
            shape = RoundedCornerShape(26.dp),
            borderColor = null,
            contentPadding = PaddingValues(0.dp),
            contentModifier = Modifier.fillMaxSize(),
        ) {
            V2LogsTerminal()
        }
    }
}

@Composable
private fun V2LogsTerminal() {
    val colors = V2Theme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentLogs = TunnelManager.logs
    val listState = rememberLazyListState()
    val isDeploying by DeployManager.isDeploying.collectAsStateWithLifecycle()

    var followLogs by rememberSaveable { mutableStateOf(true) }
    var readUpToId by rememberSaveable { mutableLongStateOf(0L) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    val lastEntryId = currentLogs.lastOrNull()?.id ?: 0L

    // A drag should stop following immediately, not only after the gesture ends.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                followLogs = false
            }
        }
    }

    // Re-enable following after the user returns to the bottom (fling, wheel, DPAD or mouse).
    LaunchedEffect(listState) {
        var hadScroll = false
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) {
                    hadScroll = true
                } else if (hadScroll) {
                    val atBottom = !listState.canScrollForward
                    followLogs = atBottom
                    if (atBottom) {
                        readUpToId = currentLogs.lastOrNull()?.id ?: 0L
                    }
                }
            }
    }

    LaunchedEffect(followLogs, lastEntryId) {
        if (followLogs) {
            readUpToId = lastEntryId
        }
    }

    // Entry ids restart when the process is recreated; a saved watermark from the previous
    // process would otherwise hide every new log from the unread counter.
    LaunchedEffect(lastEntryId) {
        if (lastEntryId > 0L && readUpToId > lastEntryId) {
            readUpToId = 0L
        }
    }

    LaunchedEffect(lastEntryId, isDeploying, followLogs) {
        if (!followLogs || currentLogs.isEmpty()) return@LaunchedEffect
        if (isDeploying) {
            listState.animateScrollToItem(currentLogs.lastIndex)
        } else {
            // Keep history readable: append without an animation that fights the user.
            listState.scrollToItem(currentLogs.lastIndex)
        }
    }

    val newLogCount = if (followLogs) {
        0
    } else {
        countLogsAfter(currentLogs, readUpToId)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (currentLogs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = colors.terminalMuted,
                    modifier = Modifier.size(38.dp),
                )
                Text(
                    text = "Логи пусты",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.terminalText,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = "Подключитесь, чтобы увидеть события",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.terminalMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 72.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(V2Spacing.Xs),
            ) {
                items(
                    items = currentLogs,
                    key = { it.id },
                    contentType = { "log_line" },
                ) { entry ->
                    V2LogLine(entry)
                }
            }
        }

        AnimatedVisibility(
            visible = !followLogs && currentLogs.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            enter = fadeIn(tween(140)) + slideInVertically(tween(140)) { it / 3 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 3 },
        ) {
            Surface(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(currentLogs.lastIndex)
                        followLogs = true
                    }
                },
                shape = V2Shapes.Pill,
                color = V2Palette.InkDarkElevated,
                contentColor = V2Palette.OnInkDark,
                border = BorderStroke(1.dp, V2Palette.OnInkDarkSubtle),
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "К последним логам",
                        modifier = Modifier.size(18.dp),
                    )
                    if (newLogCount > 0) {
                        Text(
                            text = if (newLogCount > 99) "99+" else newLogCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = V2MonoFamily,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        if (currentLogs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { showClearConfirm = true },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Очистить логи",
                        tint = colors.terminalText,
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(
                    onClick = {
                        val text = currentLogs.joinToString("\n") { entry ->
                            formatLogEntryForCopy(
                                message = entry.message,
                                level = resolveLogLevel(entry.level, entry.priority, entry.isError),
                                timestampMs = entry.timestampMs,
                                count = entry.count,
                            )
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("FOCSQ Logs", text))
                        val message = if (currentLogs.size >= TunnelManager.MAX_LOG_ENTRIES) {
                            "Скопированы последние ${currentLogs.size} записей"
                        } else {
                            "Скопировано записей: ${currentLogs.size}"
                        }
                        context.showRaisedToast(message, android.widget.Toast.LENGTH_SHORT)
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Скопировать последние логи",
                        tint = colors.terminalText,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        V2DialogContainer(
            title = "Очистить логи?",
            subtitle = "Удалённые записи нельзя восстановить.",
            icon = Icons.Filled.Delete,
            onDismiss = { showClearConfirm = false },
            actions = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Отмена",
                        onClick = { showClearConfirm = false },
                        style = V2PillStyle.Outline,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                    V2PillButton(
                        text = "Очистить",
                        onClick = {
                            showClearConfirm = false
                            followLogs = true
                            readUpToId = 0L
                            TunnelManager.clearLogs()
                        },
                        style = V2PillStyle.Danger,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                }
            },
        ) {}
    }
}

private fun resolveTagColors(isSuccess: Boolean, isError: Boolean): TagColors {
    return when {
        isError -> V2Palette.TagError
        isSuccess -> V2Palette.TagSuccess
        else -> V2Palette.TagInfo
    }
}

private fun AnsiColor.toTerminalColor(defaultColor: Color, mutedColor: Color): Color = when (this) {
    AnsiColor.DEFAULT -> defaultColor
    AnsiColor.BLACK, AnsiColor.BRIGHT_BLACK -> mutedColor
    AnsiColor.RED, AnsiColor.BRIGHT_RED -> V2Palette.TagError.text
    AnsiColor.GREEN, AnsiColor.BRIGHT_GREEN -> V2Palette.TagSuccess.text
    AnsiColor.YELLOW, AnsiColor.BRIGHT_YELLOW -> V2Palette.TagWarning.text
    AnsiColor.BLUE, AnsiColor.BRIGHT_BLUE,
    AnsiColor.MAGENTA, AnsiColor.BRIGHT_MAGENTA,
    AnsiColor.CYAN, AnsiColor.BRIGHT_CYAN,
    -> V2Palette.TagInfo.text
    AnsiColor.WHITE, AnsiColor.BRIGHT_WHITE -> defaultColor
}

private fun annotatedLogBody(
    text: String,
    defaultColor: Color,
    mutedColor: Color,
): AnnotatedString {
    val segments = parseAnsiSegments(text)
    if (segments.size == 1 && segments[0].color == AnsiColor.DEFAULT && !segments[0].bold) {
        return AnnotatedString(segments[0].text)
    }
    return buildAnnotatedString {
        segments.forEach { segment ->
            if (segment.text.isEmpty()) return@forEach
            val segmentColor = segment.rgb?.let { rgb ->
                Color(0xFF000000.toInt() or rgb)
            } ?: segment.color.toTerminalColor(defaultColor, mutedColor)
            val style = if (segment.bold) {
                SpanStyle(
                    color = segmentColor,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                SpanStyle(color = segmentColor)
            }
            pushStyle(style)
            append(segment.text)
            pop()
        }
    }
}

@Composable
private fun V2LogLine(
    entry: LogEntry,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors

    val level = resolveLogLevel(entry.level, entry.priority, entry.isError)
    val (rawTag, body) = remember(entry.message, level) {
        parseMessageTagPreservingAnsi(entry.message, level)
    }
    val isError = isErrorLog(rawTag, level, entry.isError)
    val isSuccess = isSuccessLog(rawTag, level, entry.isError)

    val tagColors = resolveTagColors(isSuccess, isError)
    val bodyColor = colors.terminalText

    val annotatedBody = remember(body, bodyColor, colors.terminalMuted) {
        annotatedLogBody(
            text = body,
            defaultColor = bodyColor,
            mutedColor = colors.terminalMuted,
        )
    }
    val time = remember(entry.timestampMs) { formatLogTime(entry.timestampMs) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        verticalAlignment = Alignment.Top,
    ) {
        if (time.isNotEmpty()) {
            Text(
                text = time,
                color = colors.terminalMuted,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                ),
                fontFamily = V2MonoFamily,
                maxLines = 1,
                modifier = Modifier.width(LogTimeWidth),
            )
        }

        Surface(
            shape = V2Shapes.Pill,
            color = tagColors.background,
            contentColor = tagColors.text,
            border = null,
            modifier = Modifier.defaultMinSize(minHeight = 22.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = rawTag.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                    fontFamily = V2MonoFamily,
                    maxLines = 1,
                )
            }
        }

        Text(
            text = annotatedBody,
            color = bodyColor,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
            fontFamily = V2MonoFamily,
            fontWeight = if (isError) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )

        if (entry.count > 1) {
            Surface(
                shape = V2Shapes.Pill,
                color = V2Palette.TagCount.background,
                contentColor = V2Palette.TagCount.text,
                border = null,
                modifier = Modifier.defaultMinSize(minHeight = 18.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "x${entry.count}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        fontFamily = V2MonoFamily,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
