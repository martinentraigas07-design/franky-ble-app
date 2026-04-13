package com.franky.robot.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.franky.robot.data.ble.BleManager

class RobotViewModel(private val ble: BleManager) : ViewModel() {

    fun send(cmd: String) = ble.send(cmd)

    fun stop() = ble.send("S")

}
