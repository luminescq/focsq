@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csqtt.client.ui.v2.dialogs

import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.csqtt.client.VkAuthPayload
import com.csqtt.client.VkAuthPhase
import com.csqtt.client.VkAuthSession
import com.csqtt.client.VkAuthWebViewManager
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme

@Composable
internal fun V2VkAuthDialog(
    onToken: (VkAuthPayload) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = V2Theme.colors
    val scope = rememberCoroutineScope()
    var uiReady by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(VkAuthPhase.LOGIN) }
    var attempt by remember { mutableStateOf(0) }
    var dismissRequested by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun animateDismiss(onDone: () -> Unit = onDismiss) {
        if (dismissRequested) return
        dismissRequested = true
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            onDone()
        }
    }

    val webView = remember(attempt) { VkAuthWebViewManager.acquire(context) }
    val session = remember(attempt) {
        VkAuthSession(
            onUiReady = { uiReady = true },
            onToken = { payload ->
                animateDismiss {
                    onToken(payload)
                }
            },
            onError = { errorMessage = it },
            onPhaseChange = { phase = it },
        )
    }

    LaunchedEffect(attempt) {
        if (attempt > 0) {
            uiReady = false
            errorMessage = null
            phase = VkAuthPhase.LOGIN
        }
    }

    DisposableEffect(session, webView, lifecycleOwner) {
        session.attach(webView)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> session.pause()
                Lifecycle.Event.ON_START -> session.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.stop()
            VkAuthWebViewManager.release(webView)
        }
    }

    val webAlpha by animateFloatAsState(
        targetValue = if (uiReady && errorMessage == null) 1f else 0f,
        label = "vk_auth_web_alpha",
    )

    val title = when (phase) {
        VkAuthPhase.LOGIN -> "Вход в аккаунт ВК"
        VkAuthPhase.TOKEN -> "Получение токена"
    }

    val subtitle = if (phase == VkAuthPhase.LOGIN) {
        "Войдите любым способом для получения токена"
    } else {
        null
    }

    ModalBottomSheet(
        onDismissRequest = { animateDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = colors.bottomSurface,
        contentColor = colors.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp),
                shape = RoundedCornerShape(2.dp),
                color = colors.textMuted.copy(alpha = 0.4f),
            ) {}
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V2Spacing.Lg)
                .padding(bottom = V2Spacing.Lg)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = V2BodyFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                V2CircleIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                )
            }

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 13.sp,
                    ),
                    color = colors.textSecondary,
                )
            }

            // Webview Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            isFocusable = false
                            isFocusableInTouchMode = false
                            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                            val radius = 16f * ctx.resources.displayMetrics.density
                            outlineProvider = object : ViewOutlineProvider() {
                                override fun getOutline(view: View, outline: Outline) {
                                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                                }
                            }
                            clipToOutline = true
                            addView(
                                webView,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                ),
                            )
                            post { webView.requestFocus() }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(webAlpha),
                )

                if (!uiReady && errorMessage == null) {
                    CircularProgressIndicator(color = colors.accent)
                }

                errorMessage?.let { message ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
                        modifier = Modifier.padding(V2Spacing.Lg),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = V2BodyFamily),
                            color = colors.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm)) {
                            V2PillButton(
                                text = "Повторить",
                                onClick = { attempt += 1 },
                                style = V2PillStyle.Contrast,
                                height = 50.dp,
                            )
                            V2PillButton(
                                text = "Закрыть",
                                onClick = { animateDismiss() },
                                style = V2PillStyle.Outline,
                                height = 50.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}
