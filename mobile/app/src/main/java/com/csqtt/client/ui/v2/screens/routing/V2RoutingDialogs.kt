@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csqtt.client.ui.v2.screens.routing

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.csqtt.client.ui.v2.design.V2BodyFamily
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.R
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.legacy.AppItem
import com.csqtt.client.ui.v2.components.V2Card
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2Switch
import com.csqtt.client.ui.v2.components.V2SwitchSetting
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val PackageSetSaver = listSaver<Set<String>, String>(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)

@Composable
internal fun V2AppsRoutingDialog(
    appsList: List<AppItem>,
    isAppsLoading: Boolean,
    isWhitelist: Boolean,
    blacklist: Set<String>,
    whitelist: Set<String>,
    onSave: (black: Set<String>, white: Set<String>, isWhitelist: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = V2Theme.colors
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    var modeIsWhitelist by rememberSaveable { mutableStateOf(isWhitelist) }
    var blackSelection by rememberSaveable(stateSaver = PackageSetSaver) {
        mutableStateOf(blacklist)
    }
    var whiteSelection by rememberSaveable(stateSaver = PackageSetSaver) {
        mutableStateOf(whitelist)
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }

    val activeSelection = if (modeIsWhitelist) whiteSelection else blackSelection

    val filteredApps = remember(appsList, searchQuery, activeSelection, showSystemApps) {
        val base = if (searchQuery.isBlank()) {
            appsList
        } else {
            appsList.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
            .filter { showSystemApps || !it.isSystem }
        base.sortedWith(
            compareByDescending<AppItem> { it.packageName in activeSelection }
                .thenBy { it.isSystem }
                .thenBy { it.name.lowercase() },
        )
    }
    val allVisibleSelected = filteredApps.isNotEmpty() &&
        filteredApps.all { it.packageName in activeSelection }

    val ruPresetCount = remember(blackSelection) {
        RuAppPresets.packages.count { it in blackSelection }
    }
    val ruPresetEnabled = ruPresetCount >= RuAppPresets.packages.size

    fun setRuPreset(enabled: Boolean) {
        if (enabled) {
            blackSelection = blackSelection + RuAppPresets.packages
            modeIsWhitelist = false
        } else {
            blackSelection = blackSelection - RuAppPresets.packages
        }
    }

    fun toggleAllVisible() {
        val visible = filteredApps.mapTo(mutableSetOf()) { it.packageName }
        val newSelection = if (allVisibleSelected) {
            activeSelection - visible
        } else {
            activeSelection + visible
        }
        if (modeIsWhitelist) {
            whiteSelection = newSelection
        } else {
            blackSelection = newSelection
        }
    }

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun saveAndClose() {
        onSave(blackSelection, whiteSelection, modeIsWhitelist)
    }

    fun animateSave() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            saveAndClose()
        }
    }

    fun animateDismiss() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            onDismiss()
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = V2Spacing.Lg)
                .padding(bottom = V2Spacing.Lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Маршрутизация приложений",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                V2CircleIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                )
            }

            Spacer(modifier = Modifier.height(V2Spacing.Md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                V2PillButton(
                    text = "Чёрный список",
                    onClick = { modeIsWhitelist = false },
                    modifier = Modifier.weight(1f),
                    style = if (!modeIsWhitelist) V2PillStyle.Contrast else V2PillStyle.Outline,
                    height = 40.dp,
                    horizontalPadding = 8.dp,
                )
                V2PillButton(
                    text = "Белый список",
                    onClick = { modeIsWhitelist = true },
                    modifier = Modifier.weight(1f),
                    style = if (modeIsWhitelist) V2PillStyle.Contrast else V2PillStyle.Outline,
                    height = 40.dp,
                    horizontalPadding = 8.dp,
                )
            }

            Spacer(modifier = Modifier.height(V2Spacing.Sm))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        "Поиск приложения",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.textMuted)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clickable(
                                    role = Role.Button,
                                    onClick = { searchQuery = "" },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Clear, contentDescription = "Очистить", tint = colors.textMuted)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                shape = V2Shapes.Field,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedContainerColor = colors.field,
                    unfocusedContainerColor = colors.field,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = colors.accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(V2Spacing.Sm))

            V2Card(
                onClick = { setRuPreset(!ruPresetEnabled) },
                color = colors.field,
                contentPadding = PaddingValues(
                    horizontal = V2Spacing.Md,
                    vertical = V2Spacing.Sm,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Исключить RU приложения",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (ruPresetEnabled) {
                                "Все ${RuAppPresets.packages.size} приложений в исключениях"
                            } else {
                                "Обход туннеля для Яндекса, Сбера, Ozon и других"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    V2Switch(
                        checked = ruPresetEnabled,
                        onCheckedChange = { setRuPreset(it) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(V2Spacing.Xs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                V2PillButton(
                    text = if (showSystemApps) "Системные" else "Без системных",
                    onClick = { showSystemApps = !showSystemApps },
                    style = if (showSystemApps) V2PillStyle.Contrast else V2PillStyle.Outline,
                    height = 32.dp,
                    horizontalPadding = 12.dp,
                )
                V2PillButton(
                    text = if (allVisibleSelected) "Снять все" else "Выбрать все",
                    onClick = { toggleAllVisible() },
                    style = V2PillStyle.Outline,
                    height = 32.dp,
                    horizontalPadding = 12.dp,
                    enabled = filteredApps.isNotEmpty(),
                )
            }

            Spacer(modifier = Modifier.height(V2Spacing.Xs))

            Text(
                text = if (modeIsWhitelist) {
                    "Выбрано: ${activeSelection.size} (в туннеле)"
                } else {
                    "Выбрано: ${activeSelection.size} (исключено)"
                },
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            when {
                isAppsLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }

                filteredApps.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Приложения не найдены",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = V2Spacing.Sm),
                        verticalArrangement = Arrangement.spacedBy(V2Spacing.Xs),
                    ) {
                        items(
                            items = filteredApps,
                            key = { it.packageName },
                            contentType = { "application" },
                        ) { app ->
                            val isSelected = app.packageName in activeSelection
                            V2AppRow(
                                app = app,
                                isSelected = isSelected,
                                onClick = {
                                    val newSelection = if (isSelected) {
                                        activeSelection - app.packageName
                                    } else {
                                        activeSelection + app.packageName
                                    }
                                    if (modeIsWhitelist) {
                                        whiteSelection = newSelection
                                    } else {
                                        blackSelection = newSelection
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(V2Spacing.Sm))

            V2PillButton(
                text = "Готово",
                onClick = { animateSave() },
                modifier = Modifier.fillMaxWidth(),
                style = V2PillStyle.Contrast,
                height = 48.dp,
            )
        }
    }
}

@Composable
internal fun V2DomainsRoutingDialog(
    isWhitelist: Boolean,
    blackStr: String,
    whiteStr: String,
    templateDomainsEnabled: Boolean,
    templateDomains: List<String>,
    onSave: (blackFormatted: String, whiteFormatted: String, isWhitelist: Boolean, templateEnabled: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = V2Theme.colors
    val templateSet = remember(templateDomains) { templateDomains.toSet() }
    var modeIsWhitelist by rememberSaveable { mutableStateOf(isWhitelist) }
    var templateEnabled by rememberSaveable { mutableStateOf(templateDomainsEnabled) }
    var blackText by rememberSaveable {
        mutableStateOf(
            parseDomainsFromText(blackStr)
                .filter { !templateDomainsEnabled || it !in templateSet }
                .joinToString("\n"),
        )
    }
    var whiteText by rememberSaveable {
        mutableStateOf(parseDomainsFromText(whiteStr).joinToString("\n"))
    }

    val currentText = if (modeIsWhitelist) whiteText else blackText
    val parsedRules = remember(currentText) { parseDomainsFromText(currentText) }
    val parsedTotal = remember(currentText, parsedRules) {
        if (parsedRules.size >= MAX_ROUTING_RULES) {
            parseDomainsFromText(currentText, Int.MAX_VALUE).size
        } else {
            parsedRules.size
        }
    }
    val parsedCount = parsedRules.size

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun saveAndClose() {
        val formattedBlack = parseDomainsFromText(blackText).joinToString(",")
        val formattedWhite = parseDomainsFromText(whiteText).joinToString(",")
        onSave(formattedBlack, formattedWhite, modeIsWhitelist, templateEnabled)
    }

    fun animateSave() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            saveAndClose()
        }
    }

    fun animateDismiss() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            onDismiss()
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = V2Spacing.Lg)
                .padding(bottom = V2Spacing.Lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Маршрутизация по доменам",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                V2CircleIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                )
            }

            Spacer(modifier = Modifier.height(V2Spacing.Md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                V2PillButton(
                    text = "Чёрный список",
                    onClick = { modeIsWhitelist = false },
                    modifier = Modifier.weight(1f),
                    style = if (!modeIsWhitelist) V2PillStyle.Contrast else V2PillStyle.Outline,
                    height = 40.dp,
                    horizontalPadding = 8.dp,
                )
                V2PillButton(
                    text = "Белый список",
                    onClick = { modeIsWhitelist = true },
                    modifier = Modifier.weight(1f),
                    style = if (modeIsWhitelist) V2PillStyle.Contrast else V2PillStyle.Outline,
                    height = 40.dp,
                    horizontalPadding = 8.dp,
                )
            }

            Spacer(modifier = Modifier.height(V2Spacing.Sm))

            if (!modeIsWhitelist) {
                V2SwitchSetting(
                    title = "Готовый список RU доменов",
                    checked = templateEnabled,
                    onCheckedChange = { enabled -> templateEnabled = enabled },
                )
                if (templateEnabled && templateDomains.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(V2Spacing.Xs))
                    Surface(
                        shape = V2Shapes.Pill,
                        color = colors.surfaceElevated,
                        contentColor = colors.textSecondary,
                    ) {
                        Text(
                            text = "RU-шаблон: ${templateDomains.size} доменов",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(V2Spacing.Sm))
            }

            OutlinedTextField(
                value = currentText,
                onValueChange = { newText ->
                    if (modeIsWhitelist) whiteText = newText else blackText = newText
                },
                placeholder = {
                    Text(
                        "Введите домены списком, через запятую или пробел:\nyandex.ru, mail.ru\nkinopoisk.ru",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                    )
                },
                shape = V2Shapes.Field,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedContainerColor = colors.field,
                    unfocusedContainerColor = colors.field,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = colors.accent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            Spacer(modifier = Modifier.height(V2Spacing.Sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (parsedTotal > MAX_ROUTING_RULES) {
                        "Распознано: $parsedCount из $parsedTotal (лимит $MAX_ROUTING_RULES)"
                    } else {
                        "Распознано: $parsedCount"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (currentText.isNotBlank() || (!modeIsWhitelist && templateEnabled)) {
                        V2PillButton(
                            text = "Очистить",
                            onClick = {
                                if (modeIsWhitelist) {
                                    whiteText = ""
                                } else {
                                    blackText = ""
                                    templateEnabled = false
                                }
                            },
                            style = V2PillStyle.Outline,
                            height = 36.dp,
                            horizontalPadding = 14.dp,
                        )
                    }
                    V2PillButton(
                        text = "Готово",
                        onClick = { animateSave() },
                        style = V2PillStyle.Contrast,
                        height = 36.dp,
                        horizontalPadding = 14.dp,
                    )
                }
            }
        }
    }
}

@Composable
internal fun V2IpsRoutingDialog(
    isWhitelist: Boolean,
    blackStr: String,
    whiteStr: String,
    onSave: (blackFormatted: String, whiteFormatted: String, isWhitelist: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = V2Theme.colors
    var modeIsWhitelist by rememberSaveable { mutableStateOf(isWhitelist) }
    var blackText by rememberSaveable { mutableStateOf(parseIpsFromText(blackStr).joinToString("\n")) }
    var whiteText by rememberSaveable { mutableStateOf(parseIpsFromText(whiteStr).joinToString("\n")) }

    val currentText = if (modeIsWhitelist) whiteText else blackText
    val parsedRules = remember(currentText) { parseIpsFromText(currentText) }
    val parsedTotal = remember(currentText, parsedRules) {
        if (parsedRules.size >= MAX_ROUTING_RULES) {
            parseIpsFromText(currentText, Int.MAX_VALUE).size
        } else {
            parsedRules.size
        }
    }
    val parsedCount = parsedRules.size

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun saveAndClose() {
        val formattedBlack = parseIpsFromText(blackText).joinToString(",")
        val formattedWhite = parseIpsFromText(whiteText).joinToString(",")
        onSave(formattedBlack, formattedWhite, modeIsWhitelist)
    }

    fun animateSave() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            saveAndClose()
        }
    }

    fun animateDismiss() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            onDismiss()
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = V2Spacing.Lg)
                .padding(bottom = V2Spacing.Lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Маршрутизация по IP",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                V2CircleIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                )
            }

            Spacer(modifier = Modifier.height(V2Spacing.Md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                V2PillButton(
                    text = "Чёрный список",
                    onClick = { modeIsWhitelist = false },
                    modifier = Modifier.weight(1f),
                    style = if (!modeIsWhitelist) V2PillStyle.Contrast else V2PillStyle.Outline,
                    height = 40.dp,
                    horizontalPadding = 8.dp,
                )
                V2PillButton(
                    text = "Белый список",
                    onClick = { modeIsWhitelist = true },
                    modifier = Modifier.weight(1f),
                    style = if (modeIsWhitelist) V2PillStyle.Contrast else V2PillStyle.Outline,
                    height = 40.dp,
                    horizontalPadding = 8.dp,
                )
            }

            Spacer(modifier = Modifier.height(V2Spacing.Sm))

            OutlinedTextField(
                value = currentText,
                onValueChange = { newText ->
                    if (modeIsWhitelist) whiteText = newText else blackText = newText
                },
                placeholder = {
                    Text(
                        "1.2.3.4/32\n1.2.3.4\n1.2.3.4-1.2.3.255",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                    )
                },
                shape = V2Shapes.Field,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedContainerColor = colors.field,
                    unfocusedContainerColor = colors.field,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = colors.accent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            Spacer(modifier = Modifier.height(V2Spacing.Sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (parsedTotal > MAX_ROUTING_RULES) {
                        "Распознано: $parsedCount из $parsedTotal (лимит $MAX_ROUTING_RULES)"
                    } else {
                        "Распознано: $parsedCount"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (currentText.isNotBlank()) {
                        V2PillButton(
                            text = "Очистить",
                            onClick = {
                                if (modeIsWhitelist) whiteText = "" else blackText = ""
                            },
                            style = V2PillStyle.Outline,
                            height = 36.dp,
                            horizontalPadding = 14.dp,
                        )
                    }
                    V2PillButton(
                        text = "Готово",
                        onClick = { animateSave() },
                        style = V2PillStyle.Contrast,
                        height = 36.dp,
                        horizontalPadding = 14.dp,
                    )
                }
            }
        }
    }
}

@Composable
internal fun V2RulesTransferDialog(
    settingsStore: SettingsStore,
    isWhitelist: Boolean,
    blacklistSelection: Set<String>,
    whitelistSelection: Set<String>,
    domainIsWhitelist: Boolean,
    templateDomainsEnabled: Boolean,
    excludeRuZones: Boolean,
    templateDomains: List<String>,
    directDomainsBlackStr: String,
    directDomainsWhiteStr: String,
    ipIsWhitelist: Boolean,
    directIpsBlackStr: String,
    directIpsWhiteStr: String,
    onClose: () -> Unit,
) {
    val colors = V2Theme.colors
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var transferApps by rememberSaveable { mutableStateOf(true) }
    var transferDomains by rememberSaveable { mutableStateOf(true) }
    var transferIps by rememberSaveable { mutableStateOf(true) }
    var isImporting by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun animateClose() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            onClose()
        }
    }

    fun exportRules() {
        if (!transferApps && !transferDomains && !transferIps) {
            context.showRaisedToast("Выберите хотя бы один пункт", Toast.LENGTH_SHORT)
            return
        }
        val root = JSONObject()
        root.put("version", 2)
        if (transferApps) {
            val appsObj = JSONObject()
            appsObj.put("is_whitelist", isWhitelist)
            appsObj.put("active_mode", if (isWhitelist) "whitelist" else "blacklist")
            val blackArr = JSONArray()
            blacklistSelection.sorted().forEach { blackArr.put(it) }
            appsObj.put("blacklist", blackArr)
            val whiteArr = JSONArray()
            whitelistSelection.sorted().forEach { whiteArr.put(it) }
            appsObj.put("whitelist", whiteArr)
            root.put("apps", appsObj)
        }
        if (transferDomains) {
            val domainsObj = JSONObject()
            domainsObj.put("is_whitelist", domainIsWhitelist)
            domainsObj.put("active_mode", if (domainIsWhitelist) "whitelist" else "blacklist")
            domainsObj.put("template_domains", templateDomainsEnabled)
            domainsObj.put("exclude_ru_zones", excludeRuZones)
            val blackArr = JSONArray()
            val templateSet = templateDomains.toSet()
            val blackList = parseDomainsFromText(directDomainsBlackStr)
            val customOnly = if (templateDomainsEnabled) {
                blackList.filter { !templateSet.contains(it) }
            } else {
                blackList
            }
            customOnly.forEach { blackArr.put(it) }
            domainsObj.put("blacklist", blackArr)
            val whiteArr = JSONArray()
            parseDomainsFromText(directDomainsWhiteStr).forEach { whiteArr.put(it) }
            domainsObj.put("whitelist", whiteArr)
            domainsObj.put("direct_domains", if (domainIsWhitelist) whiteArr else blackArr)
            root.put("domains", domainsObj)
        }
        if (transferIps) {
            val ipsObj = JSONObject()
            ipsObj.put("is_whitelist", ipIsWhitelist)
            ipsObj.put("active_mode", if (ipIsWhitelist) "whitelist" else "blacklist")
            val blackArr = JSONArray()
            parseIpsFromText(directIpsBlackStr).forEach { blackArr.put(it) }
            ipsObj.put("blacklist", blackArr)
            val whiteArr = JSONArray()
            parseIpsFromText(directIpsWhiteStr).forEach { whiteArr.put(it) }
            ipsObj.put("whitelist", whiteArr)
            ipsObj.put("direct_ips", if (ipIsWhitelist) whiteArr else blackArr)
            root.put("ips", ipsObj)
        }
        clipboardManager.setText(AnnotatedString(root.toString(2)))
        context.showRaisedToast("Правила экспортированы в буфер обмена", Toast.LENGTH_SHORT)
    }

    fun importRules() {
        if (isImporting) return
        if (!transferApps && !transferDomains && !transferIps) {
            context.showRaisedToast("Выберите хотя бы один пункт", Toast.LENGTH_SHORT)
            return
        }
        val clipText = clipboardManager.getText()?.text.orEmpty().trim()
        if (clipText.isBlank()) {
            context.showRaisedToast("Буфер обмена пуст", Toast.LENGTH_SHORT)
            return
        }

        val actions = mutableListOf<suspend () -> Unit>()
        var needsAppsReload = false
        var needsRoutingPush = false
        var importedAny = false
        var truncated = false

        fun capped(rules: List<String>): List<String> {
            if (rules.size > MAX_ROUTING_RULES) truncated = true
            return rules.take(MAX_ROUTING_RULES)
        }

        val existingBlackDomains = parseDomainsFromText(directDomainsBlackStr)
        val existingWhiteDomains = parseDomainsFromText(directDomainsWhiteStr)
        val existingBlackIps = parseIpsFromText(directIpsBlackStr)
        val existingWhiteIps = parseIpsFromText(directIpsWhiteStr)
        val rootJson = runCatching { JSONObject(clipText) }.getOrNull()
        if (rootJson != null) {
            if (transferApps && rootJson.has("apps")) {
                val appsObj = rootJson.getJSONObject("apps")
                val isWhite = if (appsObj.has("active_mode")) {
                    appsObj.optString("active_mode") == "whitelist"
                } else {
                    appsObj.optBoolean("is_whitelist", isWhitelist)
                }
                val newBlack = mutableSetOf<String>()
                val blackArr = appsObj.optJSONArray("blacklist")
                if (blackArr != null) {
                    for (i in 0 until blackArr.length()) {
                        val pkg = blackArr.optString(i)
                        if (isValidPackage(pkg)) newBlack.add(pkg)
                    }
                }
                val newWhite = mutableSetOf<String>()
                val whiteArr = appsObj.optJSONArray("whitelist")
                if (whiteArr != null) {
                    for (i in 0 until whiteArr.length()) {
                        val pkg = whiteArr.optString(i)
                        if (isValidPackage(pkg)) newWhite.add(pkg)
                    }
                }
                actions += {
                    settingsStore.saveAppRoutingLists(
                        blacklist = newBlack.sorted().joinToString(","),
                        whitelist = newWhite.sorted().joinToString(","),
                        isWhitelist = isWhite,
                    )
                }
                needsAppsReload = true
                importedAny = true
            }
            if (transferDomains && rootJson.has("domains")) {
                val domainsObj = rootJson.getJSONObject("domains")
                val isWhite = if (domainsObj.has("active_mode")) {
                    domainsObj.optString("active_mode") == "whitelist"
                } else {
                    domainsObj.optBoolean("is_whitelist", domainIsWhitelist)
                }
                val isTemplate = domainsObj.optBoolean("template_domains", false)
                val impExcludeRu = domainsObj.optBoolean("exclude_ru_zones", excludeRuZones)
                val impBlack = mutableListOf<String>()
                val blackArr = domainsObj.optJSONArray("blacklist")
                if (blackArr != null) {
                    for (i in 0 until blackArr.length()) {
                        val d = cleanDomain(blackArr.optString(i))
                        if (isValidDomain(d)) impBlack.add(d)
                    }
                }
                val impWhite = mutableListOf<String>()
                val whiteArr = domainsObj.optJSONArray("whitelist")
                if (whiteArr != null) {
                    for (i in 0 until whiteArr.length()) {
                        val d = cleanDomain(whiteArr.optString(i))
                        if (isValidDomain(d)) impWhite.add(d)
                    }
                }
                val flatArr = domainsObj.optJSONArray("direct_domains")
                if (flatArr != null && impBlack.isEmpty() && impWhite.isEmpty()) {
                    for (i in 0 until flatArr.length()) {
                        val d = cleanDomain(flatArr.optString(i))
                        if (isValidDomain(d)) {
                            if (isWhite) impWhite.add(d) else impBlack.add(d)
                        }
                    }
                }
                val importedBlack = capped(impBlack.distinct())
                val importedWhite = capped(impWhite.distinct())
                val finalBlack = when {
                    blackArr != null && (importedBlack.isNotEmpty() || !isWhite) -> importedBlack
                    flatArr != null && !isWhite -> importedBlack
                    else -> existingBlackDomains
                }
                val finalWhite = when {
                    whiteArr != null && (importedWhite.isNotEmpty() || isWhite) -> importedWhite
                    flatArr != null && isWhite -> importedWhite
                    else -> existingWhiteDomains
                }
                val formattedBlack = finalBlack.joinToString(",")
                val formattedWhite = finalWhite.joinToString(",")
                val hasRules = if (isWhite) {
                    formattedWhite.isNotBlank()
                } else {
                    formattedBlack.isNotBlank() || isTemplate || impExcludeRu
                }
                actions += {
                    settingsStore.saveDomainRouting(
                        enabled = hasRules,
                        isWhitelist = isWhite,
                        templateEnabled = isTemplate,
                        excludeRuZones = impExcludeRu,
                        black = formattedBlack,
                        white = formattedWhite,
                    )
                }
                needsRoutingPush = true
                importedAny = true
            }
            if (transferIps && rootJson.has("ips")) {
                val ipsObj = rootJson.getJSONObject("ips")
                val isWhite = if (ipsObj.has("active_mode")) {
                    ipsObj.optString("active_mode") == "whitelist"
                } else {
                    ipsObj.optBoolean("is_whitelist", ipIsWhitelist)
                }
                val impBlack = mutableListOf<String>()
                val blackArr = ipsObj.optJSONArray("blacklist")
                if (blackArr != null) {
                    for (i in 0 until blackArr.length()) {
                        val r = cleanIpRule(blackArr.optString(i))
                        if (isValidIpRule(r)) impBlack.add(r)
                    }
                }
                val impWhite = mutableListOf<String>()
                val whiteArr = ipsObj.optJSONArray("whitelist")
                if (whiteArr != null) {
                    for (i in 0 until whiteArr.length()) {
                        val r = cleanIpRule(whiteArr.optString(i))
                        if (isValidIpRule(r)) impWhite.add(r)
                    }
                }
                val flatArr = ipsObj.optJSONArray("direct_ips")
                if (flatArr != null && impBlack.isEmpty() && impWhite.isEmpty()) {
                    for (i in 0 until flatArr.length()) {
                        val r = cleanIpRule(flatArr.optString(i))
                        if (isValidIpRule(r)) {
                            if (isWhite) impWhite.add(r) else impBlack.add(r)
                        }
                    }
                }
                val importedBlack = capped(impBlack.distinct())
                val importedWhite = capped(impWhite.distinct())
                val finalBlack = when {
                    blackArr != null && (importedBlack.isNotEmpty() || !isWhite) -> importedBlack
                    flatArr != null && !isWhite -> importedBlack
                    else -> existingBlackIps
                }
                val finalWhite = when {
                    whiteArr != null && (importedWhite.isNotEmpty() || isWhite) -> importedWhite
                    flatArr != null && isWhite -> importedWhite
                    else -> existingWhiteIps
                }
                val formattedBlack = finalBlack.joinToString(",")
                val formattedWhite = finalWhite.joinToString(",")
                val activeList = if (isWhite) formattedWhite else formattedBlack
                actions += {
                    settingsStore.saveIpRouting(
                        enabled = activeList.isNotBlank(),
                        isWhitelist = isWhite,
                        black = formattedBlack,
                        white = formattedWhite,
                    )
                }
                needsRoutingPush = true
                importedAny = true
            }
        } else {
            if (transferApps) {
                val parsed = parseAppExportFromText(clipText)
                if (parsed != null) {
                    val (impBlack, impWhite) = parsed
                    if (impBlack.isNotEmpty() || impWhite.isNotEmpty()) {
                        val mergedBlack = blacklistSelection + impBlack
                        val mergedWhite = whitelistSelection + impWhite
                        actions += {
                            settingsStore.saveAppRoutingLists(
                                blacklist = mergedBlack.sorted().joinToString(","),
                                whitelist = mergedWhite.sorted().joinToString(","),
                                isWhitelist = isWhitelist,
                            )
                        }
                        needsAppsReload = true
                        importedAny = true
                    }
                }
            }
            if (transferDomains) {
                val importedDomains = parseDomainsFromText(clipText)
                if (importedDomains.isNotEmpty()) {
                    val mergedActive = capped((if (domainIsWhitelist) {
                        existingWhiteDomains
                    } else {
                        existingBlackDomains
                    } + importedDomains).distinct())
                    val newBlack = if (domainIsWhitelist) existingBlackDomains else mergedActive
                    val newWhite = if (domainIsWhitelist) mergedActive else existingWhiteDomains
                    val hasRules = if (domainIsWhitelist) {
                        newWhite.isNotEmpty()
                    } else {
                        newBlack.isNotEmpty() || templateDomainsEnabled || excludeRuZones
                    }
                    actions += {
                        settingsStore.saveDomainRouting(
                            enabled = hasRules,
                            isWhitelist = domainIsWhitelist,
                            templateEnabled = templateDomainsEnabled,
                            excludeRuZones = excludeRuZones,
                            black = newBlack.joinToString(","),
                            white = newWhite.joinToString(","),
                        )
                    }
                    needsRoutingPush = true
                    importedAny = true
                }
            }
            if (transferIps) {
                val importedIps = parseIpsFromText(clipText)
                if (importedIps.isNotEmpty()) {
                    val mergedActive = capped((if (ipIsWhitelist) {
                        existingWhiteIps
                    } else {
                        existingBlackIps
                    } + importedIps).distinct())
                    val newBlack = if (ipIsWhitelist) existingBlackIps else mergedActive
                    val newWhite = if (ipIsWhitelist) mergedActive else existingWhiteIps
                    actions += {
                        settingsStore.saveIpRouting(
                            enabled = mergedActive.isNotEmpty(),
                            isWhitelist = ipIsWhitelist,
                            black = newBlack.joinToString(","),
                            white = newWhite.joinToString(","),
                        )
                    }
                    needsRoutingPush = true
                    importedAny = true
                }
            }
        }
        if (!importedAny) {
            context.showRaisedToast("В буфере обмена нет подходящих данных", Toast.LENGTH_SHORT)
            return
        }
        isImporting = true
        RoutingPersistence.scope.launch {
            try {
                actions.forEach { it() }
                if (needsAppsReload) TunnelManager.reloadVpn()
                if (needsRoutingPush) TunnelManager.pushLocalRouting(context)
                context.showRaisedToast(
                    if (truncated) {
                        "Правила импортированы (лимит $MAX_ROUTING_RULES на список)"
                    } else {
                        "Правила успешно импортированы"
                    },
                    Toast.LENGTH_LONG,
                )
                runCatching { sheetState.hide() }
                onClose()
            } catch (e: Exception) {
                context.showRaisedToast(
                    "Не удалось импортировать правила: ${e.message ?: e.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                )
            } finally {
                isImporting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isImporting) onClose() },
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V2Spacing.Lg)
                .padding(bottom = V2Spacing.Xl),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Импорт и экспорт правил",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                V2CircleIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Закрыть",
                    onClick = { if (!isImporting) animateClose() },
                )
            }

            Spacer(modifier = Modifier.height(V2Spacing.Md))

            V2TransferCheckRow(
                title = "Приложения",
                checked = transferApps,
                onCheckedChange = { transferApps = it },
            )
            Spacer(modifier = Modifier.height(V2Spacing.Sm))
            V2TransferCheckRow(
                title = "Домены",
                checked = transferDomains,
                onCheckedChange = { transferDomains = it },
            )
            Spacer(modifier = Modifier.height(V2Spacing.Sm))
            V2TransferCheckRow(
                title = "IP адреса",
                checked = transferIps,
                onCheckedChange = { transferIps = it },
            )

            Spacer(modifier = Modifier.height(V2Spacing.Md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                V2PillButton(
                    text = "Экспорт",
                    onClick = { exportRules() },
                    modifier = Modifier.weight(1f),
                    style = V2PillStyle.Contrast,
                    enabled = !isImporting,
                )
                V2PillButton(
                    text = if (isImporting) "Импорт…" else "Импорт",
                    onClick = { importRules() },
                    modifier = Modifier.weight(1f),
                    style = V2PillStyle.Outline,
                    enabled = !isImporting,
                )
            }
        }
    }
}

@Composable
private fun V2TransferCheckRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = V2Theme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = V2Shapes.Field,
        color = colors.field,
        border = null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            V2CircleCheckbox(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
fun V2CircleCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    checkedColor: Color = V2Theme.colors.contrast,
    checkmarkColor: Color = V2Theme.colors.onContrast,
    uncheckedColor: Color = V2Theme.colors.surfaceElevated,
    size: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val bgAnimated by animateColorAsState(
        targetValue = if (checked) checkedColor else uncheckedColor,
        label = "circle_cb_bg",
    )

    val clickModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
        )
    } else Modifier

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bgAnimated, CircleShape)
            .then(clickModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = checkmarkColor,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun V2AppRow(
    app: AppItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    val backgroundColor = if (isSelected) colors.surfaceElevated else colors.bottomSurface

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(
                value = isSelected,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            ),
        shape = V2Shapes.Row,
        color = backgroundColor,
        contentColor = colors.textPrimary,
        border = null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (app.icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(V2Shapes.Field),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(colors.field, V2Shapes.Field),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = V2BodyFamily,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            V2CircleCheckbox(
                checked = isSelected,
                onCheckedChange = null,
            )
        }
    }
}
