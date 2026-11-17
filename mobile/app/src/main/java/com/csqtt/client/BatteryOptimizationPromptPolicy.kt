package com.csqtt.client

internal fun shouldRequestBatteryOptimizationExemption(
    promptHandled: Boolean,
    alreadyExempt: Boolean,
): Boolean = !promptHandled && !alreadyExempt
