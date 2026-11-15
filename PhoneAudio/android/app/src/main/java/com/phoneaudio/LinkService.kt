package com.phoneaudio

import android.app.*
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Держит Bluetooth-канал к ПК. Переключение = команда OPEN/CLOSE: ПК открывает/закрывает
 * A2DP-соединение (сопряжение остаётся), Android сам направляет звук на ПК или на динамик.
 */
class LinkService : Service() {
    companion object {
        const val ACTION_START = "com.phoneaudio.START"
        const val ACTION_TOGGLE = "com.phoneaudio.TOGGLE"
        const val ACTION_QUIT = "com.phoneaudio.QUIT"
        const val EXTRA_AUTO = "auto"
        private const val CHANNEL = "link"
        private const val NOTIF_ID = 1
        @Volatile var instance: LinkService? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private lateinit var prefs: Prefs
    private var client: BtClient? = null
    @Volatile private var audioOpen = false
    @Volatile private var switchMs = -1
    @Volatile private var error: String? = null
    private var lastKey = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Управление звуком", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startFg()
                if (client == null) {
                    client = BtClient(this, scope, prefs,
                        onChange = { publish() },
                        onRemoteOpen = { open -> audioOpen = open; publish() },
                        onLost = { audioOpen = false }).also { it.start() }
                    publish()
                }
                if (intent.getBooleanExtra(EXTRA_AUTO, false)) {
                    scope.launch {
                        withTimeoutOrNull(8000) { while (client?.connected != true) delay(100) }
                        if (!audioOpen) toggleInternal()
                    }
                }
            }
            ACTION_TOGGLE -> if (client == null) stopSelf() else toggle()
            ACTION_QUIT -> stopSelf()
            else -> if (client == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    fun toggle() { scope.launch { toggleInternal() } }

    private suspend fun toggleInternal() = mutex.withLock {
        error = null
        val c = client
        if (c == null || !c.connected) { error = "ПК не на связи по Bluetooth"; publish(); return@withLock }
        val open = audioOpen
        val t0 = SystemClock.elapsedRealtime()
        val reply = c.request(if (open) "CLOSE" else "OPEN", if (open) 4000 else 12000)
        if (reply?.startsWith("OK") == true) {
            audioOpen = !open
            switchMs = (SystemClock.elapsedRealtime() - t0).toInt()
        } else error = "ПК: ${reply ?: "нет ответа"}"
        publish()
    }

    private fun publish() {
        val c = client ?: return
        val st = UiState(true, c.connected, c.pcName, audioOpen, switchMs, error ?: (if (!c.connected) c.lastError else null))
        AppState.state.value = st
        val key = "${st.connected}|${st.pcName}|${st.audioOpen}"
        if (key != lastKey) {
            lastKey = key
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
            TileService.requestListeningState(this, ComponentName(this, ToggleTileService::class.java))
        }
    }

    private fun startFg() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_ID, n)
    }

    private fun buildNotification(): Notification {
        val st = AppState.state.value
        val title = if (st.audioOpen) "Звук идёт на ПК" else "Звук на телефоне"
        val text = if (st.connected) "${st.pcName} · Bluetooth на связи" else "Ищу ПК по Bluetooth…"
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val toggle = PendingIntent.getService(this, 1, Intent(this, LinkService::class.java).setAction(ACTION_TOGGLE), flags)
        val quit = PendingIntent.getService(this, 2, Intent(this, LinkService::class.java).setAction(ACTION_QUIT), flags)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flags)
        val icon = Icon.createWithResource(this, R.drawable.ic_stat)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(icon, if (st.audioOpen) "На телефон" else "На ПК", toggle).build())
            .addAction(Notification.Action.Builder(icon, "Выключить", quit).build())
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0))
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    override fun onDestroy() {
        instance = null
        val c = client; client = null
        if (c != null) {
            // вернуть звук на телефон, пока канал ещё жив
            runBlocking { withTimeoutOrNull(1500) { if (audioOpen && c.connected) c.request("CLOSE", 1200) } }
            c.stop()
        }
        scope.cancel()
        AppState.state.value = UiState()
        TileService.requestListeningState(this, ComponentName(this, ToggleTileService::class.java))
        super.onDestroy()
    }
}
