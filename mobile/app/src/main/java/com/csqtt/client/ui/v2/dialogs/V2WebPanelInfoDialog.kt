@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csqtt.client.ui.v2.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch

@Composable
fun V2WebPanelInfoDialog(onDismiss: () -> Unit) {
    val colors = V2Theme.colors
    val scope = rememberCoroutineScope()
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
                    text = "Веб-панель управления",
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

            // Description paragraphs
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "У FOCSQ есть собственная веб-панель управления сервером. По умолчанию она расположена на порту 46002; если включить режим ручных портов, порт можно изменить на свой.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
                    color = colors.textSecondary,
                )

                Text(
                    text = "Кнопка «Панель» открывает панель в браузере по адресу вашего сервера. Чтобы панель заработала, укажите IP сервера, придумайте логин и пароль в окне «Авторизация» и установите FOCSQ на сервер.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
                    color = colors.textSecondary,
                )
            }

            V2PillButton(
                text = "Понятно",
                onClick = { animateDismiss() },
                style = V2PillStyle.Contrast,
                height = 48.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
