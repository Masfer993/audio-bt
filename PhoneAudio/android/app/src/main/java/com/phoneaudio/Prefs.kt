package com.phoneaudio

import android.content.Context
import android.os.Build

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("phoneaudio", Context.MODE_PRIVATE)
    /** MAC выбранного сопряжённого ПК; пусто = первое устройство класса «компьютер». */
    var pcAddress: String
        get() = sp.getString("pc", "") ?: ""
        set(v) = sp.edit().putString("pc", v).apply()
    val phoneName: String get() = Build.MODEL.replace(' ', '_')
}
