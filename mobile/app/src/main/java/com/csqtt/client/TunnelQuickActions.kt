package com.csqtt.client

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import kotlinx.coroutines.flow.first

internal object QuickTunnelAction {
    const val START = "START"
    const val STOP = "STOP"
}

internal suspend fun buildQuickStartIntent(context: Context): Intent? {
    val store = SettingsStore(context.applicationContext)
    val source = resolveConnectionSource(store) ?: return null
    return Intent(context, TunnelService::class.java).apply {
        action = QuickTunnelAction.START
        putExtra("peer", source.peer)
        putExtra("vk_hashes", source.hashes)
        putExtra("vk_hashes_from_link", source.hashesFromLink)
        putExtra("vk_access_token", source.vkAccessToken)
        putExtra("vk_token_from_link", source.tokenFromLink)
        putExtra("secondary_vk_hash", store.secondaryVkHash.first())
        putExtra("workers_per_hash", store.workersPerHash.first())
        putExtra("port", 0)
        putExtra("sni", store.sni.first())
        putExtra("connection_password", source.password)
        putExtra("protocol", store.protocol.first())
        putExtra("vk_auth_mode", store.vkAuthMode.first())
        putExtra("captcha_mode", store.captchaMode.first())
        putExtra("captcha_solve_method", store.captchaSolveMethod.first())
        putExtra("fingerprint", store.selectedFingerprint.first())
        putExtra("obfs_mode", store.obfsMode.first())
        putExtra("turn_transport", store.turnTransport.first())
        putExtra("srtp_mode", store.srtpMode.first())
        putExtra("custom_turn_ips", store.customTurnIps.first())
    }
}

internal suspend fun toggleTunnelFromQuickAction(context: Context, openMain: () -> Unit) {
    if (TunnelManager.stopping.value) return
    if (tunnelPowerIsOn(TunnelManager.running.value, TunnelManager.starting.value, TunnelManager.autoPausedForWifi.value)) {
        context.startService(Intent(context, TunnelService::class.java).apply { action = QuickTunnelAction.STOP })
        return
    }
    if (VpnService.prepare(context) != null) {
        context.showRaisedToast("Откройте FOCSQ и выдайте VPN-разрешение", Toast.LENGTH_LONG)
        openMain()
        return
    }
    val startIntent = buildQuickStartIntent(context)
    if (startIntent == null) {
        context.showRaisedToast("Заполните настройки подключения в FOCSQ", Toast.LENGTH_LONG)
        openMain()
        return
    }
    context.startForegroundService(startIntent)
}
