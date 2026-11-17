package com.csqtt.client.ui.legacy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes

internal enum class CheckChoiceStyle { RECOMMENDED, OTHER, DESTRUCTIVE }

internal data class CheckChoice(val title: String, val detail: String, val style: CheckChoiceStyle)

/**
 * What the server check found and what can be done about it: the found servers, then a sentence for anything the app
 * will not touch, then one button per action (the recommended one filled, a destructive one in the error colour), each
 * with the line that says what it does. «Отмена» stays on the bottom line.
 */
@Composable
internal fun ServerCheckDialog(
    title: String,
    servers: List<Pair<String, String>>,
    emptyText: String,
    notes: List<String>,
    choices: List<CheckChoice>,
    onChoose: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 640.dp),
            shape = CsqttShapes.Dialog,
            color = appSectionCardColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, appSectionCardBorderColor()),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (servers.isEmpty() && emptyText.isNotBlank()) {
                        Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    servers.forEach { (name, detail) ->
                        Column {
                            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            if (detail.isNotBlank()) {
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    notes.forEach { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp, color = MaterialTheme.colorScheme.error)
                    }
                    choices.forEachIndexed { index, choice ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                            val modifier = Modifier.fillMaxWidth().heightIn(min = CsqttSizes.ControlHeight)
                            when (choice.style) {
                                CheckChoiceStyle.RECOMMENDED -> Button(
                                    onClick = { onChoose(index) },
                                    modifier = modifier,
                                    shape = CsqttShapes.Pill,
                                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
                                ) { Text(choice.title, fontWeight = FontWeight.Bold) }
                                CheckChoiceStyle.OTHER -> OutlinedButton(
                                    onClick = { onChoose(index) },
                                    modifier = modifier,
                                    shape = CsqttShapes.Pill,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                                ) { Text(choice.title, fontWeight = FontWeight.Bold) }
                                CheckChoiceStyle.DESTRUCTIVE -> OutlinedButton(
                                    onClick = { onChoose(index) },
                                    modifier = modifier,
                                    shape = CsqttShapes.Pill,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                ) { Text(choice.title, fontWeight = FontWeight.Bold) }
                            }
                            Text(
                                choice.detail,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = CsqttSizes.ControlHeight),
                    shape = CsqttShapes.Pill,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Text(if (choices.isEmpty()) "Закрыть" else "Отмена", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
