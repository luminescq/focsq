@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.csqtt.client.ui.v2.screens.connection

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.CsqttConstants
import com.csqtt.client.ProfileSubscriptionInfo
import com.csqtt.client.R
import com.csqtt.client.SettingsStore
import com.csqtt.client.SubscriptionMeta
import com.csqtt.client.SubscriptionSync
import com.csqtt.client.defaultProfileName
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.v2.components.V2Card
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch

@Composable
internal fun V2ProfilePickerMenu(
    settingsStore: SettingsStore,
    activeProfile: Int,
    profileNames: Map<Int, String>,
    occupiedProfiles: Set<Int>,
    profileSubscriptions: Map<Int, ProfileSubscriptionInfo>,
    tunnelBusy: Boolean,
    onDismiss: () -> Unit,
    onEditProfile: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 340.dp,
) {
    val colors = V2Theme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext

    val metaByUrl by settingsStore.subscriptionMeta.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    var refreshingUrl by remember { mutableStateOf<String?>(null) }
    var deletingSubscription by remember { mutableStateOf<Pair<String, String>?>(null) }
    var detailsUrl by rememberSaveable { mutableStateOf<String?>(null) }

    fun selectProfile(profile: Int) {
        if (profile == activeProfile) {
            onDismiss()
            return
        }
        if (tunnelBusy) {
            context.showRaisedToast(
                "Сначала остановите туннель, чтобы сменить профиль",
                Toast.LENGTH_SHORT,
            )
            return
        }
        scope.launch { settingsStore.saveActiveProfile(profile) }
        onDismiss()
    }

    fun refreshSubscription(url: String) {
        if (tunnelBusy) {
            context.showRaisedToast(
                "Сначала остановите туннель, чтобы обновить подписку",
                Toast.LENGTH_SHORT,
            )
            return
        }
        if (refreshingUrl != null) return
        refreshingUrl = url
        scope.launch {
            val result = SubscriptionSync.refresh(settingsStore, url)
            refreshingUrl = null
            context.showRaisedToast(
                if (result.success) result.message else "Не удалось обновить подписку: ${result.message}",
                Toast.LENGTH_LONG,
            )
        }
    }

    val visibleSlots = (CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX)
        .filter { it in occupiedProfiles }
    val subscriptionGroups = linkedMapOf<String, MutableList<Int>>()
    metaByUrl.keys.filter { it.isNotBlank() }.forEach { url ->
        subscriptionGroups[url] = mutableListOf()
    }
    val standaloneSlots = mutableListOf<Int>()
    for (slot in visibleSlots) {
        val info = profileSubscriptions[slot]
        if (info != null && info.url.isNotBlank()) {
            subscriptionGroups.getOrPut(info.url) { mutableListOf() }.add(slot)
        } else {
            standaloneSlots.add(slot)
        }
    }

    if (visibleSlots.isEmpty() && subscriptionGroups.isEmpty()) {
        V2Card(
            modifier = modifier.fillMaxWidth(),
            shape = V2Shapes.Dialog,
            color = colors.bottomSurface,
            contentPadding = PaddingValues(V2Spacing.Sm),
        ) {
            Text(
                text = "Нет профилей — нажмите +",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = V2Spacing.Md, vertical = V2Spacing.Lg),
            )
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            subscriptionGroups.entries.forEach { (url, slots) ->
                val info = slots.firstOrNull()?.let { profileSubscriptions[it] }
                val meta = metaByUrl[url]
                val title = subscriptionGroupTitle(info, meta)
                val expanded = url !in collapsed
                V2SubscriptionCard(
                    title = title,
                    nodeCount = slots.size,
                    expanded = expanded && slots.isNotEmpty(),
                    onToggle = {
                        if (slots.isNotEmpty()) {
                            if (url in collapsed) collapsed.remove(url) else collapsed[url] = true
                        }
                    },
                    refreshing = refreshingUrl == url,
                    onRefresh = { refreshSubscription(url) },
                    onEdit = { detailsUrl = url },
                    onDelete = { deletingSubscription = url to title },
                ) {
                    slots.forEach { slot ->
                        V2ProfilePickerRow(
                            name = profileNames[slot] ?: defaultProfileName(slot),
                            selected = slot == activeProfile,
                            indent = true,
                            onSelect = { selectProfile(slot) },
                            onEdit = {
                                onEditProfile(slot)
                                onDismiss()
                            },
                        )
                    }
                }
            }
            if (standaloneSlots.isNotEmpty()) {
                V2Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = V2Shapes.Row,
                    color = colors.bottomSurface,
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    standaloneSlots.forEach { slot ->
                        V2ProfilePickerRow(
                            name = profileNames[slot] ?: defaultProfileName(slot),
                            selected = slot == activeProfile,
                            indent = false,
                            onSelect = { selectProfile(slot) },
                            onEdit = {
                                onEditProfile(slot)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }

    deletingSubscription?.let { (url, title) ->
        V2DialogContainer(
            title = "Удалить подписку?",
            subtitle = "Все узлы подписки «$title» будут удалены.",
            icon = Icons.Filled.Delete,
            onDismiss = { deletingSubscription = null },
            actions = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Отмена",
                        onClick = { deletingSubscription = null },
                        style = V2PillStyle.Outline,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                    V2PillButton(
                        text = "Удалить",
                        onClick = {
                            val toDeleteUrl = url
                            deletingSubscription = null
                            if (tunnelBusy) {
                                context.showRaisedToast(
                                    "Сначала остановите туннель, чтобы удалить подписку",
                                    Toast.LENGTH_SHORT,
                                )
                                return@V2PillButton
                            }
                            scope.launch {
                                settingsStore.clearSubscription(toDeleteUrl)
                                context.showRaisedToast("Подписка удалена", Toast.LENGTH_SHORT)
                            }
                        },
                        style = V2PillStyle.Danger,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                }
            },
        ) {}
    }

    detailsUrl?.let { url ->
        val info = profileSubscriptions.values.firstOrNull { it.url == url }
        V2SubscriptionDetailsDialog(
            settingsStore = settingsStore,
            url = url,
            initialTitle = subscriptionGroupTitle(info, metaByUrl[url]),
            nodeCount = profileSubscriptions.count { it.value.url == url },
            tunnelBusy = tunnelBusy,
            onDismiss = { detailsUrl = null },
            onDeleted = { detailsUrl = null },
        )
    }
}

@Composable
private fun V2SubscriptionCard(
    title: String,
    nodeCount: Int,
    expanded: Boolean,
    refreshing: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = V2Theme.colors
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "chevronRotation",
    )

    V2Card(
        modifier = Modifier.fillMaxWidth(),
        shape = V2Shapes.Row,
        color = colors.bottomSurface,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggle,
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(colors.accentSoft.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudDownload,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = V2BodyFamily,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Узлов: $nodeCount",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = V2BodyFamily,
                        ),
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (refreshing) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceElevated.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadingIndicator(
                                color = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        PickerCircleButton(
                            icon = Icons.Filled.Refresh,
                            contentDescription = "Обновить подписку",
                            onClick = onRefresh,
                        )
                    }
                    PickerCircleButton(
                        icon = Icons.Filled.Edit,
                        contentDescription = "Настройки подписки",
                        onClick = onEdit,
                    )
                    PickerCircleButton(
                        icon = Icons.Outlined.Delete,
                        contentDescription = "Удалить подписку",
                        onClick = onDelete,
                        tint = colors.error.copy(alpha = 0.85f),
                        backgroundColor = colors.error.copy(alpha = 0.12f),
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceElevated.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (expanded) "Свернуть" else "Развернуть",
                            tint = colors.textSecondary,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(chevronRotation),
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(160)) + fadeIn(tween(160)),
                exit = shrinkVertically(animationSpec = tween(120)) + fadeOut(tween(120)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        thickness = 0.75.dp,
                        color = colors.outline.copy(alpha = 0.35f),
                    )
                    content()
                }
            }
        }
    }
}

@Composable
private fun PickerCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = V2Theme.colors.textSecondary,
    backgroundColor: Color = V2Theme.colors.surfaceElevated.copy(alpha = 0.55f),
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun V2ProfilePickerRow(
    name: String,
    selected: Boolean,
    indent: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = V2Theme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) colors.accentSoft.copy(alpha = 0.4f) else Color.Transparent
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
                onLongClick = onEdit,
            )
            .padding(
                start = if (indent) 24.dp else 10.dp,
                end = 8.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (selected) colors.accentSoft else colors.surfaceElevated.copy(alpha = 0.45f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_v2_flame),
                contentDescription = null,
                tint = if (selected) colors.accent else colors.textMuted,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = V2BodyFamily,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            ),
            color = if (selected) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PickerCircleButton(
            icon = Icons.Filled.Edit,
            contentDescription = "Редактировать профиль",
            onClick = onEdit,
            tint = colors.textMuted,
            backgroundColor = Color.Transparent,
        )
    }
}

internal fun subscriptionGroupTitle(
    info: ProfileSubscriptionInfo?,
    meta: SubscriptionMeta? = null,
): String {
    val metaTitle = meta?.title.orEmpty().trim()
    if (metaTitle.isNotEmpty()) return metaTitle
    val title = info?.title.orEmpty().trim()
    if (title.isNotEmpty()) return title
    val host = info?.url
        ?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        .orEmpty()
    return host.ifBlank { "Подписка" }
}
