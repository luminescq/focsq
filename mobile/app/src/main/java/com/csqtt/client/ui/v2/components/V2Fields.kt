package com.csqtt.client.ui.v2.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Theme

@Composable
fun V2InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    V2Card(
        modifier = modifier,
        color = colors.bottomSurface,
        shape = V2Shapes.Row,
        borderColor = null,
        contentPadding = PaddingValues(16.dp, 14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                ),
                color = colors.textSecondary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                ),
                color = colors.textPrimary,
            )
        }
    }
}

@Composable
fun V2LabeledField(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = V2Theme.colors
    V2Card(
        modifier = modifier,
        color = colors.bottomSurface,
        shape = V2Shapes.Row,
        borderColor = null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp - 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                ),
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = V2BodyFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun V2SecretPathField(
    title: String,
    value: String,
    onRefresh: () -> Unit,
    onCopy: () -> Unit = {},
    onOpen: (() -> Unit)? = null,
    busy: Boolean = false,
    status: String = "",
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    V2Card(
        modifier = modifier,
        color = colors.bottomSurface,
        shape = V2Shapes.Row,
        borderColor = null,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = V2BodyFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                ),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                V2SquareIconButton(
                    icon = Icons.Default.ContentCopy,
                    contentDescription = "Скопировать ссылку",
                    onClick = onCopy,
                    size = 34.dp,
                    iconSize = 16.dp,
                    shape = RoundedCornerShape(8.dp),
                    contentColor = colors.textPrimary,
                )
                if (onOpen != null) {
                    V2SquareIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Открыть панель",
                        onClick = onOpen,
                        size = 34.dp,
                        iconSize = 16.dp,
                        shape = RoundedCornerShape(8.dp),
                        contentColor = colors.textPrimary,
                    )
                }
                if (busy) {
                    Box(
                        modifier = Modifier.size(34.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(18.dp),
                            color = colors.accent,
                        )
                    }
                } else {
                    V2SquareIconButton(
                        icon = Icons.Filled.Refresh,
                        contentDescription = "Обновить путь",
                        onClick = onRefresh,
                        size = 34.dp,
                        iconSize = 16.dp,
                        shape = RoundedCornerShape(8.dp),
                        contentColor = colors.textPrimary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCopy,
                ),
            shape = V2Shapes.Field,
            color = colors.field,
            contentColor = colors.textPrimary,
            border = null,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = V2BodyFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (status.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = V2BodyFamily,
                    fontSize = 12.sp,
                ),
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
