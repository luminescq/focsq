package com.csqtt.client.ui.v2.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Theme

@Composable
fun V2SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = V2Theme.colors

    V2Card(
        modifier = modifier,
        color = colors.bottomSurface,
        shape = V2Shapes.Row,
        borderColor = null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                ),
                color = if (enabled) colors.textPrimary else colors.textMuted,
            )
            trailing()
        }
    }
}

/** Custom sleek matte switch with spring physics */
@Composable
fun V2Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = V2Theme.colors
    val thumbOffset by animateFloatAsState(
        targetValue = if (checked) 20f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "v2-switch-thumb",
    )
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.field
            checked -> colors.contrast
            else -> colors.field
        },
        label = "v2-switch-track",
    )
    val thumbColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textMuted
            checked -> colors.onContrast
            else -> colors.textSecondary
        },
        label = "v2-switch-thumb-color",
    )

    Box(
        modifier = modifier
            .width(48.dp)
            .height(28.dp)
            .clip(V2Shapes.Pill)
            .background(trackColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}

@Composable
fun V2SwitchSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onInfoClick: (() -> Unit)? = null,
) {
    V2SettingRow(title = title, modifier = modifier, enabled = enabled) {
        if (onInfoClick != null) {
            V2SquareIconButton(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = "Подробнее",
                onClick = onInfoClick,
                size = 30.dp,
                iconSize = 16.dp,
                shape = V2Shapes.Pill,
                backgroundColor = Color.Transparent,
                contentColor = V2Theme.colors.textMuted,
            )
        }
        V2Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
fun V2DropdownPill(
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onInfo: ((String) -> Unit)? = null,
) {
    val colors = V2Theme.colors
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .height(34.dp)
                .then(
                    if (enabled) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = true }
                    } else {
                        Modifier
                    },
                ),
            shape = V2Shapes.Button,
            color = colors.field,
            contentColor = if (enabled) colors.textPrimary else colors.textMuted,
            border = null,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = V2BodyFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(V2Sizes.IconSmall),
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = colors.bottomSurface,
            tonalElevation = 0.dp,
            shadowElevation = 18.dp,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = V2BodyFamily,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    },
                    trailingIcon = if (onInfo != null) {
                        {
                            IconButton(
                                onClick = {
                                    expanded = false
                                    onInfo(option)
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                    contentDescription = "Информация: $option",
                                    tint = colors.textMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
fun V2DropdownSetting(
    title: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onTitleInfo: (() -> Unit)? = null,
    onInfo: ((String) -> Unit)? = null,
) {
    V2SettingRow(
        title = title,
        modifier = modifier,
        enabled = enabled,
    ) {
        if (onTitleInfo != null) {
            V2SquareIconButton(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = "Информация: $title",
                onClick = onTitleInfo,
                size = 30.dp,
                iconSize = 16.dp,
                shape = V2Shapes.Pill,
                backgroundColor = Color.Transparent,
                contentColor = V2Theme.colors.textMuted,
            )
        }
        V2DropdownPill(
            value = value,
            options = options,
            onSelected = onSelected,
            enabled = enabled,
            onInfo = onInfo,
        )
    }
}

@Composable
fun V2ActionSetting(
    title: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    action: String? = null,
    enabled: Boolean = true,
    accent: Boolean = false,
) {
    val colors = V2Theme.colors
    V2SettingRow(
        title = title,
        onClick = onAction,
        modifier = modifier,
        enabled = enabled,
    ) {
        if (!action.isNullOrBlank()) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                ),
                color = if (accent) colors.contrast else colors.textSecondary,
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(4.dp))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (accent) colors.contrast else colors.textSecondary,
            modifier = Modifier.size(V2Sizes.IconSmall),
        )
    }
}

@Composable
fun V2ActionSetting(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
) = V2ActionSetting(
    title = title,
    onAction = onClick,
    modifier = modifier,
    action = null,
    enabled = enabled,
    accent = accent,
)

@Composable
fun V2Chevron(
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = modifier.size(com.csqtt.client.ui.v2.design.V2Sizes.IconMedium),
        tint = tint ?: com.csqtt.client.ui.v2.design.V2Theme.colors.textMuted,
    )
}
