package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelServicePolicyTest {
    @Test
    fun intentionalWifiPauseKeepsServiceAliveEvenAfterStartupGrace() {
        for (wasRunning in listOf(false, true)) {
            assertFalse(TunnelServicePolicy.shouldStop(
                wasEverRunning = wasRunning,
                running = false,
                elapsedMs = TunnelServicePolicy.STARTUP_GRACE_MS * 2,
                pausedForWifi = true,
            ))
        }
    }

    @Test
    fun startupDoesNotStopBeforeGracePeriod() {
        assertFalse(
            TunnelServicePolicy.shouldStop(
                wasEverRunning = false,
                running = false,
                elapsedMs = TunnelServicePolicy.STARTUP_GRACE_MS - 1,
            ),
        )
    }

    @Test
    fun startupFailureStopsAfterGracePeriod() {
        assertTrue(
            TunnelServicePolicy.shouldStop(
                wasEverRunning = false,
                running = false,
                elapsedMs = TunnelServicePolicy.STARTUP_GRACE_MS,
            ),
        )
    }

    @Test
    fun stoppedRunningTunnelStopsImmediately() {
        assertTrue(
            TunnelServicePolicy.shouldStop(
                wasEverRunning = true,
                running = false,
                elapsedMs = 1_000,
            ),
        )
    }

    @Test
    fun runningTunnelKeepsServiceAlive() {
        assertFalse(
            TunnelServicePolicy.shouldStop(
                wasEverRunning = true,
                running = true,
                elapsedMs = TunnelServicePolicy.STARTUP_GRACE_MS,
            ),
        )
    }
}
