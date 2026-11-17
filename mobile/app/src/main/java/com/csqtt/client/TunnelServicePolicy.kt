package com.csqtt.client

internal object TunnelServicePolicy {
    const val STARTUP_GRACE_MS = 60_000L

    fun shouldStop(
        wasEverRunning: Boolean,
        running: Boolean,
        elapsedMs: Long,
        pausedForWifi: Boolean = false,
    ): Boolean = !pausedForWifi && !running && (wasEverRunning || elapsedMs >= STARTUP_GRACE_MS)
}
