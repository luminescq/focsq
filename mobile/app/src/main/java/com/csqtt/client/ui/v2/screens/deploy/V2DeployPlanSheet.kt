@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csqtt.client.ui.v2.screens.deploy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.legacy.DeployPlan
import com.csqtt.client.ui.legacy.PlanAction
import com.csqtt.client.ui.legacy.PlanKind
import com.csqtt.client.ui.legacy.describeServer
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch

/**
 * Check-first deploy plan: what is already on the server and which actions fit it.
 * The action list comes from the legacy [com.csqtt.client.ui.legacy.planDeploy] policy,
 * so V2 keeps the same safety rules (side-by-side install, port clashes, foreign installs).
 */
@Composable
internal fun V2DeployPlanSheet(
    host: String,
    plan: DeployPlan,
    profilePeerPort: Int?,
    onChoose: (PlanAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = V2Theme.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun animateDismiss(onDone: () -> Unit = onDismiss) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDone() }
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "На сервере",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = V2DisplayFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                        ),
                        color = colors.textPrimary,
                    )
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = V2BodyFamily,
                            fontSize = 12.sp,
                        ),
                        color = colors.textMuted,
                    )
                }
                V2CircleIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                )
            }

            if (plan.servers.isEmpty()) {
                Text(
                    text = "FOCSQ не найден — сервер чистый",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            } else {
                Text(
                    text = "Найдено",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = V2DisplayFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = colors.textMuted,
                )
                plan.servers.forEach { server ->
                    val (title, detail) = describeServer(server, profilePeerPort)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(V2Shapes.Row)
                            .background(colors.field)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = V2BodyFamily,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = colors.textPrimary,
                        )
                        if (detail.isNotBlank()) {
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = V2BodyFamily,
                                    fontSize = 12.sp,
                                ),
                                color = colors.textMuted,
                            )
                        }
                    }
                }
            }

            plan.notes.forEach { note ->
                Text(
                    text = note,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(V2Shapes.Row)
                        .background(colors.warning.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    ),
                    color = colors.warning,
                )
            }

            if (plan.actions.isNotEmpty()) {
                Text(
                    text = "Что сделать",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = V2DisplayFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                plan.actions.forEachIndexed { index, action ->
                    PlanActionRow(
                        action = action,
                        recommended = index == 0 && !action.destructive,
                        onClick = {
                            animateDismiss {
                                onChoose(action)
                            }
                        },
                    )
                }
            } else {
                V2PillButton(
                    text = "Закрыть",
                    onClick = { animateDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    style = V2PillStyle.Outline,
                    height = 48.dp,
                )
            }
        }
    }
}

@Composable
private fun PlanActionRow(
    action: PlanAction,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    val colors = V2Theme.colors
    val destructive = action.destructive
    val tint = when {
        destructive -> colors.error
        recommended -> colors.accent
        else -> colors.textSecondary
    }
    val background = when {
        destructive -> colors.error.copy(alpha = 0.10f)
        recommended -> colors.accentSoft.copy(alpha = 0.55f)
        else -> colors.field
    }
    val icon = actionIcon(action.kind, destructive)

    Surface(
        onClick = onClick,
        shape = V2Shapes.Row,
        color = background,
        contentColor = colors.textPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(V2Shapes.Pill)
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = action.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = V2DisplayFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        ),
                        color = if (destructive) colors.error else colors.textPrimary,
                    )
                    if (recommended) {
                        Text(
                            text = "рекомендуется",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = colors.accent,
                        )
                    }
                }
                Text(
                    text = action.detail,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    ),
                    color = colors.textSecondary,
                )
            }
        }
    }
}

private fun actionIcon(kind: PlanKind, destructive: Boolean): ImageVector = when {
    destructive -> Icons.Filled.Delete
    kind == PlanKind.UPDATE -> Icons.Filled.CloudUpload
    kind == PlanKind.INSTALL_NEXT_TO -> Icons.Filled.Add
    kind == PlanKind.REPLACE -> Icons.Filled.Replay
    else -> Icons.Filled.Download
}
