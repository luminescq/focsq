package com.csqtt.client.ui.v2.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme

@Composable
fun V2VkTokenRevokeDialog(
    onCancel: () -> Unit,
    onCopyToken: () -> Unit,
    onRevokeToken: () -> Unit,
) {
    val colors = V2Theme.colors

    V2DialogContainer(
        title = "Отзыв VK-авторизации",
        subtitle = "Управление токеном звонков",
        icon = Icons.Default.Delete,
        onDismiss = onCancel,
        actions = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                V2PillButton(
                    text = "Копировать токен",
                    icon = Icons.Default.ContentCopy,
                    onClick = onCopyToken,
                    style = V2PillStyle.Contrast,
                    modifier = Modifier.fillMaxWidth(),
                    height = 50.dp,
                )
                V2PillButton(
                    text = "Удалить токен",
                    icon = Icons.Default.Delete,
                    onClick = onRevokeToken,
                    style = V2PillStyle.Danger,
                    modifier = Modifier.fillMaxWidth(),
                    height = 50.dp,
                )
                V2PillButton(
                    text = "Назад",
                    onClick = onCancel,
                    style = V2PillStyle.Outline,
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
            Text(
                text = "При удалении токена сессия VK сохраняется настолько, насколько это возможно: токен можно будет быстро получить заново, либо вы сможете сменить аккаунт самостоятельно.",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    lineHeight = 18.sp,
                ),
                color = colors.textSecondary,
            )
        }
    }
}
