package com.csqtt.client.ui.v2.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.SettingsStore
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.v2.components.V2Card
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import com.csqtt.client.ui.v2.screens.connection.endpoint
import com.csqtt.client.ui.v2.screens.connection.knownProfileEndpoints
import com.csqtt.client.ui.v2.screens.connection.saveImportedLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun V2BrowserImportDialog(
    rawLink: String,
    settingsStore: SettingsStore,
    onDismiss: () -> Unit,
    onImported: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    val scope = rememberCoroutineScope()
    val link = remember(rawLink) { parseCsqttLink(rawLink) }
    val missingPassword = link?.password.isNullOrBlank()

    var profileName by rememberSaveable(rawLink) {
        mutableStateOf(link?.host.orEmpty())
    }
    var busy by rememberSaveable { mutableStateOf(false) }
    var targetSlot by remember { mutableStateOf<Int?>(null) }
    var duplicateSlot by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(rawLink) {
        if (link != null) {
            val known = settingsStore.knownProfileEndpoints()
            val duplicate = known[link.endpoint()]
            duplicateSlot = duplicate
            targetSlot = duplicate ?: settingsStore.findFirstFreeProfile() ?: settingsStore.activeProfile.first()
        }
    }

    if (link == null) {
        onDismiss()
        return
    }

    fun doImport() {
        if (busy) return
        val slot = targetSlot ?: return
        busy = true
        scope.launch {
            val finalName = profileName.trim().ifBlank { link.host }
            if (!settingsStore.saveImportedLink(slot, rawLink, finalName)) {
                busy = false
                onDismiss()
                return@launch
            }
            settingsStore.saveActiveProfile(slot)
            busy = false
            onImported(slot, finalName)
        }
    }

    V2DialogContainer(
        title = "Импорт конфигурации",
        subtitle = "Получена внешняя ссылка FOCSQ",
        onDismiss = onDismiss,
        modifier = modifier,
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                V2PillButton(
                    text = "Отмена",
                    onClick = onDismiss,
                    style = V2PillStyle.Outline,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    height = 50.dp,
                )
                V2PillButton(
                    text = "Импортировать",
                    onClick = { doImport() },
                    style = V2PillStyle.Contrast,
                    enabled = !busy && targetSlot != null && !missingPassword,
                    modifier = Modifier.weight(1f),
                    height = 50.dp,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(V2Spacing.Md)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Название профиля",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = V2DisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    ),
                    color = colors.textSecondary,
                )
                V2TextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    placeholder = link.host,
                    singleLine = true,
                    enabled = !busy,
                )
            }

            V2Card(
                modifier = Modifier.fillMaxWidth(),
                shape = V2Shapes.Row,
                color = colors.field,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Сервер",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = V2BodyFamily,
                                color = colors.textSecondary,
                            ),
                        )
                        Text(
                            text = "${link.host}:${link.port}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = V2BodyFamily,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = colors.textPrimary,
                        )
                    }

                    if (link.power != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Потоков",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = V2BodyFamily,
                                    color = colors.textSecondary,
                                ),
                            )
                            Text(
                                text = "${link.power}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = V2BodyFamily,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = colors.accent,
                            )
                        }
                    }

                    if (link.hashes.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Хешей звонков",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = V2BodyFamily,
                                    color = colors.textSecondary,
                                ),
                            )
                            Text(
                                text = "${link.hashes.size}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = V2BodyFamily,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = colors.accent,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Профиль",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = V2BodyFamily,
                                color = colors.textSecondary,
                            ),
                        )
                        Text(
                            text = if (targetSlot != null) "Новый" else "—",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = V2DisplayFamily,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = colors.textPrimary,
                        )
                    }
                }
            }

            if (missingPassword) {
                Text(
                    text = "В ссылке нет пароля подключения — импорт недоступен.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                    color = colors.error,
                )
            }

            if (duplicateSlot != null) {
                Text(
                    text = "Этот сервер уже добавлен. При импорте параметры профиля будут обновлены.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                    color = colors.accent,
                )
            }
        }
    }
}
