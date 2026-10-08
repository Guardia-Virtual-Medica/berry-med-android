package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.protocol.VitalSigns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MonitorUiState(
    val statusLine: String = "Iniciando…",
    val berryConnected: Boolean = false,
    val thermConnected: Boolean = false,
    val relayPresent: Boolean = false,
    val pusherConnected: Boolean = false,
    val sessionActive: Boolean = false,
    val vitals: VitalSigns = VitalSigns(),
)

object MonitorState {
    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    fun update(block: (MonitorUiState) -> MonitorUiState) {
        _state.value = block(_state.value)
    }
}
