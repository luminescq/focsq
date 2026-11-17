package com.csqtt.client.ui.legacy.dialogs

import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import com.csqtt.client.ui.legacy.appSectionCardBorderColor
import com.csqtt.client.ui.legacy.appSectionCardColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.VkHashValidationStatus
import com.csqtt.client.ui.legacy.utils.stripVkUrlStatic
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashesDialog(
    hash1: String,
    hash2: String,
    hash3: String,
    hash4: String,
    hash5: String,
    hash6: String,
    validationResults: Map<String, VkHashValidationStatus>,
    onValidationResultsChange: (Map<String, VkHashValidationStatus>) -> Unit,
    onCheck: suspend (List<String>) -> Map<String, VkHashValidationStatus>,
    onAutoSave: ((String, String, String, String, String, String) -> Unit)? = null,
    onSave: (String, String, String, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var h1 by rememberSaveable { mutableStateOf(hash1) }
    var h2 by rememberSaveable { mutableStateOf(hash2) }
    var h3 by rememberSaveable { mutableStateOf(hash3) }
    var h4 by rememberSaveable { mutableStateOf(hash4) }
    var h5 by rememberSaveable { mutableStateOf(hash5) }
    var h6 by rememberSaveable { mutableStateOf(hash6) }
    var results by remember(validationResults) { mutableStateOf(validationResults) }
    var isChecking by remember { mutableStateOf(false) }
    var unavailableCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hashes = listOf(h1, h2, h3, h4, h5, h6)
    val validBorder = if (isSystemInDarkTheme()) Color(0xFF66BB6A) else Color(0xFF2E7D32)

    fun updateHash(index: Int, raw: String) {
        val cleaned = stripVkUrlStatic(raw.filter { it != ' ' && it != '\n' && it != '\r' })
        val previous = hashes[index]
        if (cleaned == previous) return
        if (results.containsKey(previous)) {
            val updatedResults = VkHashValidationCodec.invalidate(results, previous)
            results = updatedResults
            onValidationResultsChange(updatedResults)
        }
        unavailableCount = 0
        when (index) {
            0 -> h1 = cleaned
            1 -> h2 = cleaned
            2 -> h3 = cleaned
            3 -> h4 = cleaned
            4 -> h5 = cleaned
            5 -> h6 = cleaned
        }
        val cur1 = if (index == 0) cleaned else h1
        val cur2 = if (index == 1) cleaned else h2
        val cur3 = if (index == 2) cleaned else h3
        val cur4 = if (index == 3) cleaned else h4
        val cur5 = if (index == 4) cleaned else h5
        val cur6 = if (index == 5) cleaned else h6
        onAutoSave?.invoke(cur1, cur2, cur3, cur4, cur5, cur6)
    }

    Dialog(
        onDismissRequest = onDismiss,
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
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Phone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = "VK Хеши",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Text(
                    text = "Больше хешей — выше лимит потоков и лучшее распределение нагрузки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )

                hashes.forEachIndexed { idx, value ->
                    val label = if (idx == 0) "VK Хеш 1 *" else "VK Хеш ${idx + 1}"
                    val isShort = value.isNotBlank() && value.length < 16
                    val status = results[value]
                    val isInvalid = status == VkHashValidationStatus.Invalid
                    val fieldColors = if (status == VkHashValidationStatus.Valid) {
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = validBorder,
                            unfocusedBorderColor = validBorder,
                        )
                    } else {
                        OutlinedTextFieldDefaults.colors()
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { updateHash(idx, it) },
                        label = { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        placeholder = {
                            Text(
                                "Ссылка звонка или хеш",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        singleLine = true,
                        enabled = !isChecking,
                        isError = isShort || isInvalid,
                        supportingText = if (isShort) {
                            { Text("Хеш ${idx + 1} — короткий (мин. 16)", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        trailingIcon = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 4.dp),
                            ) {
                                IconButton(
                                    onClick = {
                                        val clipText = context.getSystemService(ClipboardManager::class.java)
                                            ?.primaryClip
                                            ?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)
                                            ?.coerceToText(context)
                                            ?.toString()
                                        if (!clipText.isNullOrBlank()) {
                                            updateHash(idx, clipText)
                                        }
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentPaste,
                                        contentDescription = "Вставить из буфера в хеш ${idx + 1}",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { updateHash(idx, "") },
                                    enabled = value.isNotEmpty(),
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Удалить хеш ${idx + 1}",
                                        tint = MaterialTheme.colorScheme.error.copy(
                                            alpha = if (value.isNotEmpty()) 1f else 0.38f,
                                        ),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = CsqttShapes.Control,
                        colors = fieldColors,
                    )
                }

                val activeHashes = hashes.map(String::trim).filter { it.length >= 16 }.distinct()
                OutlinedButton(
                    onClick = {
                        isChecking = true
                        unavailableCount = 0
                        scope.launch {
                            val checked = onCheck(activeHashes)
                            val updatedResults = results + checked
                            results = updatedResults
                            onValidationResultsChange(updatedResults)
                            unavailableCount = activeHashes.size - checked.size
                            isChecking = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
                    enabled = !isChecking && hashes.any { it.isNotBlank() },
                    shape = CsqttShapes.Pill,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(
                        if (isChecking) "Проверяем..." else "Проверить актив",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (unavailableCount > 0) {
                    Text(
                        "Не удалось проверить: $unavailableCount. Поля оставлены без статуса.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp,
                    )
                }

                Button(
                    onClick = {
                        onSave(h1, h2, h3, h4, h5, h6)
                    },
                    modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
                    shape = CsqttShapes.Pill,
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                ) {
                    Text("Сохранить", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
