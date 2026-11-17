package com.csqtt.client

internal object TunRecoveryPolicy {
    fun shouldReuse(
        hasInterface: Boolean,
        descriptorValid: Boolean,
        serviceDestroyed: Boolean,
        forceRebuild: Boolean,
        activeClientIp: String?,
        activeDns: String?,
        requestedClientIp: String,
        requestedDns: String,
    ): Boolean =
        hasInterface &&
            descriptorValid &&
            !serviceDestroyed &&
            !forceRebuild &&
            activeClientIp == requestedClientIp &&
            activeDns == requestedDns

    fun shouldRestoreInterface(
        permissionGranted: Boolean,
        stopRequested: Boolean,
        serviceDestroyed: Boolean,
        activeClientIp: String?,
        activeDns: String?,
    ): Boolean = false
}
