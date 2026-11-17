package com.csqtt.client.ui.v2.dialogs

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.SettingsStore
import com.csqtt.client.ui.v2.components.V2Card
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.components.V2TransparentIconButton
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

@Composable
fun V2WifiNetworksDialog(
    settingsStore: SettingsStore,
    onDismiss: () -> Unit,
) {
    val colors = V2Theme.colors
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    val savedSsids by settingsStore.autoPauseWifiSsids.collectAsStateWithLifecycle(
        initialValue = emptySet(),
    )
    var currentSsid by remember { mutableStateOf<String?>(null) }
    var hasLocationPermission by remember {
        mutableStateOf(context.hasFineLocationPermission())
    }
    var manualSsid by rememberSaveable { mutableStateOf("") }

    fun refreshCurrentNetwork() {
        hasLocationPermission = context.hasFineLocationPermission()
        currentSsid = if (hasLocationPermission) context.readCurrentWifiSsid() else null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasLocationPermission = granted
        currentSsid = if (granted) context.readCurrentWifiSsid() else null
    }

    LaunchedEffect(Unit) { refreshCurrentNetwork() }

    fun saveSsids(ssids: Set<String>) {
        scope.launch { settingsStore.saveAutoPauseWifiSsids(ssids) }
    }

    V2DialogContainer(
        title = "Сети для автопаузы",
        subtitle = "Пауза только на выбранных Wi-Fi",
        onDismiss = onDismiss,
        actions = {
            V2PillButton(
                text = "Готово",
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
                text = "Если список пуст, туннель встаёт на паузу на любой Wi-Fi сети. " +
                    "Добавьте сети, на которых пауза должна срабатывать.",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    lineHeight = 18.sp,
                ),
                color = colors.textSecondary,
            )

            val activeSsid = currentSsid
            V2Card(
                color = colors.field,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Текущая сеть",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                        Text(
                            text = activeSsid ?: if (hasLocationPermission) {
                                "Нет подключения"
                            } else {
                                "Нужен доступ к геоданным"
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = V2BodyFamily,
                            ),
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    when {
                        activeSsid != null && activeSsid !in savedSsids -> V2PillButton(
                            text = "Добавить",
                            onClick = { saveSsids(savedSsids + activeSsid) },
                            style = V2PillStyle.Outline,
                            height = 40.dp,
                            fontSize = 13.sp,
                        )
                        activeSsid != null -> Text(
                            text = "Добавлена",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textMuted,
                        )
                        !hasLocationPermission -> V2PillButton(
                            text = "Разрешить",
                            onClick = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                            style = V2PillStyle.Outline,
                            height = 40.dp,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            if (!hasLocationPermission) {
                Text(
                    text = "Android скрывает имя Wi-Fi сети без разрешения на местоположение. " +
                        "Сеть можно добавить вручную ниже.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        lineHeight = 16.sp,
                    ),
                    color = colors.textMuted,
                )
            }

            if (savedSsids.isNotEmpty()) {
                savedSsids.sortedBy { it.lowercase() }.forEach { ssid ->
                    V2Card(
                        color = colors.field,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 14.dp,
                            top = 2.dp,
                            end = 4.dp,
                            bottom = 2.dp,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = ssid,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = V2BodyFamily,
                                ),
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            V2TransparentIconButton(
                                icon = Icons.Default.Close,
                                contentDescription = "Удалить сеть",
                                onClick = { saveSsids(savedSsids - ssid) },
                            )
                        }
                    }
                }
            }

            V2TextField(
                value = manualSsid,
                onValueChange = { manualSsid = it },
                placeholder = "Имя сети (SSID)",
            )
            V2PillButton(
                text = "Добавить сеть",
                onClick = {
                    val name = manualSsid.trim()
                    if (name.isNotEmpty()) {
                        saveSsids(savedSsids + name)
                        manualSsid = ""
                    }
                },
                style = V2PillStyle.Outline,
                enabled = manualSsid.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                height = 46.dp,
            )
        }
    }
}

private fun Context.hasFineLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun Context.readCurrentWifiSsid(): String? {
    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return null
    val raw = runCatching { wifiManager.connectionInfo?.ssid }.getOrNull() ?: return null
    val ssid = raw.trim().removeSurrounding("\"")
    return ssid.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
}
