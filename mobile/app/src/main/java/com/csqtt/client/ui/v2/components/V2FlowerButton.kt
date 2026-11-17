@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.csqtt.client.ui.v2.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.graphics.shapes.toPath
import com.csqtt.client.ui.v2.design.V2Palette
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Theme
import kotlin.math.PI
import kotlin.math.cos

private const val CONNECT_SPIN_DEGREES_PER_SECOND = 90f
private const val BREATH_PERIOD_SECONDS = 3.2f
private const val BREATH_SCALE_AMPLITUDE = 0.05f

@Composable
fun V2FlowerButton(
    connected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = V2Sizes.ConnectButton,
    enabled: Boolean = true,
    connecting: Boolean = false,
) {
    val colors = V2Theme.colors
    // Цвет цветка всегда обычный (как в неактивном состоянии)
    val petalColor = colors.flower
    // Glyph stays readable on both light and dark petals.
    val iconColor = remember(petalColor) {
        if (petalColor.luminance() > 0.5f) Color(0xFF141414) else V2Palette.OnInkDark
    }

    val density = LocalDensity.current

    // Render the flower into a bitmap once: scaling a texture is a transform instead of
    // re-tessellating the vector path every frame, which leaked hundreds of MB of GL memory.
    val flowerBitmap = remember(size, density, petalColor) {
        val px = with(density) { size.toPx() }.toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(
            px,
            px,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        val path = android.graphics.Path()
        MaterialShapes.SoftBurst.toPath(path)
        val matrix = android.graphics.Matrix()
        matrix.setScale(px.toFloat(), px.toFloat())
        path.transform(matrix)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = petalColor.toArgb()
        canvas.drawPath(path, paint)
        bitmap.asImageBitmap()
    }

    // Angles come from the monotonic clock, so spin speed never depends on the tick rate.
    val tick = rememberAmbientClock(enabled = connecting || connected, fast = connecting)

    var frozenAngle by remember { mutableFloatStateOf(0f) }
    var spinBaseAngle by remember { mutableFloatStateOf(0f) }
    var spinStartNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connecting) {
        if (connecting) {
            spinBaseAngle = frozenAngle
            spinStartNanos = System.nanoTime()
        } else if (spinStartNanos != 0L) {
            val elapsed = (System.nanoTime() - spinStartNanos) / 1_000_000_000f
            frozenAngle = (spinBaseAngle + elapsed * CONNECT_SPIN_DEGREES_PER_SECOND) % 360f
            spinStartNanos = 0L
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClickLabel = if (connected) "Отключить" else "Подключить",
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = if (connected) "Отключить туннель" else "Подключить туннель"
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = flowerBitmap,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    tick.longValue // subscribe to ambient ticks for draw invalidation
                    val nowNanos = System.nanoTime()
                    // While the effect is starting, spinStartNanos is still 0; show the
                    // frozen angle instead of an arbitrary jump for that first frame.
                    rotationZ = if (connecting && spinStartNanos != 0L) {
                        (spinBaseAngle +
                            (nowNanos - spinStartNanos) / 1_000_000_000f * CONNECT_SPIN_DEGREES_PER_SECOND) % 360f
                    } else {
                        frozenAngle
                    }
                    val scale = if (connected && !connecting) {
                        val phase = (nowNanos / 1_000_000_000f % BREATH_PERIOD_SECONDS) / BREATH_PERIOD_SECONDS
                        1f + BREATH_SCALE_AMPLITUDE * (0.5f - 0.5f * cos(2f * PI.toFloat() * phase))
                    } else {
                        1f
                    }
                    scaleX = scale
                    scaleY = scale
                },
        )
        Canvas(modifier = Modifier.size(size * 0.26f)) {
            drawCenterIcon(connected, iconColor)
        }
    }
}

private fun DrawScope.drawCenterIcon(connected: Boolean, iconColor: Color) {
    val w = size.width
    val h = size.height

    if (connected) {
        val boxSide = w * 0.55f
        val offset = (w - boxSide) / 2f
        drawRoundRect(
            color = iconColor,
            topLeft = Offset(offset, offset),
            size = Size(boxSide, boxSide),
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
        )
        return
    }

    val playWidth = w * 0.58f
    val playHeight = h * 0.65f
    val left = (w - playWidth) / 2f + w * 0.05f // slight visual centering
    val right = left + playWidth
    val top = (h - playHeight) / 2f
    val bottom = top + playHeight
    val midY = h / 2f

    val playPath = Path().apply {
        moveTo(left, top)
        lineTo(left, bottom)
        lineTo(right, midY)
        close()
    }
    drawPath(playPath, iconColor)
    drawPath(
        playPath,
        iconColor,
        style = Stroke(
            width = w * 0.16f,
            join = StrokeJoin.Round,
            cap = StrokeCap.Round,
        ),
    )
}
