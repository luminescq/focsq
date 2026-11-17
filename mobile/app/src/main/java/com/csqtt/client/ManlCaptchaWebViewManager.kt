package com.csqtt.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

object ManlCaptchaWebViewManager {
    private const val TAG = "ManlCaptchaWV"
    private const val CAPTCHA_TIMEOUT_MS = CsqttConstants.Timeouts.CAPTCHA_MANUAL_TIMEOUT_MS

    val captchaMutex = Mutex()
    val pendingResult = AtomicReference<CompletableDeferred<Result<String>>?>(null)
    private val activeActivityRef = AtomicReference<WeakReference<ManlCaptchaActivity>?>(null)
    var activeActivity: ManlCaptchaActivity?
        get() = activeActivityRef.get()?.get()
        set(value) {
            activeActivityRef.set(value?.let(::WeakReference))
        }
    @Volatile
    var pendingIntentToStart: Intent? = null
    @Volatile
    var isCaptchaPending = false

    fun checkAndShowPendingCaptcha(context: Context) {
        val intent = pendingIntentToStart
        if (intent != null && activeActivity == null) {
            context.startActivity(intent)
        }
    }

    fun cancelCaptcha() {
        isCaptchaPending = false
        pendingResult.getAndSet(null)?.completeExceptionally(CancellationException("Cancelled by system"))
    }

    private const val NOTIFICATION_ID = CsqttConstants.Notifications.CAPTCHA_NOTIFICATION_ID
    private const val CHANNEL_ID = CsqttConstants.Notifications.CAPTCHA_CHANNEL_ID

    private fun showCaptchaNotification(context: Context, redirectUri: String) {
        if (MainActivity.isForeground) return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Уведомления защиты (Капча)",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

        val openIntent = Intent(context, ManlCaptchaActivity::class.java).apply {
            putExtra("redirectUri", redirectUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = Intent(context, CaptchaCancelReceiver::class.java)
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 1, cancelIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Требуется подтверждение капчи")
            .setContentText("ВК запросил проверку безопасности. Нажмите для решения.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .addAction(0, "Отменить и выключить", cancelPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun clearCaptchaNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    suspend fun solveCaptchaAsync(context: Context, redirectUri: String, sessionToken: String): String {
        if (sessionToken.isBlank()) throw IllegalArgumentException("Пустой session token captcha")
        return captchaMutex.withLock {
            isCaptchaPending = true
            val deferred = CompletableDeferred<Result<String>>()

            pendingResult.getAndSet(deferred)?.cancel()

            showCaptchaNotification(context, redirectUri)

            val intent = Intent(context, ManlCaptchaActivity::class.java).apply {
                putExtra("redirectUri", redirectUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            pendingIntentToStart = intent

            if (MainActivity.isForeground) {
                context.startActivity(intent)
            }

            try {
                withTimeout(CAPTCHA_TIMEOUT_MS) {
                    deferred.await().getOrThrow()
                }
            } finally {
                isCaptchaPending = false
                pendingResult.set(null)
                pendingIntentToStart = null
                clearCaptchaNotification(context)
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    try {
                        activeActivity?.finish()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error finishing activity: ${e.message}")
                    }
                    activeActivity = null
                }
            }
        }
    }

    fun notifyResult(result: Result<String>) {
        val deferred = pendingResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(result)
        }
    }
}
