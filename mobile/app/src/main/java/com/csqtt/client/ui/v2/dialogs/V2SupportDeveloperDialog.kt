package com.csqtt.client.ui.v2.dialogs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.CSQTTColors
import com.csqtt.client.CsqttConstants
import com.csqtt.client.R
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme

private fun openBrowserLink(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.w("CSQTT", "Не удалось открыть ссылку: $url", e)
        context.showRaisedToast("Не удалось открыть браузер", Toast.LENGTH_SHORT)
    }
}

@Composable
fun V2SupportDeveloperDialog(
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val colors = V2Theme.colors
    var showCryptoDialog by remember { mutableStateOf(false) }

    if (showCryptoDialog) {
        V2CryptoDonateDialog(onDismiss = { showCryptoDialog = false })
        return
    }

    V2DialogContainer(
        title = "Поддержать проект",
        subtitle = "Помощь разработчикам и серверной инфраструктуре",
        icon = Icons.Default.Favorite,
        onDismiss = onDismissRequest,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
        ) {
            Text(
                text = "FOCSQ развивается благодаря сообществу. Вы можете подписаться на официальный канал или поддержать нас криптовалютой.",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    lineHeight = 18.sp,
                ),
                color = colors.textSecondary,
            )

            V2PillButton(
                text = "Telegram: @amurcanov_telegram",
                iconPainter = painterResource(R.drawable.ic_telegram),
                onClick = { openBrowserLink(context, CsqttConstants.Links.TELEGRAM) },
                style = V2PillStyle.Contrast,
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp,
            )

            V2PillButton(
                text = "Криптовалюта (TON, USDT)",
                iconPainter = painterResource(R.drawable.ic_crypto_logo),
                onClick = { showCryptoDialog = true },
                style = V2PillStyle.Outline,
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp,
            )
        }
    }
}
