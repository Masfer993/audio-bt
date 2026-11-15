package com.phoneaudio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private var fromTile = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasBt()) startLink() else {
            toast("Нужно разрешение «Устройства поблизости» (Bluetooth)")
            if (fromTile) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        fromTile = intent.getBooleanExtra("grant", false)
        setContent {
            App(prefs, bonded = ::bondedList, onMain = ::onMain, onQuit = { LinkService.instance?.stopSelf() },
                onBattery = ::openBattery, onAutostart = ::openAutostart)
        }
        if (fromTile) { if (LinkService.instance == null) begin() else finish() }
    }

    private fun hasBt() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun onMain() {
        val s = LinkService.instance
        if (s == null) begin() else s.toggle()
    }

    private fun begin() {
        val need = mutableListOf<String>()
        if (!hasBt()) need += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) startLink() else permLauncher.launch(need.toTypedArray())
    }

    private fun startLink() {
        ContextCompat.startForegroundService(
            this, Intent(this, LinkService::class.java).setAction(LinkService.ACTION_START).putExtra(LinkService.EXTRA_AUTO, true)
        )
        if (fromTile) finish()
    }

    @SuppressLint("MissingPermission")
    private fun bondedList(): List<Pair<String, String>> = try {
        val ad = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        ad?.bondedDevices?.map { (it.name ?: it.address) to it.address } ?: emptyList()
    } catch (_: SecurityException) { emptyList() }

    private fun openBattery() = startSafe(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    private fun openAutostart() {
        val miui = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        if (!startSafe(miui))
            startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun startSafe(i: Intent): Boolean = try { startActivity(i); true } catch (_: Exception) { false }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

@Composable
fun App(
    prefs: Prefs, bonded: () -> List<Pair<String, String>>, onMain: () -> Unit, onQuit: () -> Unit,
    onBattery: () -> Unit, onAutostart: () -> Unit,
) {
    val st by AppState.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showSettings = true }) { Text("Настройки") }
                }
                Spacer(Modifier.weight(1f))
                val bg = if (st.audioOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                val fg = if (st.audioOpen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Box(Modifier.size(230.dp).clip(CircleShape).background(bg).clickable(onClick = onMain), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            !st.serviceRunning -> "Запустить"
                            st.audioOpen -> "Вернуть\nна телефон"
                            else -> "Отправить\nна ПК"
                        },
                        style = MaterialTheme.typography.headlineSmall, color = fg, textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(32.dp))
                Text(
                    when {
                        !st.serviceRunning -> "Выключено"
                        st.audioOpen -> "Phone → PC"
                        else -> "Звук на телефоне"
                    },
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(8.dp))
                Text(if (st.pcName.isNotEmpty()) st.pcName else "ПК не выбран")
                Text(
                    when {
                        !st.serviceRunning -> "Bluetooth-канал: —"
                        st.connected -> "Bluetooth-канал: на связи"
                        else -> "Bluetooth-канал: поиск ПК…"
                    }
                )
                Text(if (st.switchMs >= 0) "Последнее переключение: ${st.switchMs} мс" else "Последнее переключение: —")
                st.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.weight(1f))
                if (st.serviceRunning) TextButton(onClick = onQuit) { Text("Выключить сервис") }
            }
        }
        if (showSettings) SettingsDialog(prefs, bonded, onBattery, onAutostart) { showSettings = false }
    }
}

@Composable
fun SettingsDialog(
    prefs: Prefs, bonded: () -> List<Pair<String, String>>,
    onBattery: () -> Unit, onAutostart: () -> Unit, onClose: () -> Unit,
) {
    val devices = remember { bonded() }
    var selected by remember { mutableStateOf(prefs.pcAddress) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Готово") } },
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Какой ПК (из сопряжённых по Bluetooth):", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { selected = ""; prefs.pcAddress = "" }) {
                    Text(if (selected.isBlank()) "[Авто: устройство класса «компьютер»]" else "Авто: устройство класса «компьютер»")
                }
                devices.forEach { (name, addr) ->
                    TextButton(onClick = { selected = addr; prefs.pcAddress = addr }) {
                        Text(if (selected == addr) "[$name]" else name)
                    }
                }
                if (devices.isEmpty()) Text("Нет сопряжённых устройств или нет разрешения Bluetooth.", style = MaterialTheme.typography.bodySmall)
                Text("Смена ПК вступает в силу после выключения и включения сервиса.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("MIUI: чтобы система не убивала сервис", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = onAutostart) { Text("Автозапуск") }
                OutlinedButton(onClick = onBattery) { Text("Экономия батареи") }
            }
        }
    )
}
