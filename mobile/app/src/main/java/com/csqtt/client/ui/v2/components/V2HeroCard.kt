package com.csqtt.client.ui.v2.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.R
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Theme

@Composable
fun V2HeroCard(
    title: String,
    version: String? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showGithub: Boolean = false,
    onGithubClick: () -> Unit = {},
    badgeIcon: ImageVector? = null,
    onBadgeClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = V2Theme.colors
    val badgeInteraction = remember { MutableInteractionSource() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = V2Shapes.Hero,
        color = colors.bottomSurface,
        contentColor = colors.textPrimary,
        border = null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = V2DisplayFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            letterSpacing = 0.sp,
                        ),
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = V2DisplayFamily,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = colors.textSecondary,
                        )
                    }
                    if (showGithub) {
                        Surface(
                            modifier = Modifier
                                .height(34.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onGithubClick,
                                ),
                            shape = V2Shapes.Pill,
                            color = colors.field,
                            contentColor = colors.textPrimary,
                            border = null,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_github),
                                    contentDescription = null,
                                    tint = colors.textPrimary,
                                    modifier = Modifier.size(V2Sizes.IconSmall),
                                )
                                Text(
                                    text = "GitHub",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = V2DisplayFamily,
                                        fontWeight = FontWeight.ExtraBold,
                                    ),
                                    color = colors.textPrimary,
                                )
                            }
                        }
                    }
                    if (footer != null) {
                        footer()
                    }
                }
                if (version != null) {
                    V2VersionBadge(
                        version = version,
                        rotating = true,
                        modifier = if (onBadgeClick != null) {
                            Modifier.clickable(
                                interactionSource = badgeInteraction,
                                indication = null,
                                onClick = onBadgeClick,
                            )
                        } else {
                            Modifier
                        },
                    )
                }
            }
            if (actions != null) {
                Spacer(modifier = Modifier.height(14.dp))
                actions()
            }
        }
    }
}
