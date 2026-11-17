package com.csqtt.client

internal fun vkProbeRetryDelayMs(failures: Int): Long = when (failures.coerceAtLeast(1)) {
    in 1..15 -> 1_000L
    16 -> 2_000L
    17 -> 3_000L
    18 -> 5_000L
    19 -> 8_000L
    else -> 15_000L
}

internal fun isVkProbeHttpResponse(code: Int): Boolean = code in 100..599
