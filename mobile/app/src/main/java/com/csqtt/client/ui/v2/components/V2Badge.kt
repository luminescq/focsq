@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.csqtt.client.ui.v2.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.toPath
import com.csqtt.client.ui.v2.design.V2Palette
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2VersionFamily

private const val BADGE_DEGREES_PER_SECOND = 15f

/** Rotating SoftBurst badge with the version text kept horizontal. */
@Composable
fun V2VersionBadge(
    version: String,
    modifier: Modifier = Modifier,
    size: Dp = V2Sizes.VersionBadge,
    background: Color = V2Palette.InkDark,
    contentColor: Color = V2Palette.OnInkDark,
    rotating: Boolean = true,
) {
    val density = LocalDensity.current
    // Same trick as the connection flower: cache the vector shape in a bitmap so the
    // rotation is a cheap texture transform instead of re-tessellating the path.
    val badgeBitmap = remember(size, density, background) {
        val px = with(density) { size.toPx() }.toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(
            px,
            px,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        val path = android.graphics.Path()
        MaterialShapes.Cookie9Sided.toPath(path)
        val matrix = android.graphics.Matrix()
        matrix.setScale(px.toFloat(), px.toFloat())
        path.transform(matrix)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = background.toArgb()
        canvas.drawPath(path, paint)
        bitmap.asImageBitmap()
    }
    val tick = rememberAmbientClock(enabled = rotating)

    // Measure rotation from the moment spinning is (re)enabled, so toggling it back on
    // resumes from the frozen angle instead of jumping to an absolute-clock angle.
    var frozenAngle by remember { mutableFloatStateOf(0f) }
    var spinBaseAngle by remember { mutableFloatStateOf(0f) }
    var spinStartNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(rotating) {
        if (rotating) {
            spinBaseAngle = frozenAngle
            spinStartNanos = System.nanoTime()
        } else if (spinStartNanos != 0L) {
            val elapsed = (System.nanoTime() - spinStartNanos) / 1_000_000_000f
            frozenAngle = (spinBaseAngle + elapsed * BADGE_DEGREES_PER_SECOND) % 360f
            spinStartNanos = 0L
        }
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = badgeBitmap,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    tick.longValue // subscribe to ambient ticks for draw invalidation
                    rotationZ = if (rotating && spinStartNanos != 0L) {
                        val elapsed = (System.nanoTime() - spinStartNanos) / 1_000_000_000f
                        (spinBaseAngle + elapsed * BADGE_DEGREES_PER_SECOND) % 360f
                    } else {
                        frozenAngle
                    }
                },
        )
        Text(
            text = version,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = V2VersionFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                letterSpacing = 0.5.sp,
            ),
            color = contentColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
fun V2Avatar(
    initials: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = V2Sizes.Avatar,
    initialsColor: Color = V2Palette.OnInkDark,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = initialsColor,
        )
    }
}
