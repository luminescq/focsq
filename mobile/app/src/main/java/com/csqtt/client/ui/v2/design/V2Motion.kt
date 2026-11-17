package com.csqtt.client.ui.v2.design

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object V2Motion {
    const val Instant: Int = 0
    const val Fast: Int = 140
    const val Normal: Int = 220
    const val Slow: Int = 320

    val StandardEasing: Easing = FastOutSlowInEasing

    fun <T> fastTween(): TweenSpec<T> = tween(durationMillis = 140, easing = StandardEasing)
    fun <T> standardTween(): TweenSpec<T> = tween(durationMillis = 220, easing = StandardEasing)
    fun <T> slowTween(): TweenSpec<T> = tween(durationMillis = 320, easing = StandardEasing)
    fun <T> softSpring(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 520.0f)
}
