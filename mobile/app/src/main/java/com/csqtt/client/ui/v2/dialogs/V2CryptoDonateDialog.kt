package com.csqtt.client.ui.v2.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme

private const val GRAM_TON_WALLET = "UQCsHSj_Bev5AG3vCz-84TQC7BSWjNdNdOjP9M2gWUEmbyD7"
private const val USDT_TON_WALLET = "UQCsHSj_Bev5AG3vCz-84TQC7BSWjNdNdOjP9M2gWUEmbyD7"
private const val USDT_TRC20_WALLET = "TD1oiQiHmjqsRDPxfUjUbSWxEmcr4k7Lob"

private fun copyAddressToClipboard(context: Context, label: String, address: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, address))
    context.showRaisedToast("Адрес скопирован", Toast.LENGTH_SHORT)
}

@Composable
fun V2CryptoDonateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = V2Theme.colors

    V2DialogContainer(
        title = "Криптовалюта",
        subtitle = "Поддержать развитие проекта",
        onDismiss = onDismiss,
        actions = {
            V2PillButton(
                text = "Закрыть",
                onClick = onDismiss,
                style = V2PillStyle.Contrast,
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            Text(
                text = "Нажмите на карточку, чтобы скопировать адрес кошелька:",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    lineHeight = 18.sp,
                ),
                color = colors.textSecondary,
            )

            Spacer(modifier = Modifier.height(4.dp))

            V2CryptoItem(
                badge = "GRAM",
                title = "GRAM (TON)",
                wallet = GRAM_TON_WALLET,
                onClick = { copyAddressToClipboard(context, "GRAM (TON)", GRAM_TON_WALLET) },
            )

            V2CryptoItem(
                badge = "USDT",
                title = "USDT (TON)",
                wallet = USDT_TON_WALLET,
                onClick = { copyAddressToClipboard(context, "USDT (TON)", USDT_TON_WALLET) },
            )

            V2CryptoItem(
                badge = "TRC20",
                title = "USDT (TRC20)",
                wallet = USDT_TRC20_WALLET,
                onClick = { copyAddressToClipboard(context, "USDT (TRC20)", USDT_TRC20_WALLET) },
            )
        }
    }
}

@Composable
private fun V2CryptoItem(
    badge: String,
    title: String,
    wallet: String,
    onClick: () -> Unit,
) {
    val colors = V2Theme.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(V2Shapes.Field)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = V2Shapes.Field,
        color = colors.field,
        border = null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.bottomSurface)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = V2BodyFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                    ),
                    color = colors.accent,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = V2BodyFamily,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                    color = colors.textPrimary,
                )
                Text(
                    text = wallet,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Копировать",
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
