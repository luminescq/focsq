package com.csqtt.client.ui.v2.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Every tick redraws the whole window, which costs several ms on low-end devices, so
// idle decoration runs at ~20 updates/s. Short transitional animations (connecting)
// request the faster rate so their motion stays smooth.
private const val AMBIENT_TICK_MS = 50L
private const val AMBIENT_FAST_TICK_MS = 33L

private object AmbientClock {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeners = 0
    private var fastListeners = 0
    private var job: Job? = null
    private var jobDelayMs = 0L

    val tick = mutableLongStateOf(0L)

    fun acquire(fast: Boolean) {
        listeners++
        if (fast) fastListeners++
        restartIfNeeded()
    }

    fun release(fast: Boolean) {
        if (listeners > 0) listeners--
        if (fast && fastListeners > 0) fastListeners--
        restartIfNeeded()
    }

    private fun restartIfNeeded() {
        if (listeners == 0) {
            job?.cancel()
            job = null
            jobDelayMs = 0L
            return
        }
        val delayMs = if (fastListeners > 0) AMBIENT_FAST_TICK_MS else AMBIENT_TICK_MS
        if (job != null && jobDelayMs == delayMs) return
        job?.cancel()
        jobDelayMs = delayMs
        job = scope.launch {
            while (isActive) {
                delay(delayMs)
                tick.longValue++
            }
        }
    }
}

/**
 * Shared tick counter for decorative shape animations. All subscribers update in the
 * same frame, and the clock stops while no host lifecycle is resumed. Read the returned
 * state inside a draw-phase lambda (e.g. `graphicsLayer {}`) so ticks invalidate only
 * the layer instead of recomposing.
 */
@Composable
fun rememberAmbientClock(enabled: Boolean = true, fast: Boolean = false): LongState {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, enabled, fast) {
        if (!enabled) {
            return@DisposableEffect onDispose { }
        }
        var resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (resumed) AmbientClock.acquire(fast)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!resumed) {
                    resumed = true
                    AmbientClock.acquire(fast)
                }
                Lifecycle.Event.ON_PAUSE -> if (resumed) {
                    resumed = false
                    AmbientClock.release(fast)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) {
                resumed = false
                AmbientClock.release(fast)
            }
        }
    }
    return AmbientClock.tick
}
