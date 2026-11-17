package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerCountPolicyTest {

    @Test
    fun everySupportedTotalRemainsAnExactMultipleOfFive() {
        val supported = (1..CsqttConstants.Tunnel.MAX_WORKERS / CsqttConstants.Tunnel.WORKERS_PER_GROUP)
            .map { it * CsqttConstants.Tunnel.WORKERS_PER_GROUP }
        for ((groups, workers) in supported.withIndex()) {
            assertEquals(workers, WorkerCountPolicy.normalize(workers))
            assertEquals(groups + 1, workers / CsqttConstants.Tunnel.WORKERS_PER_GROUP)
        }
        assertEquals(100, supported.last())
        assertEquals(100, WorkerCountPolicy.normalize(CsqttConstants.Tunnel.MAX_WORKERS))
    }

    @Test
    fun extraWorkersRaiseTheDefaultMaximum() {
        assertEquals(
            CsqttConstants.Tunnel.DEFAULT_MAX_WORKERS,
            WorkerCountPolicy.defaultMaximum(extraWorkers = false),
        )
        assertEquals(
            CsqttConstants.Tunnel.MAX_WORKERS,
            WorkerCountPolicy.defaultMaximum(extraWorkers = true),
        )
        assertEquals(
            CsqttConstants.Tunnel.DEFAULT_MAX_WORKERS,
            WorkerCountPolicy.normalize(
                Int.MAX_VALUE,
                maximum = WorkerCountPolicy.defaultMaximum(extraWorkers = false),
            ),
        )
    }

    @Test
    fun hashCapacityHasNoHiddenExtraWorkers() {
        assertEquals(30, WorkerCountPolicy.maximumForHashes(1))
        assertEquals(60, WorkerCountPolicy.maximumForHashes(2))
        assertEquals(90, WorkerCountPolicy.maximumForHashes(3))
        assertEquals(100, WorkerCountPolicy.maximumForHashes(4))
        assertEquals(100, WorkerCountPolicy.maximumForHashes(5))
        assertEquals(100, WorkerCountPolicy.maximumForHashes(6))
        assertEquals(100, WorkerCountPolicy.maximumForHashes(7))
    }

    @Test
    fun manualHashCapacityCapsProfilePower() {
        assertEquals(30, WorkerCountPolicy.maximumForProfile(CsqttConstants.VkAutoHash.MODE_MANUAL, 1))
        assertEquals(100, WorkerCountPolicy.maximumForProfile(CsqttConstants.VkAutoHash.MODE_MANUAL, 4))
        assertEquals(100, WorkerCountPolicy.maximumForProfile(CsqttConstants.VkAutoHash.MODE_MANUAL, 7))
        assertEquals(30, WorkerCountPolicy.maximumForProfile(CsqttConstants.VkAutoHash.MODE_MANUAL, 0))
    }

    @Test
    fun automaticHashModesKeepTheFullProfileRange() {
        assertEquals(
            100,
            WorkerCountPolicy.maximumForProfile(CsqttConstants.VkAutoHash.MODE_AUTO_API, 1),
        )
        assertEquals(
            100,
            WorkerCountPolicy.maximumForProfile(CsqttConstants.VkAutoHash.MODE_AUTO_JS, 1),
        )
    }

    @Test
    fun participantCapacityComesFromLinkBeforeLocalProfileState() {
        assertEquals(
            100,
            WorkerCountPolicy.maximumForSources(
                linkMode = true,
                linkHashCount = 4,
                autoHashMode = false,
                manualHashCount = 0,
            ),
        )
        assertEquals(
            100,
            WorkerCountPolicy.maximumForSources(
                linkMode = false,
                linkHashCount = 4,
                autoHashMode = false,
                manualHashCount = 1,
            ),
        )
        assertEquals(
            100,
            WorkerCountPolicy.maximumForSources(
                linkMode = false,
                linkHashCount = 0,
                autoHashMode = true,
                manualHashCount = 1,
            ),
        )
    }

    @Test
    fun invalidPersistedCountsAreBoundedAndNeverCreatePartialCredentialGroup() {
        for (requested in -1_000..1_000) {
            val normalized = WorkerCountPolicy.normalize(requested)
            assertTrue(normalized in 5..CsqttConstants.Tunnel.MAX_WORKERS)
            assertEquals(0, normalized % 5)
            when {
                requested <= 5 -> assertEquals(5, normalized)
                requested >= CsqttConstants.Tunnel.MAX_WORKERS ->
                    assertEquals(CsqttConstants.Tunnel.MAX_WORKERS, normalized)
                else -> {
                    assertTrue(normalized >= requested)
                    assertTrue(normalized < requested + CsqttConstants.Tunnel.WORKERS_PER_GROUP)
                }
            }
        }
    }

    @Test
    fun stalePersistedMaximumIsRecappedByCurrentHashCountAtRuntime() {
        assertEquals(30, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 1))
        assertEquals(60, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 2))
        assertEquals(90, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 3))
        assertEquals(100, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 4))
        assertEquals(100, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 5))
        assertEquals(100, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 6))
        assertEquals(100, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 7))
    }

    @Test
    fun automaticHashModeAllowsTheFullWorkerRange() {
        for (requested in -100..300) {
            val admitted = WorkerCountPolicy.normalize(requested)
            assertTrue(admitted in 5..CsqttConstants.Tunnel.MAX_WORKERS)
            assertEquals(0, admitted % 5)
        }
        assertEquals(100, WorkerCountPolicy.normalize(Int.MAX_VALUE))
    }

    @Test
    fun craftedIntentCountsCannotBypassHashAwareRuntimeAdmission() {
        for (requested in listOf(Int.MIN_VALUE, -1, 0, 4, 28, 109, Int.MAX_VALUE)) {
            for (hashCount in 1..CsqttConstants.Tunnel.MAX_VK_HASHES) {
                val admitted = WorkerCountPolicy.normalizeForHashes(requested, hashCount)
                assertTrue(admitted in 5..WorkerCountPolicy.maximumForHashes(hashCount))
                assertTrue(admitted == 5 || admitted % CsqttConstants.Tunnel.WORKERS_PER_GROUP == 0)
            }
        }
        assertEquals(
            30,
            WorkerCountPolicy.normalizeForHashValues(
                Int.MAX_VALUE,
                listOf("same", " same ", "same", ""),
            ),
        )
        assertEquals(
            100,
            WorkerCountPolicy.normalizeForHashValues(
                Int.MAX_VALUE,
                listOf("one", "two", "three", "four", "five", "six", "ignored-seventh"),
            ),
        )
    }

    @Test
    fun automaticCallCountScalesToSevenHashes() {
        assertEquals(1, VkAutoCallsManager.callCountForWorkers(10))
        assertEquals(1, VkAutoCallsManager.callCountForWorkers(20))
        assertEquals(1, VkAutoCallsManager.callCountForWorkers(30))
        assertEquals(2, VkAutoCallsManager.callCountForWorkers(45))
        assertEquals(2, VkAutoCallsManager.callCountForWorkers(60))
        assertEquals(4, VkAutoCallsManager.callCountForWorkers(100))
        assertEquals(CsqttConstants.VkAutoHash.AUTO_CALL_SMALL_DELAY_MS, VkAutoCallsManager.requestDelayForCallCount(1))
        assertEquals(CsqttConstants.VkAutoHash.AUTO_CALL_SMALL_DELAY_MS, VkAutoCallsManager.requestDelayForCallCount(4))
        assertEquals(CsqttConstants.VkAutoHash.AUTO_CALL_LARGE_DELAY_MS, VkAutoCallsManager.requestDelayForCallCount(5))
        assertEquals(CsqttConstants.VkAutoHash.AUTO_CALL_LARGE_DELAY_MS, VkAutoCallsManager.requestDelayForCallCount(6))
    }

    @Test
    fun automaticCallFailureCanRedistributeWholeGroupsAcrossCreatedHashes() {
        assertEquals(
            100,
            WorkerCountPolicy.normalizeForHashValues(
                Int.MAX_VALUE,
                listOf("one", "two", "three", "four", "five"),
                allowRedistribution = true,
            ),
        )
    }

    @Test
    fun manualHashesAreCappedPerHashUnlessRedistributionIsAllowed() {
        assertEquals(
            30,
            WorkerCountPolicy.normalizeForHashValues(
                30,
                listOf("hash1_at_least_16_chars", "hash2_at_least_16_chars"),
                allowRedistribution = false,
            ),
        )
        assertEquals(
            60,
            WorkerCountPolicy.normalizeForHashValues(
                100,
                listOf("hash1_at_least_16_chars", "hash2_at_least_16_chars"),
                allowRedistribution = false,
            ),
        )
        assertEquals(
            30,
            WorkerCountPolicy.normalizeForHashValues(
                50,
                listOf("single_hash_at_least_16_chars"),
                allowRedistribution = false,
            ),
        )
        assertEquals(
            100,
            WorkerCountPolicy.normalizeForHashValues(
                100,
                listOf("hash1_at_least_16_chars", "hash2_at_least_16_chars"),
                allowRedistribution = true,
            ),
        )
    }
}
