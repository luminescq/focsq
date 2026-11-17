package com.csqtt.client.ui.legacy

import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.csqtt.client.SettingsStore
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.R

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.verticalScroll
import com.csqtt.client.CsqttConstants
import com.csqtt.client.TunnelManager
import com.csqtt.client.ui.legacy.components.CsqttSegmentedControl
import com.csqtt.client.ui.legacy.components.CsqttSettingRow
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.csqtt.client.ui.legacy.dialogs.ImportExportDialog
import com.csqtt.client.ui.legacy.dialogs.QrScannerDialog
import com.csqtt.client.ui.legacy.dialogs.SupportDeveloperDialog
import com.csqtt.client.ui.legacy.dialogs.TurnSubnetsDialog

private enum class UsageRole { Owner, Participant }

@Composable
fun FloatingToolbar(
    settingsStore: SettingsStore,
    csqttLinkMode: Boolean,
    activeProfile: Int,
    onActiveProfileChange: (Int) -> Unit,
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    activeFingerprint: String,
    onFingerprintChange: (String) -> Unit,
    autoPauseOnWifi: Boolean,
    onAutoPauseOnWifiChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedRole = if (csqttLinkMode) UsageRole.Participant else UsageRole.Owner
    val vkAuthMode by settingsStore.vkAuthMode.collectAsStateWithLifecycle(
        initialValue = CsqttConstants.VkAuth.MODE_CALLS,
    )
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val selectedTurnSubnet by settingsStore.selectedTurnSubnet.collectAsStateWithLifecycle(
        initialValue = "",
    )
    val turnSubnetIpLimit by settingsStore.turnSubnetIpLimit.collectAsStateWithLifecycle(
        initialValue = "ALL",
    )
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }
    var showSupportDeveloperDialog by rememberSaveable { mutableStateOf(false) }
    var showImportExportDialog by rememberSaveable { mutableStateOf(false) }
    var showAutoPauseInfo by rememberSaveable { mutableStateOf(false) }
    var showTurnSubnetsDialog by rememberSaveable { mutableStateOf(false) }
    val profileNames by settingsStore.profileNames.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )
    var editingProfileIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingProfileName by rememberSaveable { mutableStateOf("") }
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val settingsIconTint = if (isDarkTheme) Color.White else Color.Black

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showQrScanner = true
        } else {
            Toast.makeText(context, "Требуется разрешение на камеру для сканирования QR-кода", Toast.LENGTH_SHORT).show()
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        IconButton(
            onClick = { showSupportDeveloperDialog = true },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_donate_heart),
                contentDescription = "Поддержать разработчика",
                modifier = Modifier.size(24.dp),
                tint = settingsIconTint,
            )
        }

        if (!csqttLinkMode) {
            IconButton(
                onClick = { showImportExportDialog = true },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_import_export),
                    contentDescription = "Импорт и экспорт конфигурации",
                    modifier = Modifier.size(24.dp),
                    tint = settingsIconTint,
                )
            }
        }

        IconButton(
            onClick = {
                val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    showQrScanner = true
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_qr_scan),
                contentDescription = "Сканировать QR-код",
                modifier = Modifier.size(24.dp),
                tint = settingsIconTint,
            )
        }

        IconButton(
            onClick = { isExpanded = true },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_settings_gear),
                contentDescription = stringResource(R.string.quick_settings),
                modifier = Modifier.size(24.dp),
                tint = settingsIconTint,
            )
        }
    }

    if (showSupportDeveloperDialog) {
        SupportDeveloperDialog(
            onDismissRequest = { showSupportDeveloperDialog = false }
        )
    }

    if (showImportExportDialog) {
        ImportExportDialog(
            settingsStore = settingsStore,
            onDismissRequest = { showImportExportDialog = false }
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onDismissRequest = { showQrScanner = false },
            onQrCodeScanned = { rawLink ->
                val parsed = com.csqtt.client.ui.legacy.utils.parseCsqttLink(rawLink)
                if (parsed != null) {
                    showQrScanner = false
                    scope.launch {
                        settingsStore.saveCsqttParticipantLink(rawLink.trim())
                    }
                    Toast.makeText(context, "Ссылка подключена из QR-кода ✓ Режим участника включён", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "QR-код не содержит ссылку FOCSQ", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (isExpanded) {
            Dialog(
                onDismissRequest = { isExpanded = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    shape = CsqttShapes.Dialog,
                    color = appSectionCardColor(),
                    border = BorderStroke(1.dp, appSectionCardBorderColor()),
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth(0.92f)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Настройки",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = { isExpanded = false },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_close),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        "Ваша роль использования",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )

                    CsqttSegmentedControl(
                        options = listOf(
                            UsageRole.Owner to "Владелец",
                            UsageRole.Participant to "Участник",
                        ),
                        selected = selectedRole,
                        onSelected = { role ->
                            val participant = role == UsageRole.Participant
                            if (participant != csqttLinkMode) {
                                scope.launch { settingsStore.saveCsqttLinkMode(participant) }
                            }
                        },
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(CsqttSizes.CompactControlHeight),
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    if (!csqttLinkMode) {
                        CompactDropdownSetting(
                            title = "Профиль конфигурации",
                            selectedKey = activeProfile.toString(),
                            options = (CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX)
                                .map { profile -> profile.toString() to (profileNames[profile] ?: "Профиль $profile") },
                            enabled = true,
                            onSelected = { profile ->
                                profile.toIntOrNull()?.let(onActiveProfileChange)
                            },
                            onItemEdit = { key ->
                                val p = key.toIntOrNull() ?: 0
                                editingProfileIndex = p
                                editingProfileName = profileNames[p] ?: "Профиль $p"
                            },
                        )
                    }

                    CompactDropdownSetting(
                        title = "Отпечаток",
                        selectedKey = activeFingerprint,
                        options = listOf(
                            "chrome" to "Chrome",
                            "safari" to "Safari",
                            "firefox" to "Firefox",
                            "edge" to "Edge",
                            "opera" to "Opera",
                        ),
                        enabled = true,
                        onSelected = onFingerprintChange,
                    )

                    CompactDropdownSetting(
                        title = "Оформление",
                        selectedKey = currentTheme,
                        options = listOf(
                            CsqttConstants.Theme.MODE_SYSTEM to "Система",
                            CsqttConstants.Theme.MODE_LIGHT to "Светлая",
                            CsqttConstants.Theme.MODE_DARK to "Тёмная",
                        ),
                        enabled = true,
                        onSelected = onThemeChange,
                    )

                    CsqttSettingRow(
                        title = "Автопауза при Wi-Fi",
                        checked = autoPauseOnWifi,
                        onCheckedChange = onAutoPauseOnWifiChange,
                        onInfoClick = { showAutoPauseInfo = true },
                        infoContentDescription = "Как работает автопауза при Wi-Fi",
                    )

                    if (!csqttLinkMode) {
                        OutlinedButton(
                            onClick = { showTurnSubnetsDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CsqttSizes.ControlHeight),
                            shape = CsqttShapes.Pill,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            Text(
                                text = "Выбор TURN подсетей",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAutoPauseInfo) {
        WifiAutoPauseInfoDialog(onDismiss = { showAutoPauseInfo = false })
    }

    if (showTurnSubnetsDialog) {
        TurnSubnetsDialog(
            selectedSubnetId = selectedTurnSubnet,
            turnSubnetIpLimit = turnSubnetIpLimit,
            onSelectSubnet = { id ->
                scope.launch { settingsStore.saveSelectedTurnSubnet(id) }
            },
            onSelectIpLimit = { limit ->
                scope.launch { settingsStore.saveTurnSubnetIpLimit(limit) }
            },
            onDismiss = { showTurnSubnetsDialog = false },
        )
    }

    editingProfileIndex?.let { p ->
        Dialog(
            onDismissRequest = { editingProfileIndex = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = CsqttShapes.Dialog,
                color = appSectionCardColor(),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, appSectionCardBorderColor()),
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(0.92f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Название профиля",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = { editingProfileIndex = null },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = editingProfileName,
                        onValueChange = { editingProfileName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = CsqttShapes.Control,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = { editingProfileName = "" },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Очистить",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )

                    Button(
                        onClick = {
                            val targetProfile = p
                            val targetName = editingProfileName
                            editingProfileIndex = null
                            scope.launch {
                                settingsStore.saveProfileName(targetProfile, targetName)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CsqttSizes.ControlHeight),
                        shape = CsqttShapes.Control,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            text = "Сохранить",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
