package com.csqtt.client.ui.legacy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.csqtt.client.CsqttConstants
import kotlin.math.roundToInt

import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color

@Composable
internal fun TunnelWorkersControl(
    value: Float,
    maximum: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onInfo: () -> Unit,
    fixedWorkers: Int? = null,
) {
    val isFixed = fixedWorkers != null && fixedWorkers in 3..100
    val minimum = 5f
    val normalizedMaximum = roundToGroup(maximum, maximum)
    val normalizedValue = roundToGroup(value.coerceIn(minimum, normalizedMaximum), normalizedMaximum)
    var localValue by remember(normalizedValue, normalizedMaximum) {
        mutableStateOf(normalizedValue.coerceIn(minimum, normalizedMaximum))
    }
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isFixed) "Потоки (лимит сервера):" else "Потоки",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                IconButton(onClick = onInfo, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = "Информация о потоках",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Text(
                text = if (isFixed) fixedWorkers.toString() else localValue.toInt().toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2563EB),
            )
        }
        if (!isFixed) {
            CompactSteppedSlider(
                value = localValue,
                onValueChange = { localValue = it },
                onValueChangeFinished = {
                    val finalValue = localValue.coerceIn(minimum, normalizedMaximum)
                    currentOnValueChange(finalValue)
                },
                valueRange = minimum..normalizedMaximum,
                stepSize = CsqttConstants.Tunnel.WORKERS_PER_GROUP.toFloat(),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            CompactSteppedSlider(
                value = fixedWorkers.toFloat(),
                onValueChange = {},
                valueRange = 3f..maximum.coerceAtLeast(fixedWorkers.toFloat()),
                stepSize = 1f,
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun CompactSteppedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float>,
    stepSize: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant
    val thumbStrokeColor = MaterialTheme.colorScheme.surface
    val focusedThumbStrokeColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { 9.dp.toPx() }
    val trackWidthPx = with(density) { 5.dp.toPx() }

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val currentValue by rememberUpdatedState(value)
    val currentValueRange by rememberUpdatedState(valueRange)
    val currentStepSize by rememberUpdatedState(stepSize)
    var focused by remember { mutableStateOf(false) }

    fun snap(raw: Float): Float {
        return roundToGroup(raw, currentValueRange.endInclusive)
    }

    fun stepNeighbor(current: Float, forward: Boolean): Float {
        val allowed = WORKER_STEPS.filter { it in currentValueRange }
        val idx = allowed.indexOf(current).let { if (it >= 0) it else allowed.indexOf(roundToGroup(current, currentValueRange.endInclusive)) }
        val targetIdx = if (forward) (idx + 1).coerceAtMost(allowed.size - 1) else (idx - 1).coerceAtLeast(0)
        return allowed.getOrElse(targetIdx) { current }
    }

    fun positionToValue(x: Float, width: Float): Float {
        val left = thumbRadiusPx
        val right = (width - thumbRadiusPx).coerceAtLeast(left + 1f)
        val fraction = ((x.coerceIn(left, right) - left) / (right - left)).coerceIn(0f, 1f)
        return snap(currentValueRange.start + fraction * (currentValueRange.endInclusive - currentValueRange.start))
    }

    Canvas(
        modifier = modifier
            .height(34.dp)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionDown -> {
                        val next = stepNeighbor(currentValue, false)
                        currentOnValueChange(next)
                        currentOnValueChangeFinished?.invoke()
                        true
                    }
                    Key.DirectionRight, Key.DirectionUp -> {
                        val next = stepNeighbor(currentValue, true)
                        currentOnValueChange(next)
                        currentOnValueChangeFinished?.invoke()
                        true
                    }
                    else -> false
                }
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value,
                    range = valueRange,
                    steps = (((valueRange.endInclusive - valueRange.start) / stepSize).roundToInt() - 1).coerceAtLeast(0),
                )
            }
            .focusable(enabled)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        currentOnValueChange(positionToValue(offset.x, size.width.toFloat()))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        currentOnValueChange(positionToValue(change.position.x, size.width.toFloat()))
                    },
                    onDragEnd = {
                        currentOnValueChangeFinished?.invoke()
                    },
                    onDragCancel = {
                        currentOnValueChangeFinished?.invoke()
                    }
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    currentOnValueChange(positionToValue(offset.x, size.width.toFloat()))
                    currentOnValueChangeFinished?.invoke()
                }
            },
    ) {
        val centerY = size.height / 2f
        val left = thumbRadiusPx
        val right = size.width - thumbRadiusPx
        val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)
        val fraction = ((value - valueRange.start) / range).coerceIn(0f, 1f)
        val thumbX = left + (right - left) * fraction

        drawLine(inactiveColor, Offset(left, centerY), Offset(right, centerY), trackWidthPx, StrokeCap.Round)
        drawLine(activeColor, Offset(left, centerY), Offset(thumbX, centerY), trackWidthPx, StrokeCap.Round)

        val tickCount = (((valueRange.endInclusive - valueRange.start) / stepSize).roundToInt()).coerceAtLeast(1)
        repeat(tickCount + 1) { index ->
            val tickFraction = index / tickCount.toFloat()
            val tickX = left + (right - left) * tickFraction
            drawCircle(
                color = if (tickX <= thumbX) activeColor else inactiveColor,
                radius = 2.dp.toPx(),
                center = Offset(tickX, centerY),
            )
        }

        drawCircle(color = activeColor, radius = thumbRadiusPx, center = Offset(thumbX, centerY))
        drawCircle(
            color = if (focused) focusedThumbStrokeColor else thumbStrokeColor,
            radius = if (focused) thumbRadiusPx + 2.dp.toPx() else thumbRadiusPx,
            center = Offset(thumbX, centerY),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (focused) 3.dp.toPx() else 2.dp.toPx()),
        )
    }
}

internal val WORKER_STEPS = floatArrayOf(5f, 10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 100f)

internal fun roundToGroup(value: Float, maxW: Float = 100f): Float {
    val allowed = WORKER_STEPS.filter { it <= maxW }
    if (allowed.isEmpty()) return 5f
    return allowed.minByOrNull { kotlin.math.abs(it - value) } ?: allowed.first()
}
