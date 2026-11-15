package com.phoneaudio

import kotlinx.coroutines.flow.MutableStateFlow

data class UiState(
    val serviceRunning: Boolean = false,
    val connected: Boolean = false,     // канал управления с ПК
    val pcName: String = "",
    val audioOpen: Boolean = false,     // звук идёт через ПК
    val switchMs: Int = -1,             // время последнего переключения
    val error: String? = null,
)

object AppState {
    val state = MutableStateFlow(UiState())
}
