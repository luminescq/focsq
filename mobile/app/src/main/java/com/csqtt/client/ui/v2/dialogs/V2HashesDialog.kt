@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csqtt.client.ui.v2.dialogs

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.VkHashValidationStatus
import com.csqtt.client.ui.legacy.utils.stripVkUrlStatic
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2SquareIconButton
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch

@Composable
fun V2HashesDialog(
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
    onDismiss: () -> Unit,
) {
    val colors = V2Theme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var h1 by rememberSaveable { mutableStateOf(hash1) }
    var h2 by rememberSaveable { mutableStateOf(hash2) }
    var h3 by rememberSaveable { mutableStateOf(hash3) }
    var h4 by rememberSaveable { mutableStateOf(hash4) }
    var h5 by rememberSaveable { mutableStateOf(hash5) }
    var h6 by rememberSaveable { mutableStateOf(hash6) }
    var results by remember(validationResults) { mutableStateOf(validationResults) }
    var isChecking by remember { mutableStateOf(false) }
    var unavailableCount by remember { mutableIntStateOf(0) }

    val hashes = listOf(h1, h2, h3, h4, h5, h6)

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

    val activeHashes = hashes.map(String::trim).filter { it.length >= 16 }.distinct()

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
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(V2Shapes.Pill)
                    .background(colors.textMuted.copy(alpha = 0.4f)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        tint = colors.contrast,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = "VK Хеши",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = V2BodyFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                        color = colors.textPrimary,
                    )
                }
                V2CircleIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                )
            }

            Text(
                text = "Больше хешей — выше лимит потоков и скорость",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    fontSize = 13.sp,
                ),
                color = colors.textSecondary,
            )

            // Scrollable Hash Inputs
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                hashes.forEachIndexed { idx, value ->
                    val label = if (idx == 0) "VK Хеш 1 (основной) *" else "VK Хеш ${idx + 1}"
                    val isShort = value.isNotBlank() && value.length < 16
                    val status = results[value]
                    val isValid = status == VkHashValidationStatus.Valid
                    val isInvalid = status == VkHashValidationStatus.Invalid

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = V2BodyFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                ),
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            if (isValid) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Активен",
                                        tint = colors.success,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "Активен",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = V2BodyFamily,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = colors.success,
                                    )
                                }
                            } else if (isInvalid) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = "Невалиден",
                                        tint = colors.error,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "Недействителен",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = V2BodyFamily,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = colors.error,
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            V2TextField(
                                value = value,
                                onValueChange = { updateHash(idx, it) },
                                placeholder = "Вставьте ссылку или хеш",
                                isError = isShort || isInvalid,
                                enabled = !isChecking,
                                modifier = Modifier.weight(1f),
                            )

                            V2SquareIconButton(
                                icon = Icons.Outlined.ContentPaste,
                                contentDescription = "Вставить",
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
                                size = 54.dp,
                                iconSize = 20.dp,
                                shape = V2Shapes.Field,
                                backgroundColor = colors.field,
                                contentColor = colors.accent,
                            )

                            if (value.isNotEmpty()) {
                                V2SquareIconButton(
                                    icon = Icons.Default.Delete,
                                    contentDescription = "Очистить",
                                    onClick = { updateHash(idx, "") },
                                    size = 54.dp,
                                    iconSize = 20.dp,
                                    shape = V2Shapes.Field,
                                    backgroundColor = colors.field,
                                    contentColor = colors.textMuted,
                                )
                            }
                        }

                        if (isShort) {
                            Text(
                                text = "Хеш ${idx + 1} слишком короткий (минимум 16 символов)",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = V2BodyFamily,
                                    fontSize = 12.sp,
                                ),
                                color = colors.error,
                            )
                        }
                    }
                }

                if (unavailableCount > 0) {
                    Text(
                        text = "Не удалось проверить: $unavailableCount. Поля оставлены без изменений.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = V2BodyFamily,
                            fontSize = 12.sp,
                        ),
                        color = colors.textSecondary,
                    )
                }
            }

            // Bottom Actions
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                V2PillButton(
                    text = if (isChecking) "Проверяем..." else "Проверить актив (${activeHashes.size})",
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
                    style = V2PillStyle.Outline,
                    enabled = !isChecking && activeHashes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    height = 48.dp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    V2PillButton(
                        text = "Отмена",
                        onClick = { animateDismiss() },
                        style = V2PillStyle.Outline,
                        modifier = Modifier.weight(1f),
                        height = 48.dp,
                    )
                    V2PillButton(
                        text = "Сохранить",
                        onClick = {
                            onSave(h1, h2, h3, h4, h5, h6)
                            animateDismiss()
                        },
                        style = V2PillStyle.Contrast,
                        modifier = Modifier.weight(1f),
                        height = 48.dp,
                    )
                }
            }
        }
    }
}
