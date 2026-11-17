package com.csqtt.client.ui.v2.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Theme

enum class V2Tab(
    val title: String,
    val iconRes: Int,
) {
    Connection("Подключение", com.csqtt.client.R.drawable.ic_v2_house),
    Logs("Логи", com.csqtt.client.R.drawable.ic_v2_terminal),
    Deploy("Деплой", com.csqtt.client.R.drawable.ic_v2_cloud),
    Routing("Роутинг", com.csqtt.client.R.drawable.ic_v2_routing),
}

@Composable
fun V2BottomBar(
    selected: V2Tab,
    onSelect: (V2Tab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<V2Tab> = V2Tab.entries,
) {
    val colors = V2Theme.colors
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp),
        shape = V2Shapes.Pill,
        color = colors.bottomSurface,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 5.dp),
        ) {
            val totalWidth = maxWidth
            val tabWidth = totalWidth / tabs.size
            // Larger, bolder pill indicator
            val indicatorWidth = (tabWidth * 0.94f).coerceAtMost(90.dp)
            val indicatorHeight = 50.dp

            val animatedIndex by animateFloatAsState(
                targetValue = selectedIndex.toFloat(),
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = 450f,
                ),
                label = "v2-bottom-bar-indicator",
            )

            // Smooth sliding indicator pill
            val indicatorX = tabWidth * animatedIndex + (tabWidth - indicatorWidth) / 2

            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(indicatorWidth)
                    .height(indicatorHeight)
                    .align(Alignment.CenterStart)
                    .clip(V2Shapes.Pill)
                    .background(colors.navSelected),
            )

            // Interactive tab buttons
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val isSelected = tab == selected
                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) colors.textPrimary else colors.textSecondary.copy(alpha = 0.7f),
                        animationSpec = tween(durationMillis = 200),
                        label = "v2-tab-icon-tint",
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = 0.65f,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "v2-tab-icon-scale",
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null, // Completely removes default grey ripple / crescent artifacts
                            ) {
                                onSelect(tab)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(tab.iconRes),
                            contentDescription = tab.title,
                            tint = iconTint,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                        )
                    }
                }
            }
        }
    }
}
