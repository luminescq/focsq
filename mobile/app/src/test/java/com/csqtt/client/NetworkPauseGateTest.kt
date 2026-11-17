package com.csqtt.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPauseGateTest {
    @Test
    fun concurrentCallbacksCanPauseAndResumeOnlyOnce() {
        val gate = NetworkPauseGate()
        assertEquals(1, concurrentSuccesses { gate.pause() })
        assertTrue(gate.isPaused())
        assertEquals(1, concurrentSuccesses { gate.resume() })
        assertFalse(gate.isPaused())
    }

    @Test
    fun resetAndRestoreKeepTheStateDeterministic() {
        val gate = NetworkPauseGate()
        gate.restore()
        assertTrue(gate.isPaused())
        gate.reset()
        assertFalse(gate.isPaused())
        assertTrue(gate.pause())
        assertFalse(gate.pause())
    }

    private fun concurrentSuccesses(operation: () -> Boolean): Int {
        val threads = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val successes = AtomicInteger()
        val executor = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            executor.execute {
                start.await()
                if (operation()) successes.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
        return successes.get()
    }
}
