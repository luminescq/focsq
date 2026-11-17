package com.csqtt.client

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CaptchaCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TunnelManager.stop()
        ManlCaptchaWebViewManager.activeActivity?.finish()
        val notifMgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifMgr.cancel(CsqttConstants.Notifications.CAPTCHA_NOTIFICATION_ID)
    }
}
