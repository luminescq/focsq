package com.csqtt.client.ui.legacy.dialogs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.SettingsStore
import com.csqtt.client.ui.legacy.appSectionCardBorderColor
import com.csqtt.client.ui.legacy.appSectionCardColor
import com.csqtt.client.ui.legacy.components.CsqttSegmentedControl
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private enum class ImportExportTab {
    EXPORT,
    IMPORT
}

@Composable
fun ImportExportDialog(
    settingsStore: SettingsStore,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    var selectedTab by rememberSaveable { mutableStateOf(ImportExportTab.EXPORT) }

    var includeTunnel by rememberSaveable { mutableStateOf(true) }
    var includeDeploy by rememberSaveable { mutableStateOf(true) }
    var includeExceptions by rememberSaveable { mutableStateOf(true) }
    var includeSettings by rememberSaveable { mutableStateOf(true) }

    var isProcessing by remember { mutableStateOf(false) }

    val executeImportJson: (String) -> Unit = { jsonText ->
        if (jsonText.isBlank()) {
            Toast.makeText(context, "Конфигурация пуста", Toast.LENGTH_SHORT).show()
        } else {
            scope.launch {
                isProcessing = true
                try {
                    val ok = settingsStore.importConfigJson(
                        jsonString = jsonText,
                        includeTunnel = includeTunnel,
                        includeDeploy = includeDeploy,
                        includeExceptions = includeExceptions,
                        includeSettings = includeSettings
                    )
                    if (ok) {
                        Toast.makeText(context, "Конфигурация успешно импортирована ✓", Toast.LENGTH_LONG).show()
                        onDismissRequest()
                    } else {
                        Toast.makeText(context, "Ошибка импорта: неверный формат JSON", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Ошибка импорта: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().use { it.readText() }
                    executeImportJson(text)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось прочитать файл: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
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
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Импорт и Экспорт",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Закрыть",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                CsqttSegmentedControl(
                    options = listOf(
                        ImportExportTab.EXPORT to "Экспорт",
                        ImportExportTab.IMPORT to "Импорт"
                    ),
                    selected = selectedTab,
                    onSelected = { selectedTab = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CsqttSizes.CompactControlHeight)
                )

                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    ConfigOptionRow(
                        title = "Туннель",
                        checked = includeTunnel,
                        onCheckedChange = { includeTunnel = it }
                    )
                    ConfigOptionRow(
                        title = "Деплой",
                        checked = includeDeploy,
                        onCheckedChange = { includeDeploy = it }
                    )
                    ConfigOptionRow(
                        title = "Маршрут",
                        checked = includeExceptions,
                        onCheckedChange = { includeExceptions = it }
                    )
                    ConfigOptionRow(
                        title = "Настройки",
                        checked = includeSettings,
                        onCheckedChange = { includeSettings = it }
                    )
                }

                if (selectedTab == ImportExportTab.EXPORT) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isProcessing = true
                                    try {
                                        val json = settingsStore.exportConfigJson(
                                            includeTunnel = includeTunnel,
                                            includeDeploy = includeDeploy,
                                            includeExceptions = includeExceptions,
                                            includeSettings = includeSettings
                                        )
                                        clipboardManager.setText(AnnotatedString(json))
                                        Toast.makeText(context, "Конфиг скопирован в буфер ✓", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Ошибка экспорта: ${e.message}", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            },
                            enabled = !isProcessing && (includeTunnel || includeDeploy || includeExceptions || includeSettings),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CsqttSizes.ControlHeight),
                            shape = CsqttShapes.Pill,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Копировать в буфер",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isProcessing = true
                                    try {
                                        val json = settingsStore.exportConfigJson(
                                            includeTunnel = includeTunnel,
                                            includeDeploy = includeDeploy,
                                            includeExceptions = includeExceptions,
                                            includeSettings = includeSettings
                                        )
                                        val savedPath = saveJsonToDownloads(context, json)
                                        if (savedPath != null) {
                                            Toast.makeText(context, "Сохранено: $savedPath ✓", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Не удалось сохранить файл", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            },
                            enabled = !isProcessing && (includeTunnel || includeDeploy || includeExceptions || includeSettings),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CsqttSizes.ControlHeight),
                            shape = CsqttShapes.Pill,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Скачать в Downloads",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    executeImportJson(clip.trim())
                                } else {
                                    Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isProcessing && (includeTunnel || includeDeploy || includeExceptions || includeSettings),
                            modifier = Modifier
                                .weight(1f)
                                .height(CsqttSizes.ControlHeight),
                            shape = CsqttShapes.Pill,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Из буфера",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }

                        OutlinedButton(
                            onClick = { filePickerLauncher.launch("application/json") },
                            enabled = !isProcessing && (includeTunnel || includeDeploy || includeExceptions || includeSettings),
                            modifier = Modifier
                                .weight(1f)
                                .height(CsqttSizes.ControlHeight),
                            shape = CsqttShapes.Pill,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Из файла",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigOptionRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary
            )
        )
    }
}

private fun saveJsonToDownloads(context: Context, json: String): String? {
    val filename = "csqtt-config-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                "Downloads/$filename"
            } else {
                null
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val file = File(downloadsDir, filename)
            file.writeText(json, Charsets.UTF_8)
            file.absolutePath
        }
    } catch (e: Exception) {
        null
    }
}
