package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

class WifiAutoPausePolicyTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun transitionCompletesWhenItCancelsItsStartOrWatcherCaller() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            var persisted = false
            var paused = false
            lateinit var caller: Job
            caller = launch(start = CoroutineStart.LAZY) {
                runWifiAutoPauseTransition(owner) {
                    caller.cancel()
                    yield() // DataStore/manager suspension after cancelling startJob.
                    persisted = true
                    paused = true
                }
            }
            caller.start()
            advanceUntilIdle()
            assertTrue(caller.isCancelled)
            assertTrue(persisted)
            assertTrue(paused)
        } finally {
            owner.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stoppingTheServiceStillCancelsATransition() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var completed = false
        val caller = launch {
            runWifiAutoPauseTransition(owner) {
                owner.cancel()
                yield()
                completed = true
            }
        }
        advanceUntilIdle()
        assertTrue(caller.isCancelled)
        assertEquals(false, completed)
    }

    @Test
    fun pausesOnlyForAnEnabledRequestedTunnelOnWifi() {
        assertEquals(
            WifiAutoPauseAction.PAUSE,
            wifiAutoPauseAction(
                enabled = true,
                wifiConnected = true,
                desiredRunning = true,
                paused = false,
            ),
        )
        assertEquals(
            WifiAutoPauseAction.NONE,
            wifiAutoPauseAction(
                enabled = true,
                wifiConnected = true,
                desiredRunning = false,
                paused = false,
            ),
        )
    }

    @Test
    fun leavesAWifiPauseWhenWifiLeavesOrTheFeatureIsDisabled() {
        assertEquals(
            WifiAutoPauseAction.RESUME,
            wifiAutoPauseAction(
                enabled = true,
                wifiConnected = false,
                desiredRunning = true,
                paused = true,
            ),
        )
        assertEquals(
            WifiAutoPauseAction.RESUME,
            wifiAutoPauseAction(
                enabled = false,
                wifiConnected = true,
                desiredRunning = true,
                paused = true,
            ),
        )
    }

    @Test
    fun defaultNetworkOverridesStaleCallbacksBeforeAndDuringPause() {
        assertEquals(false, effectiveWifiAutoPauseConnection(true, false))
        assertEquals(true, effectiveWifiAutoPauseConnection(false, true))
        assertEquals(true, effectiveWifiAutoPauseConnection(true, null))
        assertEquals(false, effectiveWifiAutoPauseConnection(false, null))
    }

    @Test
    fun wifiPauseStopsVpnButKeepsControllerAlive() {
        val stopped = mutableListOf<String>()
        dispatchTunnelStop(true, { stopped.add("vpn") }, { stopped.add("controller") })
        assertEquals(listOf("vpn"), stopped)
    }

    @Test
    fun normalStopStillStopsVpnAndController() {
        val stopped = mutableListOf<String>()
        dispatchTunnelStop(false, { stopped.add("vpn") }, { stopped.add("controller") })
        assertEquals(listOf("vpn", "controller"), stopped)
    }

    @Test
    fun disablingPauseWhileWaitingResumesOnlyOnce() {
        var paused = true
        val actions = mutableListOf<WifiAutoPauseAction>()
        repeat(10) {
            val action = wifiAutoPauseAction(false, true, true, paused)
            if (action != WifiAutoPauseAction.NONE) actions.add(action)
            if (action == WifiAutoPauseAction.RESUME) paused = false
        }
        assertEquals(listOf(WifiAutoPauseAction.RESUME), actions)
    }

    @Test
    fun mobileWifiMobileHandoverDoesNotOscillateWithStaleCallbacks() {
        var paused = false
        val actions = mutableListOf<WifiAutoPauseAction>()
        // A Wi-Fi callback can arrive before it becomes the default, and onLost
        // can lag behind cellular becoming the default. No default is unknown.
        val networks = listOf(
            false to false, true to false,
            true to true, true to null, true to true,
            true to false, true to false, false to false,
        )
        for ((callbackWifi, defaultWifi) in networks) {
            val wifi = effectiveWifiAutoPauseConnection(callbackWifi, defaultWifi)
            val action = wifiAutoPauseAction(true, wifi, true, paused)
            if (action != WifiAutoPauseAction.NONE) actions.add(action)
            when (action) {
                WifiAutoPauseAction.PAUSE -> paused = true
                WifiAutoPauseAction.RESUME -> paused = false
                WifiAutoPauseAction.NONE -> Unit
            }
        }
        assertEquals(listOf(WifiAutoPauseAction.PAUSE, WifiAutoPauseAction.RESUME), actions)
    }

    @Test
    fun manualStopMustNotRequestAutomaticResume() {
        assertEquals(WifiAutoPauseAction.NONE, wifiAutoPauseAction(false, false, false, true))
        assertEquals(WifiAutoPauseAction.NONE, wifiAutoPauseAction(true, false, false, true))
    }
    @Test
    fun powerButtonStopsRunningStartingAndWifiWaitingStates() {
        for (running in listOf(false, true)) for (starting in listOf(false, true)) for (paused in listOf(false, true)) {
            assertEquals(running || starting || paused, tunnelPowerIsOn(running, starting, paused))
        }
    }

    @Test
    fun controllerOwnedStopDoesNotSendAStopBackToItself() {
        val stopped = mutableListOf<String>()
        dispatchTunnelStop(false, { stopped.add("vpn") }, { stopped.add("controller") }, notifyController = false)
        assertEquals(listOf("vpn"), stopped)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun manualStopWinsWhilePauseIsSuspendedInStorageOrNativeCleanup() = runTest {
        for (duringStorage in listOf(true, false)) {
            val mutex = kotlinx.coroutines.sync.Mutex()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val proceed = kotlinx.coroutines.CompletableDeferred<Unit>()
            var requested = true
            var savedRunning = false
            var publishedWaiting = false
            var controllerStopped = false
            val pause = launch {
                mutex.lock()
                try {
                    val completed = completeWifiAutoPause(
                        isRequested = { requested },
                        saveRunningIntent = {
                            if (duringStorage) { entered.complete(Unit); proceed.await() }
                            savedRunning = true
                        },
                        pauseDataPlane = {
                            if (!duringStorage) { entered.complete(Unit); proceed.await() }
                        },
                    )
                    if (completed) publishedWaiting = true
                } finally { mutex.unlock() }
            }
            entered.await()
            requested = false // Synchronous manual-stop intent before waiting for mutex.
            val stop = launch {
                mutex.lock()
                try { savedRunning = false; controllerStopped = true }
                finally { mutex.unlock() }
            }
            proceed.complete(Unit)
            pause.join()
            stop.join()
            assertEquals(false, savedRunning)
            assertEquals(false, publishedWaiting)
            assertTrue(controllerStopped)
        }
    }

}
