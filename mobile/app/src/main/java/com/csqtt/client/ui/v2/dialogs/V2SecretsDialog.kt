package com.csqtt.client.ui.v2.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.csqtt.client.SettingsStore
import com.csqtt.client.ui.legacy.utils.TUNNEL_PASSWORD_RULE
import com.csqtt.client.ui.legacy.utils.isValidTunnelPassword
import com.csqtt.client.ui.v2.components.SuppressAutofill
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch

@Composable
fun V2SecretsDialog(
    settingsStore: SettingsStore,
    initialPassword: String,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = V2Theme.colors
    var passwordInput by rememberSaveable { mutableStateOf(initialPassword) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val isPasswordValid = isValidTunnelPassword(passwordInput)

    V2DialogContainer(
        title = "Пароль подключения",
        subtitle = "Пароль для авторизации туннеля на сервере",
        icon = Icons.Default.Key,
        onDismiss = onDismiss,
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                V2PillButton(
                    text = "Отмена",
                    onClick = onDismiss,
                    style = V2PillStyle.Outline,
                    modifier = Modifier.weight(1f),
                    height = 50.dp,
                )
                V2PillButton(
                    text = "Сохранить",
                    onClick = {
                        scope.launch {
                            settingsStore.saveConnectionPassword(passwordInput)
                            onSaved()
                            onDismiss()
                        }
                    },
                    style = V2PillStyle.Contrast,
                    enabled = isPasswordValid,
                    modifier = Modifier.weight(1f),
                    height = 50.dp,
                )
            }
        },
    ) {
        SuppressAutofill()
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            Text(
                text = "Пароль туннеля",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = colors.textPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )

            V2TextField(
                value = passwordInput,
                onValueChange = { passwordInput = it.filter { c -> !c.isWhitespace() } },
                placeholder = "Укажите пароль туннеля",
                isError = passwordInput.isNotEmpty() && !isPasswordValid,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            if (passwordInput.isNotEmpty() && !isPasswordValid) {
                Text(
                    text = TUNNEL_PASSWORD_RULE,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = V2BodyFamily),
                    color = colors.error,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { passwordVisible = !passwordVisible },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (passwordVisible) "Скрыть пароль" else "Показать пароль",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = colors.textSecondary,
                )
            }
        }
    }
}
