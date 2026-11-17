package com.csqtt.client

import java.util.concurrent.atomic.AtomicBoolean

internal class NetworkPauseGate {
    private val paused = AtomicBoolean(false)

    fun pause(): Boolean = paused.compareAndSet(false, true)

    fun resume(): Boolean = paused.compareAndSet(true, false)

    fun isPaused(): Boolean = paused.get()

    fun reset() {
        paused.set(false)
    }

    fun restore() {
        paused.set(true)
    }
}
