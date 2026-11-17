package com.csqtt.client.ui.v2.design

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * Global Screen & Overlay Transitions for V2 UI.
 * Tweak any values or functions here to change transition timings, curves, and motion globally.
 */
object V2Transitions {
    // === Durations (in milliseconds) ===
    var tabDurationMs: Int = 220
    var overlayDurationMs: Int = 260

    // === Motion Offsets (fraction of screen width) ===
    var overlaySlideFraction: Float = 0.22f

    // === Easings ===
    val standardEaseOut: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val standardEaseIn: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

    /**
     * Transition between bottom bar tabs (Connection, Logs, Deploy, Routing).
     * Fade only: no offset animation means no re-layout of heavy screens per frame.
     */
    fun tabTransition(): ContentTransform {
        val enter = fadeIn(
            animationSpec = tween(durationMillis = tabDurationMs, easing = standardEaseOut),
        )
        val exit = fadeOut(
            animationSpec = tween(durationMillis = (tabDurationMs * 0.7f).toInt(), easing = standardEaseOut),
        )

        return enter togetherWith exit
    }

    /**
     * Transition for overlays (Settings, About).
     * Navigating deeper (e.g. Main -> Settings, or Settings -> About) slides in from right.
     * Navigating back slides out to right.
     */
    fun overlayTransition(isDeeper: Boolean): ContentTransform {
        return if (isDeeper) {
            val enter = slideInHorizontally(
                animationSpec = tween(durationMillis = overlayDurationMs, easing = standardEaseOut),
                initialOffsetX = { fullWidth -> (fullWidth * overlaySlideFraction).toInt() },
            ) + fadeIn(
                animationSpec = tween(durationMillis = overlayDurationMs, easing = standardEaseOut),
            )

            val exit = slideOutHorizontally(
                animationSpec = tween(durationMillis = overlayDurationMs, easing = standardEaseOut),
                targetOffsetX = { fullWidth -> (-fullWidth * (overlaySlideFraction * 0.6f)).toInt() },
            ) + fadeOut(
                animationSpec = tween(durationMillis = (overlayDurationMs * 0.8f).toInt(), easing = standardEaseOut),
            )

            enter togetherWith exit
        } else {
            val enter = slideInHorizontally(
                animationSpec = tween(durationMillis = overlayDurationMs, easing = standardEaseOut),
                initialOffsetX = { fullWidth -> (-fullWidth * (overlaySlideFraction * 0.6f)).toInt() },
            ) + fadeIn(
                animationSpec = tween(durationMillis = overlayDurationMs, easing = standardEaseOut),
            )

            val exit = slideOutHorizontally(
                animationSpec = tween(durationMillis = overlayDurationMs, easing = standardEaseOut),
                targetOffsetX = { fullWidth -> (fullWidth * overlaySlideFraction).toInt() },
            ) + fadeOut(
                animationSpec = tween(durationMillis = (overlayDurationMs * 0.8f).toInt(), easing = standardEaseOut),
            )

            enter togetherWith exit
        }
    }
}
