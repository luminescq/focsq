package com.csqtt.client.ui.legacy.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import com.csqtt.client.ui.legacy.appSectionCardBorderColor
import com.csqtt.client.ui.legacy.appSectionCardColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.R
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes

private const val GRAM_TON_WALLET = "UQCsHSj_Bev5AG3vCz-84TQC7BSWjNdNdOjP9M2gWUEmbyD7"
private const val USDT_TON_WALLET = "UQCsHSj_Bev5AG3vCz-84TQC7BSWjNdNdOjP9M2gWUEmbyD7"
private const val USDT_TRC20_WALLET = "TD1oiQiHmjqsRDPxfUjUbSWxEmcr4k7Lob"

private fun copyAddressToClipboard(context: Context, label: String, address: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, address))
    context.showRaisedToast("Адрес скопирован", Toast.LENGTH_SHORT)
}

@Composable
fun CryptoDonateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = CsqttShapes.Dialog,
            color = appSectionCardColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, appSectionCardBorderColor()),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Поддержать криптовалютой",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Закрыть",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                CryptoAddressField(
                    title = "GRAM",
                    instruction = "Перевод Gram в сети TON на криптокошелек:",
                    address = GRAM_TON_WALLET,
                    onCopy = { copyAddressToClipboard(context, "Gram Address", GRAM_TON_WALLET) }
                )

                CryptoAddressField(
                    title = "USDT TON",
                    instruction = "Перевод USDT в сети TON на криптокошелек:",
                    address = USDT_TON_WALLET,
                    onCopy = { copyAddressToClipboard(context, "USDT TON Address", USDT_TON_WALLET) }
                )

                CryptoAddressField(
                    title = "USDT TRC20",
                    instruction = "Перевод USDT в сети TRC20 на криптокошелек:",
                    address = USDT_TRC20_WALLET,
                    onCopy = { copyAddressToClipboard(context, "USDT TRC20 Address", USDT_TRC20_WALLET) }
                )

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CsqttSizes.ControlHeight),
                    shape = CsqttShapes.Pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = "Хорошо",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun CryptoAddressField(
    title: String,
    instruction: String,
    address: String,
    onCopy: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = instruction,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            lineHeight = 15.sp
        )

        Surface(
            shape = CsqttShapes.Control,
            color = if (isDark) Color(0xFF13171F) else Color(0xFFF1F3F7),
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = if (isDark) 0.35f else 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectionContainer(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = CsqttShapes.Pill,
                    color = Color(0xFF121418),
                    contentColor = Color.White,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CsqttShapes.Pill)
                        .clickable(onClick = onCopy)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Скопировать",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
