package com.csqtt.client

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class TunnelStartupStage {
    WAITING_FOR_CREDENTIALS,
    WAITING_FOR_TURN_OR_PEER,
    READY,
}

internal class TunnelStartupProgress {
    private val credentials = AtomicInteger()
    private val ready = AtomicBoolean(false)

    fun credentialReceived(): Int = credentials.incrementAndGet()

    fun streamReady() {
        ready.set(true)
    }

    fun stage(): TunnelStartupStage = when {
        ready.get() -> TunnelStartupStage.READY
        credentials.get() > 0 -> TunnelStartupStage.WAITING_FOR_TURN_OR_PEER
        else -> TunnelStartupStage.WAITING_FOR_CREDENTIALS
    }
}
