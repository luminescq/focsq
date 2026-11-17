package com.csqtt.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

// A transition can cancel its caller (startJob or the Wi-Fi watcher).
// The service owns its completion; stopping the service still cancels it.
internal suspend fun <T> runWifiAutoPauseTransition(
    serviceScope: CoroutineScope,
    transition: suspend () -> T,
): T = serviceScope.async { transition() }.await()

internal suspend fun completeWifiAutoPause(
    isRequested: () -> Boolean,
    saveRunningIntent: suspend () -> Unit,
    pauseDataPlane: suspend () -> Unit,
): Boolean {
    if (!isRequested()) return false
    saveRunningIntent()
    if (!isRequested()) return false
    pauseDataPlane()
    return isRequested()
}

internal fun tunnelPowerIsOn(running: Boolean, starting: Boolean, pausedForWifi: Boolean): Boolean =
    running || starting || pausedForWifi

internal enum class WifiAutoPauseAction {
    NONE,
    PAUSE,
    RESUME,
}

internal fun wifiAutoPauseAction(
    enabled: Boolean,
    wifiConnected: Boolean,
    desiredRunning: Boolean,
    paused: Boolean,
): WifiAutoPauseAction = when {
    !desiredRunning -> WifiAutoPauseAction.NONE
    paused && (!enabled || !wifiConnected) -> WifiAutoPauseAction.RESUME
    enabled && wifiConnected && desiredRunning && !paused -> WifiAutoPauseAction.PAUSE
    else -> WifiAutoPauseAction.NONE
}

internal fun effectiveWifiAutoPauseConnection(
    callbackWifiConnected: Boolean,
    defaultPhysicalWifiConnected: Boolean?,
): Boolean = defaultPhysicalWifiConnected ?: callbackWifiConnected

// A Wi-Fi pause tears down the data plane, but its controller must stay alive
// to watch networks/settings and resume. A normal stop tears down both.
internal inline fun dispatchTunnelStop(
    preserveWifiAutoPause: Boolean,
    stopVpn: () -> Unit,
    stopController: () -> Unit,
    notifyController: Boolean = true,
) {
    stopVpn()
    if (!preserveWifiAutoPause && notifyController) stopController()
}
