package com.csqtt.client.ui.v2.dialogs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.SettingsStore
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class V2ImportExportTab {
    EXPORT,
    IMPORT
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
                resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                "Downloads/$filename"
            } else null
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val file = File(downloadsDir, filename)
            file.writeText(json, Charsets.UTF_8)
            file.absolutePath
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
fun V2ImportExportDialog(
    settingsStore: SettingsStore,
    tunnelRunning: Boolean = false,
    onImported: () -> Unit = {},
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val colors = V2Theme.colors

    var selectedTab by rememberSaveable { mutableStateOf(V2ImportExportTab.EXPORT) }

    var includeTunnel by rememberSaveable { mutableStateOf(true) }
    var includeDeploy by rememberSaveable { mutableStateOf(true) }
    var includeExceptions by rememberSaveable { mutableStateOf(true) }
    var includeSettings by rememberSaveable { mutableStateOf(true) }

    var isProcessing by remember { mutableStateOf(false) }

    val executeImportJson: (String) -> Unit = { jsonText ->
        if (tunnelRunning) {
            Toast.makeText(context, "Сначала остановите туннель", Toast.LENGTH_SHORT).show()
        } else if (jsonText.isBlank()) {
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
                        includeSettings = includeSettings,
                    )
                    if (ok) {
                        Toast.makeText(context, "Конфигурация успешно импортирована ✓", Toast.LENGTH_LONG).show()
                        onImported()
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
        contract = ActivityResultContracts.GetContent(),
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

    V2DialogContainer(
        title = "Данные приложения",
        subtitle = "Импорт и экспорт настроек, правил и профилей",
        icon = Icons.Default.SwapHoriz,
        onDismiss = onDismissRequest,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            // Mode selector tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                V2PillButton(
                    text = "Экспорт",
                    onClick = { selectedTab = V2ImportExportTab.EXPORT },
                    modifier = Modifier.weight(1f),
                    style = if (selectedTab == V2ImportExportTab.EXPORT) V2PillStyle.Contrast else V2PillStyle.Outline,
                    height = 46.dp,
                )
                V2PillButton(
                    text = "Импорт",
                    onClick = { selectedTab = V2ImportExportTab.IMPORT },
                    modifier = Modifier.weight(1f),
                    style = if (selectedTab == V2ImportExportTab.IMPORT) V2PillStyle.Contrast else V2PillStyle.Outline,
                    height = 46.dp,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Разделы конфигурации",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = colors.textPrimary,
            )

            // Sections Checkboxes
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                V2ConfigOptionRow(title = "Настройки туннеля и хешей", checked = includeTunnel, onCheckedChange = { includeTunnel = it })
                V2ConfigOptionRow(title = "Параметры деплоя VPS", checked = includeDeploy, onCheckedChange = { includeDeploy = it })
                V2ConfigOptionRow(title = "Маршрутизация и списки", checked = includeExceptions, onCheckedChange = { includeExceptions = it })
                V2ConfigOptionRow(title = "Общие настройки приложения", checked = includeSettings, onCheckedChange = { includeSettings = it })
            }

            Spacer(modifier = Modifier.height(8.dp))

            val anySelected = includeTunnel || includeDeploy || includeExceptions || includeSettings

            if (selectedTab == V2ImportExportTab.EXPORT) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Копировать в буфер",
                        icon = Icons.Default.ContentCopy,
                        onClick = {
                            scope.launch {
                                isProcessing = true
                                try {
                                    val json = settingsStore.exportConfigJson(
                                        includeTunnel = includeTunnel,
                                        includeDeploy = includeDeploy,
                                        includeExceptions = includeExceptions,
                                        includeSettings = includeSettings,
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
                        style = V2PillStyle.Contrast,
                        enabled = !isProcessing && anySelected,
                        modifier = Modifier.fillMaxWidth(),
                        height = 50.dp,
                    )

                    V2PillButton(
                        text = "Сохранить в Downloads",
                        icon = Icons.Default.Download,
                        onClick = {
                            scope.launch {
                                isProcessing = true
                                try {
                                    val json = settingsStore.exportConfigJson(
                                        includeTunnel = includeTunnel,
                                        includeDeploy = includeDeploy,
                                        includeExceptions = includeExceptions,
                                        includeSettings = includeSettings,
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
                        style = V2PillStyle.Outline,
                        enabled = !isProcessing && anySelected,
                        modifier = Modifier.fillMaxWidth(),
                        height = 50.dp,
                    )
                }
            } else {
                if (tunnelRunning) {
                    Text(
                        text = "Импорт недоступен, пока туннель запущен. Остановите его и повторите.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = V2BodyFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colors.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Из буфера",
                        icon = Icons.Default.ContentPaste,
                        onClick = {
                            val clip = clipboardManager.getText()?.text
                            if (!clip.isNullOrBlank()) {
                                executeImportJson(clip.trim())
                            } else {
                                Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                            }
                        },
                        style = V2PillStyle.Contrast,
                        enabled = !isProcessing && anySelected && !tunnelRunning,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )

                    V2PillButton(
                        text = "Из файла",
                        icon = Icons.Default.FileOpen,
                        onClick = { filePickerLauncher.launch("application/json") },
                        style = V2PillStyle.Outline,
                        enabled = !isProcessing && anySelected && !tunnelRunning,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun V2ConfigOptionRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = V2Theme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(V2Shapes.Field)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = colors.contrast,
                uncheckedColor = colors.outline,
                checkmarkColor = colors.onContrast,
            ),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = V2BodyFamily,
                fontWeight = if (checked) FontWeight.ExtraBold else FontWeight.Normal,
            ),
            color = if (checked) colors.textPrimary else colors.textSecondary,
        )
    }
}
