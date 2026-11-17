@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csqtt.client.ui.v2.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.v2.components.V2Card
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Theme
import com.csqtt.client.ui.v2.design.V2VersionFamily
import kotlinx.coroutines.launch

@Composable
fun V2VersionSheet(
    version: String,
    onDismiss: () -> Unit,
) {
    val colors = V2Theme.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val cleanVersion = version.removePrefix("V ").removePrefix("v").trim()

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
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: "У вас установлена" + Pill with version + Close button on the right
            Box(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "У вас установлена",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = V2DisplayFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        ),
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )

                    Surface(
                        shape = V2Shapes.Pill,
                        color = colors.contrast,
                    ) {
                        Text(
                            text = "v$cleanVersion",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = V2VersionFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            ),
                            color = colors.onContrast,
                        )
                    }
                }

                V2CircleIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }

            // Decorative wavy divider line
            WavyDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                color = colors.textMuted.copy(alpha = 0.5f),
            )

            // "Что нового" card container
            V2Card(
                shape = RoundedCornerShape(22.dp),
                color = colors.field,
                contentPadding = PaddingValues(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Что нового в",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = V2DisplayFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                            ),
                            color = colors.textPrimary,
                        )

                        Surface(
                            shape = V2Shapes.Pill,
                            color = colors.contrast,
                        ) {
                            Text(
                                text = "v$cleanVersion",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = V2VersionFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                ),
                                color = colors.onContrast,
                            )
                        }
                    }

                    val changeItems = listOf(
                        "Ребрендинг в FOCSQ: новое имя, иконка и идентификатор приложения.",
                        "Сервер обновлён до 2.5.3: ядра собраны из исходников, версии синхронизированы.",
                    )

                    changeItems.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                ),
                                color = colors.textPrimary,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = V2BodyFamily,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                ),
                                color = colors.textPrimary,
                            )
                        }

                        if (index < changeItems.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = colors.outline.copy(alpha = 0.35f),
                                thickness = 0.8.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Harmonic wavy divider Canvas component matching the sinusoidal wavy decoration in the changelog modal.
 */
@Composable
fun WavyDivider(
    modifier: Modifier = Modifier,
    waveLength: Dp = 26.dp,
    waveHeight: Dp = 7.dp,
    strokeWidth: Dp = 2.dp,
    color: Color = V2Theme.colors.textMuted.copy(alpha = 0.5f),
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(waveHeight * 2),
    ) {
        val waveLenPx = waveLength.toPx()
        val amp = waveHeight.toPx() / 2f
        val centerY = size.height / 2f
        val path = Path()

        path.moveTo(0f, centerY)
        var x = 0f
        while (x < size.width) {
            // Cubic bezier sine approximation:
            // Top crest:
            path.cubicTo(
                x + waveLenPx * 0.16f, centerY - amp * 1.05f,
                x + waveLenPx * 0.34f, centerY - amp * 1.05f,
                x + waveLenPx * 0.5f, centerY,
            )
            // Bottom trough:
            path.cubicTo(
                x + waveLenPx * 0.66f, centerY + amp * 1.05f,
                x + waveLenPx * 0.84f, centerY + amp * 1.05f,
                x + waveLenPx, centerY,
            )
            x += waveLenPx
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
