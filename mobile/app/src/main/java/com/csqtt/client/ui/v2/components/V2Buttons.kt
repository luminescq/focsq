package com.csqtt.client.ui.v2.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.csqtt.client.R
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Theme

enum class V2PillStyle {
    Contrast,
    Outline,
    Accent,
    Danger,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun V2PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    style: V2PillStyle = V2PillStyle.Contrast,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 54.dp,
    horizontalPadding: Dp = 10.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    val colors = V2Theme.colors
    val background = when (style) {
        V2PillStyle.Contrast -> if (enabled) colors.contrast else colors.field
        V2PillStyle.Outline -> if (enabled) colors.surfaceElevated else colors.field
        V2PillStyle.Accent -> if (enabled) colors.accent else colors.accentSoft
        V2PillStyle.Danger -> if (enabled) colors.error else colors.error.copy(alpha = 0.4f)
    }
    val contentColor = when (style) {
        V2PillStyle.Contrast -> if (enabled) colors.onContrast else colors.textMuted
        V2PillStyle.Outline -> if (enabled) colors.textPrimary else colors.textMuted
        V2PillStyle.Accent -> if (enabled) colors.onAccent else colors.textMuted
        V2PillStyle.Danger -> if (enabled) Color.White else Color.White.copy(alpha = 0.5f)
    }

    Surface(
        modifier = modifier
            .height(height)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        shape = V2Shapes.Button, // 14.dp: bigger, squarer
        color = background,
        contentColor = contentColor,
        border = null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center, // Centered icon and text
        ) {
            if (loading) {
                LoadingIndicator(
                    modifier = Modifier.size(20.dp),
                    color = contentColor,
                )
                Spacer(modifier = Modifier.size(8.dp))
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
            } else if (iconPainter != null) {
                Icon(
                    painter = iconPainter,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = fontSize,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun V2CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = V2Sizes.CircleButton,
    iconSize: Dp = V2Sizes.CircleButtonIcon,
) {
    val colors = V2Theme.colors
    Surface(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = CircleShape,
        color = colors.surfaceElevated,
        contentColor = colors.textPrimary,
        border = null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun V2CircleIconButton(
    iconPainter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = V2Sizes.CircleButton,
    iconSize: Dp = V2Sizes.CircleButtonIcon,
) {
    val colors = V2Theme.colors
    Surface(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = CircleShape,
        color = colors.surfaceElevated,
        contentColor = colors.textPrimary,
        border = null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = iconPainter,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun V2SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
    iconSize: Dp = 22.dp,
    shape: androidx.compose.ui.graphics.Shape = V2Shapes.Field,
    backgroundColor: Color? = null,
    contentColor: Color? = null,
    border: androidx.compose.foundation.BorderStroke? = null,
) {
    val colors = V2Theme.colors
    val bg = backgroundColor ?: colors.surfaceElevated
    val fg = contentColor ?: colors.textPrimary
    Surface(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = shape,
        color = bg,
        contentColor = fg,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun V2SquareIconButton(
    iconPainter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
    iconSize: Dp = 22.dp,
    shape: androidx.compose.ui.graphics.Shape = V2Shapes.Field,
    backgroundColor: Color? = null,
    contentColor: Color? = null,
    border: androidx.compose.foundation.BorderStroke? = null,
) {
    val colors = V2Theme.colors
    val bg = backgroundColor ?: colors.surfaceElevated
    val fg = contentColor ?: colors.textPrimary
    Surface(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = shape,
        color = bg,
        contentColor = fg,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = iconPainter,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun V2TransparentIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val colors = V2Theme.colors
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: colors.textSecondary,
            modifier = Modifier.size(V2Sizes.IconSmall),
        )
    }
}

/**
 * Back button for headers (Settings, About, etc.) — styled as an elongated pill/stadium
 * matching the design mockup and design language.
 */
@Composable
fun V2BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    height: Dp = V2Sizes.CircleButton,
) {
    val colors = V2Theme.colors
    Surface(
        modifier = modifier
            .size(width = width, height = height)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = V2Shapes.Pill,
        color = colors.bottomSurface,
        contentColor = colors.textPrimary,
        border = null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_v2_arrow_left),
                contentDescription = "Назад",
                tint = colors.textPrimary,
                modifier = Modifier.size(V2Sizes.CircleButtonIcon),
            )
        }
    }
}
