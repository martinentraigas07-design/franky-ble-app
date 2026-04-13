package com.franky.robot.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.franky.robot.data.ble.BleManager
import com.franky.robot.domain.ConnectionStatus
import com.franky.robot.domain.SensorData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class UiEvent {
    data class Message(val text: String) : UiEvent()
    object XmlSending : UiEvent()
    object XmlSuccess : UiEvent()
    object XmlError : UiEvent()
}

class RobotViewModel(val ble: BleManager) : ViewModel() {

    val connectionStatus: StateFlow<ConnectionStatus> = ble.connectionStatus
    val sensorData: StateFlow<SensorData> = ble.sensorData
    val stateData: StateFlow<String> = ble.stateData

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents

    // ---- Movement commands ----
    fun sendFast(cmd: String) = ble.sendFast(cmd)
    fun stop() = ble.sendFast("S")
    fun eStop() = ble.sendFast("X")
    fun turbo(on: Boolean) = ble.sendFast("T:${if (on) 1 else 0}")
    fun setSpeed(pwm: Int) = ble.sendFast("SPD:$pwm")
    fun setMode(mode: String) = ble.sendFast("M:$mode")

    // ---- Blockly XML ----
    fun sendXml(xml: String) {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.XmlSending)
            val ok = ble.sendBlocklyXml(xml)
            _uiEvents.emit(if (ok) UiEvent.XmlSuccess else UiEvent.XmlError)
        }
    }

    // ---- Connection ----
    fun disconnect() = ble.disconnect()
}
