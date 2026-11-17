@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csqtt.client.ui.v2.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.TurnSubnetRegistry
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2DropdownPill
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2Switch
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.roundToInt

@Composable
fun V2TurnSubnetsDialog(
    selectedSubnetId: String,
    turnSubnetIpLimit: String = "ALL",
    onSelectSubnet: (String) -> Unit,
    onSelectIpLimit: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val colors = V2Theme.colors
    val scope = rememberCoroutineScope()
    var currentSubnetId by remember(selectedSubnetId) { mutableStateOf(selectedSubnetId) }
    var currentIpLimit by remember(turnSubnetIpLimit) { mutableStateOf(turnSubnetIpLimit) }
    val limitInt = currentIpLimit.toIntOrNull()

    val pingResults = remember { mutableStateMapOf<String, Int?>() }
    val pingStatus = remember { mutableStateMapOf<String, Boolean>() }
    var isPinging by remember { mutableStateOf(false) }

    val eligibleSubnets = TurnSubnetRegistry.subnets.filter { limitInt == null || it.count >= limitInt }

    val sortedEligibleIds = eligibleSubnets
        .mapNotNull { subnet ->
            val ping = pingResults[subnet.id]
            if (ping != null && ping > 0) subnet.id to ping else null
        }
        .sortedBy { it.second }
        .map { it.first }

    fun getPingColor(subnetId: String): Color {
        if (isPinging) return colors.accent
        val ping = pingResults[subnetId] ?: return Color.Unspecified
        if (ping <= 0) return colors.error
        val rank = sortedEligibleIds.indexOf(subnetId)
        val total = sortedEligibleIds.size
        if (rank == -1 || total == 0) return colors.error
        val greenThreshold = (total + 2) / 3
        val yellowThreshold = (2 * total + 2) / 3
        return when {
            rank < greenThreshold -> colors.success
            rank < yellowThreshold -> colors.warning
            else -> colors.error
        }
    }

    fun startPingCheck() {
        if (isPinging) return
        scope.launch {
            isPinging = true
            pingResults.clear()
            pingStatus.clear()
            eligibleSubnets.forEach { subnet ->
                pingStatus[subnet.id] = true
                val rtt = withContext(Dispatchers.IO) {
                    measureSubnetRtt(subnet.ips)
                }
                pingResults[subnet.id] = rtt?.roundToInt() ?: -1
                pingStatus[subnet.id] = false
            }
            isPinging = false
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun animateDismiss(onDone: () -> Unit = onDismiss) {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            onDone()
        }
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
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Dns,
                        contentDescription = null,
                        tint = colors.contrast,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = "Выбор TURN подсетей",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = V2BodyFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                        color = colors.textPrimary,
                    )
                }
                V2CircleIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                )
            }

            // Description
            Text(
                text = "Выберите подсеть для распределения потоков. Если все выключены — используются адреса по умолчанию из ответа VK.",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
                color = colors.textSecondary,
            )

            // Subnets list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TurnSubnetRegistry.subnets.forEach { subnet ->
                    val isEligible = limitInt == null || subnet.count >= limitInt
                    val isChecked = isEligible && currentSubnetId == subnet.id
                    val cardBg = if (isChecked) colors.surfaceElevated else colors.field

                    Surface(
                        shape = V2Shapes.Row,
                        color = if (isEligible) cardBg else colors.field.copy(alpha = 0.45f),
                        contentColor = colors.textPrimary,
                        border = null,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = isEligible,
                            ) {
                                val nextId = if (isChecked) "" else subnet.id
                                currentSubnetId = nextId
                                onSelectSubnet(nextId)
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = subnet.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = V2BodyFamily,
                                        fontWeight = if (isChecked) FontWeight.Bold else FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                    ),
                                    color = if (isEligible) colors.textPrimary else colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val poolSubtitle = when {
                                    subnet.count % 10 == 1 && subnet.count % 100 != 11 -> "${subnet.count} сервер в пуле"
                                    subnet.count % 10 in 2..4 && subnet.count % 100 !in 12..14 -> "${subnet.count} сервера в пуле"
                                    else -> "${subnet.count} серверов в пуле"
                                }
                                Text(
                                    text = poolSubtitle,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = V2BodyFamily,
                                        fontSize = 12.sp,
                                    ),
                                    color = if (isEligible) colors.textSecondary else colors.textMuted.copy(alpha = 0.6f),
                                )
                            }

                            if (isEligible) {
                                val ping = pingResults[subnet.id]
                                val isCurrentPinging = pingStatus[subnet.id] == true
                                if (isCurrentPinging) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = colors.accent,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                } else if (ping != null) {
                                    Text(
                                        text = if (ping > 0) "$ping мс" else "—",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = V2BodyFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                        ),
                                        color = getPingColor(subnet.id),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                            }

                            V2Switch(
                                checked = isChecked,
                                enabled = isEligible,
                                onCheckedChange = { checked ->
                                    val nextId = if (checked) subnet.id else ""
                                    currentSubnetId = nextId
                                    onSelectSubnet(nextId)
                                },
                            )
                        }
                    }
                }
            }

            // IP limit setting
            Surface(
                shape = V2Shapes.Row,
                color = colors.field,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Колл-во используемых IP",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = V2BodyFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        ),
                        color = colors.textPrimary,
                    )
                    V2DropdownPill(
                        value = if (currentIpLimit == "ALL") "ВСЕ" else currentIpLimit,
                        options = listOf("ВСЕ", "1", "2", "4", "9"),
                        onSelected = { label ->
                            val newLimit = if (label == "ВСЕ") "ALL" else label
                            currentIpLimit = newLimit
                            onSelectIpLimit(newLimit)
                            val newLimitInt = newLimit.toIntOrNull()
                            if (newLimitInt != null) {
                                val currentSubnet = TurnSubnetRegistry.getSubnet(currentSubnetId)
                                if (currentSubnet == null || currentSubnet.count < newLimitInt) {
                                    val firstMatch = TurnSubnetRegistry.subnets.firstOrNull { it.count >= newLimitInt }
                                    if (firstMatch != null) {
                                        currentSubnetId = firstMatch.id
                                        onSelectSubnet(firstMatch.id)
                                    }
                                }
                            }
                        },
                    )
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                V2PillButton(
                    text = if (isPinging) "Пинг..." else "Проверить пинг",
                    onClick = { startPingCheck() },
                    style = V2PillStyle.Outline,
                    enabled = !isPinging,
                    height = 48.dp,
                    modifier = Modifier.weight(1f),
                )
                V2PillButton(
                    text = "Сохранить",
                    onClick = {
                        onSelectSubnet(currentSubnetId)
                        onSelectIpLimit(currentIpLimit)
                        animateDismiss()
                    },
                    style = V2PillStyle.Contrast,
                    height = 48.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun measureSubnetRtt(ips: List<String>): Double? {
    if (ips.isEmpty()) return null
    val first3 = ips.take(3)
    val last3 = ips.takeLast(3)
    val firstRtts = first3.mapNotNull { probeStunRtt(it) }
    val lastRtts = last3.mapNotNull { probeStunRtt(it) }
    val avg1 = if (firstRtts.isNotEmpty()) firstRtts.average() else null
    val avg2 = if (lastRtts.isNotEmpty()) lastRtts.average() else null
    return when {
        avg1 != null && avg2 != null -> (avg1 + avg2) / 2.0
        avg1 != null -> avg1
        avg2 != null -> avg2
        else -> null
    }
}

private fun probeStunRtt(ip: String): Double? {
    var socket: DatagramSocket? = null
    return try {
        socket = DatagramSocket()
        socket.soTimeout = 300
        val req = byteArrayOf(0, 1, 0, 0, 0x21, 0x12, 0xa4.toByte(), 0x42) + ByteArray(12) { (0..255).random().toByte() }
        val addr = InetAddress.getByName(ip)
        val t0 = System.nanoTime()
        socket.send(DatagramPacket(req, req.size, addr, 19302))
        val resp = ByteArray(64)
        socket.receive(DatagramPacket(resp, resp.size))
        val t1 = System.nanoTime()
        (t1 - t0) / 1_000_000.0
    } catch (_: Exception) {
        null
    } finally {
        socket?.close()
    }
}
