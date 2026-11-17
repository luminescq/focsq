package com.csqtt.client

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QuickToggleTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()

        stateJob?.cancel()
        stateJob = scope.launch {
            try {
                TunnelManager.running.collect { running ->
                    updateTile(running)
                }
            } catch (e: Exception) {
                Log.e("QuickToggleTile", "Error collecting running state", e)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            try {
                toggleTunnelFromQuickAction(this@QuickToggleTileService) { openMainActivity() }
            } catch (e: Exception) {
                Log.e("QuickToggleTile", "Failed to toggle tunnel via QS tile", e)
                this@QuickToggleTileService.showRaisedToast("Ошибка запуска: ${e.localizedMessage}", Toast.LENGTH_SHORT)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateTile(running: Boolean) {
        runCatching {
            qsTile?.apply {
                label = CsqttConstants.General.APP_NAME
                icon = Icon.createWithResource(this@QuickToggleTileService, R.drawable.ic_c_logo)
                state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= 29) {
                    subtitle = if (running) "Подключено" else "Отключено"
                }
                updateTile()
            }
        }.onFailure { e ->
            Log.e("QuickToggleTile", "Failed to update QS tile state", e)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openMainActivity() {
        runCatching {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    100,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { e ->
            Log.e("QuickToggleTile", "Failed to open MainActivity", e)
        }
    }
}
