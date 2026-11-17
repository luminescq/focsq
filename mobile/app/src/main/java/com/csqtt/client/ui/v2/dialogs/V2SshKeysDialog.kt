@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csqtt.client.ui.v2.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.legacy.dialogs.validateSshCertificate
import com.csqtt.client.ui.legacy.dialogs.validateSshPrivateKey
import com.csqtt.client.ui.v2.components.SuppressAutofill
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2SquareIconButton
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme

private const val PRIVATE_KEY_PLACEHOLDER =
    "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----"

private const val CERTIFICATE_PLACEHOLDER =
    "ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1NTE5...\nuser-cert"

@Composable
fun V2SshKeysDialog(
    initialPrivateKey: String,
    initialPassphrase: String,
    initialCertificate: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = V2Theme.colors
    val scope = rememberCoroutineScope()
    var privateKey by rememberSaveable { mutableStateOf(initialPrivateKey) }
    var passphrase by rememberSaveable { mutableStateOf(initialPassphrase) }
    var certificate by rememberSaveable { mutableStateOf(initialCertificate) }
    var passphraseVisible by rememberSaveable { mutableStateOf(false) }
    var privateKeyError by rememberSaveable { mutableStateOf<String?>(null) }
    var certificateError by rememberSaveable { mutableStateOf<String?>(null) }

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
            Surface(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp),
                shape = RoundedCornerShape(2.dp),
                color = colors.textMuted.copy(alpha = 0.4f),
            ) {}
        },
    ) {
        SuppressAutofill()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V2Spacing.Lg)
                .padding(bottom = V2Spacing.Xl)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Ключи SSH",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = V2BodyFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                V2CircleIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                )
            }

            Text(
                text = "Для входа достаточно одного приватного ключа — приложение само определит его формат. Пароль и сертификат нужны только если ключ зашифрован или используется сертификат OpenSSH.",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            // Private Key Field
            Text(
                text = "Приватный SSH-ключ *",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                ),
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )

            V2TextField(
                value = privateKey,
                onValueChange = {
                    privateKey = it
                    privateKeyError = null
                },
                placeholder = PRIVATE_KEY_PLACEHOLDER,
                isError = privateKeyError != null,
                singleLine = false,
                maxLines = 6,
                modifier = Modifier.heightIn(min = 100.dp, max = 160.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )

            if (privateKeyError != null) {
                Text(
                    text = privateKeyError!!,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = V2BodyFamily),
                    color = colors.error,
                )
            } else {
                Text(
                    text = "Поддерживаются OpenSSH, RSA PEM, EC и PKCS#8",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 12.sp,
                    ),
                    color = colors.textMuted,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Passphrase Field
            Text(
                text = "Пароль ключа (необязательно)",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                ),
                color = colors.textSecondary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                V2TextField(
                    value = passphrase,
                    onValueChange = { passphrase = it.filter { c -> !c.isWhitespace() } },
                    placeholder = "Пароль ключа (необязательно)",
                    visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.weight(1f),
                )
                V2SquareIconButton(
                    icon = if (passphraseVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (passphraseVisible) "Скрыть пароль" else "Показать пароль",
                    onClick = { passphraseVisible = !passphraseVisible },
                    size = 54.dp,
                    iconSize = 22.dp,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Certificate Field
            Text(
                text = "SSH-сертификат OpenSSH (необязательно)",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                ),
                color = colors.textSecondary,
            )

            V2TextField(
                value = certificate,
                onValueChange = {
                    certificate = it
                    certificateError = null
                },
                placeholder = CERTIFICATE_PLACEHOLDER,
                isError = certificateError != null,
                singleLine = false,
                maxLines = 4,
                modifier = Modifier.heightIn(min = 70.dp, max = 120.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )

            if (certificateError != null) {
                Text(
                    text = certificateError!!,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = V2BodyFamily),
                    color = colors.error,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Save Action Pill
            V2PillButton(
                text = "Сохранить",
                onClick = {
                    val keyError = validateSshPrivateKey(privateKey, passphrase)
                    val certError = validateSshCertificate(certificate)
                    privateKeyError = keyError
                    certificateError = certError
                    if (keyError == null && certError == null) {
                        animateDismiss {
                            onSave(privateKey.trim(), passphrase.trim(), certificate.trim())
                        }
                    }
                },
                style = V2PillStyle.Contrast,
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp,
            )
        }
    }
}
