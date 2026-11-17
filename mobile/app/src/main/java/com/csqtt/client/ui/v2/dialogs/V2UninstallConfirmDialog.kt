@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csqtt.client.ui.v2.dialogs

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch

@Composable
fun V2UninstallConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String = "Удаление с сервера",
    description: String = "Будут удалены: бинарник, systemd-сервис, бот, конфигурация FOCSQ и помеченные правила firewall/NAT для FOCSQ.\n\nЭто действие необратимо.",
    confirmLabel: String = "Удалить",
) {
    val colors = V2Theme.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmText by remember { mutableStateOf("") }
    val normalizedConfirm = confirmText.trim().lowercase()
    val isConfirmed = normalizedConfirm == "да" || normalizedConfirm == "yes"

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
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = V2DisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    ),
                    color = colors.textPrimary,
                )

                V2CircleIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = V2BodyFamily,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                color = colors.textSecondary,
            )

            Text(
                text = "Введите «да» или «yes» для подтверждения:",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                ),
                color = colors.textPrimary,
            )

            V2TextField(
                value = confirmText,
                onValueChange = { confirmText = it },
                placeholder = "Введите «да» или «yes»",
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                V2PillButton(
                    text = "Отмена",
                    onClick = { animateDismiss() },
                    style = V2PillStyle.Outline,
                    height = 48.dp,
                    modifier = Modifier.weight(1f),
                )

                V2PillButton(
                    text = confirmLabel,
                    onClick = {
                        animateDismiss {
                            onConfirm()
                        }
                    },
                    icon = Icons.Default.Delete,
                    style = V2PillStyle.Danger,
                    enabled = isConfirmed,
                    height = 48.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
