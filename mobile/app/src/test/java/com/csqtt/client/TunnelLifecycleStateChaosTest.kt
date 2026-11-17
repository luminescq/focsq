package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TunnelLifecycleStateChaosTest {
    private data class ModelTicket(val epoch: Long, val sequence: Long)

    private class Model {
        var epoch = 0L
        var sequence = 0L
        var desiredRunning = false
        var active: ModelTicket? = null

        fun start(): Long {
            epoch = next(epoch)
            desiredRunning = true
            active = null
            return epoch
        }

        fun restart(): Long? {
            if (!desiredRunning) return null
            epoch = next(epoch)
            active = null
            return epoch
        }

        fun stop(): Long {
            epoch = next(epoch)
            desiredRunning = false
            active = null
            return epoch
        }

        fun reserve(expectedEpoch: Long, processIsNull: Boolean, allowStaleEpoch: Boolean = false): ModelTicket? {
            if (
                !processIsNull ||
                !desiredRunning ||
                (!allowStaleEpoch && epoch != expectedEpoch) ||
                active != null
            ) {
                return null
            }
            sequence = next(sequence)
            return ModelTicket(expectedEpoch, sequence).also { active = it }
        }

        fun release(ticket: ModelTicket) {
            if (active == ticket) active = null
        }

        fun ended(ticket: ModelTicket): Boolean {
            if (active != ticket) return false
            active = null
            return desiredRunning && epoch == ticket.epoch
        }

        fun accepts(ticket: ModelTicket): Boolean =
            desiredRunning && epoch == ticket.epoch && active == ticket

        fun canStart(expectedEpoch: Long): Boolean =
            desiredRunning && epoch == expectedEpoch

        private fun next(value: Long): Long = if (value == Long.MAX_VALUE) 1L else value + 1L
    }

    @Test
    fun deterministicLifecycleChaosMatchesIndependentModel() {
        val firstSeed = System.getenv("CSQTT_SOAK_SEED")?.toLongOrNull() ?: 0L
        val seeds = System.getenv("CSQTT_ANDROID_LIFECYCLE_SEEDS")?.toIntOrNull()?.coerceAtLeast(1) ?: 128
        val steps = System.getenv("CSQTT_ANDROID_LIFECYCLE_STEPS")?.toIntOrNull()?.coerceAtLeast(1) ?: 10_000
        repeat(seeds) { offset ->
            val seed = firstSeed + offset
            assertTrue("lifecycle diverged at reproducible seed $seed", runTrace(seed, steps))
        }
    }

    @Test
    fun lifecycleFaultGeneratorIsReproducibleAndCoversEveryTransition() {
        val first = operationTrace(104_729L, 10_000)
        val second = operationTrace(104_729L, 10_000)
        val different = operationTrace(104_730L, 10_000)
        assertTrue(first.contentEquals(second))
        assertFalse(first.contentEquals(different))
        val covered = BooleanArray(8)
        first.forEach { covered[it] = true }
        assertTrue(covered.all { it })
    }

    @Test
    fun lifecycleOracleRejectsStaleEpochAcceptanceMutation() {
        val state = TunnelLifecycleState()
        val model = Model()
        state.requestStart()
        model.start()
        assertNull(state.reserveProcess(0L, true))
        assertNotNull(model.reserve(0L, true, allowStaleEpoch = true))
    }

    @Test
    fun concurrentRestartPauseStopStormCannotResurrectAfterFinalStop() {
        val state = TunnelLifecycleState()
        val epochs = ConcurrentLinkedQueue<Long>()
        val tickets = ConcurrentLinkedQueue<TunnelLifecycleTicket>()
        epochs.add(state.requestStart())
        val threads = 16
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        repeat(threads) { thread ->
            executor.execute {
                var random = thread.toLong() + 1
                ready.countDown()
                start.await()
                repeat(5_000) {
                    random = nextRandom(random)
                    when (((random ushr 1) % 5).toInt()) {
                        0 -> state.requestRestart()?.let(epochs::add)
                        1 -> epochs.add(state.requestStart())
                        2 -> {
                            val epoch = epochs.elementAtOrNull(((random ushr 8) % epochs.size.coerceAtLeast(1)).toInt())
                            if (epoch != null) state.reserveProcess(epoch, true)?.let(tickets::add)
                        }
                        3 -> tickets.peek()?.let(state::processEnded)
                        else -> tickets.peek()?.let(state::releaseReservation)
                    }
                }
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        state.requestStop()
        assertFalse(state.isDesiredRunning())
        epochs.forEach { assertNull(state.reserveProcess(it, true)) }
        tickets.forEach { assertFalse(state.accepts(it)) }
    }

    private fun runTrace(seed: Long, steps: Int): Boolean {
        val state = TunnelLifecycleState()
        val model = Model()
        val epochs = ArrayList<Long>(512)
        val tickets = ArrayList<Pair<TunnelLifecycleTicket, ModelTicket>>(512)
        var random = seed xor 0x6a09e667f3bcc909L
        repeat(steps) {
            random = nextRandom(random)
            when (((random ushr 1) % 8).toInt()) {
                0 -> if (!rememberEpoch(epochs, state.requestStart(), model.start())) return false
                1 -> if (!rememberNullableEpoch(epochs, state.requestRestart(), model.restart())) return false
                2 -> if (!rememberEpoch(epochs, state.requestStop(), model.stop())) return false
                3 -> {
                    val expected = selectEpoch(epochs, random)
                    val processIsNull = (random and 0x400L) == 0L
                    val actual = state.reserveProcess(expected, processIsNull)
                    val expectedTicket = model.reserve(expected, processIsNull)
                    if ((actual == null) != (expectedTicket == null)) return false
                    if (actual != null && expectedTicket != null) {
                        if (actual.epoch != expectedTicket.epoch || actual.sequence != expectedTicket.sequence) return false
                        rememberTicket(tickets, actual to expectedTicket)
                    }
                }
                4 -> {
                    val pair = selectTicket(tickets, random)
                    if (pair != null) {
                        state.releaseReservation(pair.first)
                        model.release(pair.second)
                    }
                }
                5 -> {
                    val pair = selectTicket(tickets, random)
                    if (pair != null && state.processEnded(pair.first) != model.ended(pair.second)) return false
                }
                6 -> {
                    val pair = selectTicket(tickets, random)
                    if (pair != null && state.accepts(pair.first) != model.accepts(pair.second)) return false
                }
                else -> {
                    val expected = selectEpoch(epochs, random)
                    if (state.canStart(expected) != model.canStart(expected)) return false
                }
            }
            if (state.isDesiredRunning() != model.desiredRunning) return false
        }
        return true
    }

    private fun operationTrace(seed: Long, length: Int): IntArray {
        var random = seed xor 0x6a09e667f3bcc909L
        return IntArray(length) {
            random = nextRandom(random)
            ((random ushr 1) % 8).toInt()
        }
    }

    private fun nextRandom(value: Long): Long {
        var next = value
        next = next xor (next shl 13)
        next = next xor (next ushr 7)
        next = next xor (next shl 17)
        return next
    }

    private fun rememberEpoch(epochs: MutableList<Long>, actual: Long, expected: Long): Boolean {
        if (actual != expected) return false
        remember(epochs, actual)
        return true
    }

    private fun rememberNullableEpoch(epochs: MutableList<Long>, actual: Long?, expected: Long?): Boolean {
        if (actual != expected) return false
        if (actual != null) remember(epochs, actual)
        return true
    }

    private fun remember(values: MutableList<Long>, value: Long) {
        if (values.size == 512) values.removeAt(0)
        values.add(value)
    }

    private fun rememberTicket(
        values: MutableList<Pair<TunnelLifecycleTicket, ModelTicket>>,
        value: Pair<TunnelLifecycleTicket, ModelTicket>,
    ) {
        if (values.size == 512) values.removeAt(0)
        values.add(value)
    }

    private fun selectEpoch(epochs: List<Long>, random: Long): Long =
        if (epochs.isEmpty() || (random and 0x80L) != 0L) random else epochs[((random ushr 8) % epochs.size).toInt()]

    private fun selectTicket(
        tickets: List<Pair<TunnelLifecycleTicket, ModelTicket>>,
        random: Long,
    ): Pair<TunnelLifecycleTicket, ModelTicket>? =
        tickets.getOrNull(((random ushr 8) % tickets.size.coerceAtLeast(1)).toInt())
}
