package com.csqtt.client.ui.legacy.design

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable

@Immutable
object CsqttMotion {
    const val Instant = 90
    const val Fast = 140
    const val Normal = 220
    const val Slow = 320
    const val Backdrop = 520

    val StandardEasing = FastOutSlowInEasing
    val DecelerateEasing = LinearOutSlowInEasing
    val AccelerateEasing = FastOutLinearInEasing

    fun <T> fastTween() = tween<T>(durationMillis = Fast, easing = StandardEasing)

    fun <T> standardTween() = tween<T>(durationMillis = Normal, easing = StandardEasing)

    fun <T> slowTween() = tween<T>(durationMillis = Slow, easing = StandardEasing)

    fun <T> backdropTween() = tween<T>(durationMillis = Backdrop, easing = StandardEasing)

    fun <T> softSpring() = spring<T>(dampingRatio = 0.88f, stiffness = 500f)
}
