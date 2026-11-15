package com.phoneaudio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * Постоянный Bluetooth RFCOMM-канал управления к ПК: сам находит сопряжённый ПК,
 * переподключается, шлёт PING раз в 3 с. Звук идёт отдельно, по A2DP, который открывает ПК.
 */
@SuppressLint("MissingPermission")
class BtClient(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val onChange: () -> Unit,
    private val onRemoteOpen: (Boolean) -> Unit,
    private val onLost: () -> Unit,
) {
    @Volatile var connected = false; private set
    @Volatile var pcName = ""; private set

    private var job: Job? = null
    private var out: OutputStream? = null
    private val writeLock = Mutex()
    private val requestLock = Mutex()
    private val replies = Channel<String>(Channel.UNLIMITED)
    @Volatile private var lastRx = 0L

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val dev = pick()
                if (dev == null) { delay(2000); continue }
                try { session(dev) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                if (connected) { connected = false; onLost() }
                onChange()
                delay(1500)
            }
        }
    }

    fun stop() { job?.cancel(); connected = false }

    private fun adapter() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun pick(): BluetoothDevice? = try {
        val ad = adapter()
        if (ad == null || !ad.isEnabled) null else {
            val bonded = ad.bondedDevices.toList()
            val addr = prefs.pcAddress
            val d = if (addr.isNotBlank()) bonded.firstOrNull { it.address == addr }
            else bonded.firstOrNull { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }
            if (d != null) pcName = d.name ?: d.address
            d
        }
    } catch (_: SecurityException) { null }

    private suspend fun session(dev: BluetoothDevice) {
        runCatching { adapter()?.cancelDiscovery() }
        val sock = dev.createInsecureRfcommSocketToServiceRecord(Proto.SERVICE_UUID)
        try {
            sock.connect()
            out = sock.outputStream
            val reader = BufferedReader(InputStreamReader(sock.inputStream))
            while (replies.tryReceive().isSuccess) { /* drain */ }
            lastRx = SystemClock.elapsedRealtime()
            coroutineScope {
                val rd = launch(Dispatchers.IO) {
                    try {
                        while (true) {
                            val l = reader.readLine() ?: break
                            lastRx = SystemClock.elapsedRealtime()
                            when {
                                l.startsWith("PONG") -> {}
                                l.startsWith("STATE ") -> onRemoteOpen(l.trim().endsWith("open"))
                                else -> replies.trySend(l)
                            }
                        }
                    } catch (_: IOException) {}
                }
                try {
                    val hello = request("HELLO ${prefs.phoneName}", 3000)
                    if (hello?.startsWith("OK") != true) return@coroutineScope
                    connected = true
                    onRemoteOpen(hello.trim().endsWith("open"))
                    onChange()
                    while (rd.isActive) {
                        send("PING")
                        delay(3000)
                        if (SystemClock.elapsedRealtime() - lastRx > 9000) break
                    }
                } finally {
                    runCatching { sock.close() }   // разблокирует чтение
                }
            }
        } finally {
            runCatching { sock.close() }
        }
    }

    private suspend fun send(line: String) = writeLock.withLock {
        val o = out ?: throw IOException("нет соединения")
        o.write((line + "\n").toByteArray()); o.flush()
    }

    /** Отправить команду и дождаться ответа (null = таймаут/ошибка). */
    suspend fun request(cmd: String, timeoutMs: Long): String? = requestLock.withLock {
        while (replies.tryReceive().isSuccess) { /* drain */ }
        try { send(cmd) } catch (_: IOException) { return null }
        withTimeoutOrNull(timeoutMs) { replies.receive() }
    }
}
