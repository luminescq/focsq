package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TunnelLifecycleStateTest {
    @Test
    fun generationAdvancesWhenClockStallsOrMovesBackward() {
        assertTrue(nextTunnelGenerationId(100, 100) == 101L)
        assertTrue(nextTunnelGenerationId(50, 100) == 101L)
        assertTrue(nextTunnelGenerationId(200, 100) == 200L)
        assertTrue(nextTunnelGenerationId(0, Long.MAX_VALUE) == Long.MAX_VALUE)
    }

    @Test
    fun persistentGenerationReservationDominatesClockAndDelayedIntent() {
        assertEquals(101L, reserveTunnelGenerationId(50L, 75L, 100L))
        assertEquals(200L, reserveTunnelGenerationId(200L, 75L, 100L))
        assertEquals(250L, reserveTunnelGenerationId(200L, 250L, 100L))
        assertEquals(251L, reserveTunnelGenerationId(200L, 250L, 250L))
    }

    @Test
    fun onlyNewestOfTwoRestartDelaysCanStart() {
        val state = TunnelLifecycleState()
        val initialEpoch = state.requestStart()
        val initial = state.reserveProcess(initialEpoch, true)!!
        val firstRestart = state.requestRestart()!!
        val secondRestart = state.requestRestart()!!

        assertFalse(state.accepts(initial))
        assertNull(state.reserveProcess(firstRestart, true))
        assertNotNull(state.reserveProcess(secondRestart, true))
    }

    @Test
    fun stopDuringRestartDelayCannotResurrectProcess() {
        val state = TunnelLifecycleState()
        val epoch = state.requestStart()
        val process = state.reserveProcess(epoch, true)!!
        assertTrue(state.processEnded(process))

        state.requestStop()

        assertNull(state.reserveProcess(epoch, true))
        assertFalse(state.isDesiredRunning())
    }

    @Test
    fun staleEofCannotDetachReplacement() {
        val state = TunnelLifecycleState()
        val firstEpoch = state.requestStart()
        val first = state.reserveProcess(firstEpoch, true)!!
        val secondEpoch = state.requestRestart()!!
        val second = state.reserveProcess(secondEpoch, true)!!

        assertFalse(state.processEnded(first))
        assertTrue(state.accepts(second))
    }

    @Test
    fun staleProcessLogsAreRejected() {
        val state = TunnelLifecycleState()
        val firstEpoch = state.requestStart()
        val first = state.reserveProcess(firstEpoch, true)!!
        val secondEpoch = state.requestRestart()!!
        val second = state.reserveProcess(secondEpoch, true)!!

        assertFalse(state.accepts(first))
        assertTrue(state.accepts(second))
    }

    @Test
    fun restartInvalidatesTheOldProcessAndIssuesANewEpoch() {
        val state = TunnelLifecycleState()
        val firstEpoch = state.requestStart()
        val first = state.reserveProcess(firstEpoch, true)!!
        val restartedEpoch = state.requestRestart()!!

        assertFalse(state.accepts(first))
        assertNull(state.reserveProcess(firstEpoch, true))
        assertNotNull(state.reserveProcess(restartedEpoch, true))
    }

    @Test
    fun processPresencePreventsParallelReservation() {
        val state = TunnelLifecycleState()
        val epoch = state.requestStart()

        assertNull(state.reserveProcess(epoch, false))
        val first = state.reserveProcess(epoch, true)!!
        assertNull(state.reserveProcess(epoch, true))
        assertTrue(state.accepts(first))
    }

    @Test
    fun tenThousandStaleDelaysCannotStartAfterStop() {
        val state = TunnelLifecycleState()
        val epochs = ArrayList<Long>(10_000)
        state.requestStart()
        repeat(10_000) {
            epochs += state.requestRestart()!!
        }
        state.requestStop()

        for (epoch in epochs) {
            assertNull(state.reserveProcess(epoch, true))
        }
    }

    @Test
    fun concurrentReservationsCreateExactlyOneProcessTicket() {
        val state = TunnelLifecycleState()
        val epoch = state.requestStart()
        val executor = Executors.newFixedThreadPool(32)
        val ready = CountDownLatch(32)
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<TunnelLifecycleTicket>()

        repeat(32) {
            executor.execute {
                ready.countDown()
                start.await()
                state.reserveProcess(epoch, true)?.let(results::add)
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(results.size == 1)
        assertTrue(state.accepts(results.single()))
    }

    @Test
    fun staleEofStormCannotDetachCurrentProcess() {
        val state = TunnelLifecycleState()
        val stale = ArrayList<TunnelLifecycleTicket>(10_000)

        repeat(10_000) {
            val epoch = if (it == 0) state.requestStart() else state.requestRestart()!!
            stale += state.reserveProcess(epoch, true)!!
        }
        val currentEpoch = state.requestRestart()!!
        val current = state.reserveProcess(currentEpoch, true)!!

        for (ticket in stale) {
            assertFalse(state.processEnded(ticket))
        }
        assertTrue(state.accepts(current))
    }
}
