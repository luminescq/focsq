package com.csqtt.client

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VkCallPoliciesTest {
    @Test
    fun transientFailuresSucceedOnTheThirdAttempt() = runBlocking {
        var attempts = 0
        val result = runVkCallAttempts {
            attempts++
            if (attempts == 3) VkApiResult.Ok("hash") else VkApiResult.Failed("temporary")
        }
        assertEquals(3, attempts)
        assertEquals("hash", (result as VkApiResult.Ok).value)
    }

    @Test
    fun permanentFailureStopsAfterThreeAttempts() = runBlocking {
        var attempts = 0
        val result = runVkCallAttempts<String> {
            attempts++
            VkApiResult.ApiError(6, "too many requests")
        }
        assertEquals(3, attempts)
        assertTrue(result is VkApiResult.ApiError)
    }

    @Test
    fun invalidTokenIsNeverRetried() = runBlocking {
        var attempts = 0
        val result = runVkCallAttempts<String> {
            attempts++
            VkApiResult.ApiError(5, "authorization failed")
        }
        assertEquals(1, attempts)
        assertTrue(result is VkApiResult.ApiError)
    }

    @Test
    fun requestStartsRespectTheConfiguredInterval() = runBlocking {
        var now = 0L
        val starts = ArrayList<Long>()
        val pacer = VkRequestPacer(202L, { now }, { wait -> now += wait })
        repeat(18) {
            pacer.awaitRequestSlot()
            starts.add(now)
            now += if (it % 2 == 0) 20L else 350L
        }
        assertTrue(starts.zipWithNext().all { (first, second) -> second - first >= 202L })
    }
}
