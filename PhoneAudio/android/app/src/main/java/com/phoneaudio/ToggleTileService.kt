package com.phoneaudio

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Плитка в шторке: звук телефон ⇄ ПК. Если сервис не запущен, открывает приложение, оно его стартует. */
class ToggleTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        unlockAndRun {
            val s = LinkService.instance
            if (s != null) s.toggle() else openApp()
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        val st = AppState.state.value
        t.state = if (st.audioOpen) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Звук на ПК"
        t.icon = Icon.createWithResource(this, R.drawable.ic_stat)
        t.subtitle = when {
            !st.serviceRunning -> "не запущено"
            !st.connected -> "ищу ПК"
            st.audioOpen -> "на ПК"
            else -> "на телефоне"
        }
        t.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java)
            .putExtra("grant", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(i)
    }
}
