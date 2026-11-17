package com.csqtt.client.ui.v2.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.AppReleaseInfo
import com.csqtt.client.RemoteVersionSource
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme

@Composable
fun V2AppUpdateDialog(
    release: AppReleaseInfo,
    onPostpone: () -> Unit,
    onUpdate: () -> Unit,
) {
    val colors = V2Theme.colors
    val isTagOnly = release.source == RemoteVersionSource.Tag
    val title = if (isTagOnly) "Найден новый релиз" else "Доступно обновление"
    val description = if (isTagOnly) {
        "На GitHub опубликован новый тег ${release.versionTag}."
    } else {
        "Вышла новая версия приложения ${release.versionTag}. Вы можете перейти на GitHub и обновить приложение."
    }

    V2DialogContainer(
        title = title,
        subtitle = "Версия ${release.versionTag}",
        icon = Icons.Default.Update,
        onDismiss = onPostpone,
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                V2PillButton(
                    text = "Позже",
                    onClick = onPostpone,
                    style = V2PillStyle.Outline,
                    modifier = Modifier.weight(1f),
                    height = 50.dp,
                )
                V2PillButton(
                    text = "Обновить",
                    onClick = onUpdate,
                    style = V2PillStyle.Contrast,
                    modifier = Modifier.weight(1f),
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
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    lineHeight = 18.sp,
                ),
                color = colors.textSecondary,
            )
        }
    }
}
