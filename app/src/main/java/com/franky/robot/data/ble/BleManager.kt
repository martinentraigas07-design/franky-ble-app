package com.franky.robot.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import com.franky.robot.domain.ConnectionStatus
import com.franky.robot.domain.SensorData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private val scanner = adapter.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val CMD_UUID = UUID.fromString("abcd1234-5678-1234-5678-abcdef123456")

    private val _connection = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connection: StateFlow<ConnectionStatus> = _connection

    private val _sensor = MutableStateFlow(SensorData())
    val sensor: StateFlow<SensorData> = _sensor

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name
            if (name != null && name.contains("FRANKY")) {
                scanner.stopScan(this)
                connect(result.device)
            }
        }
    }

    fun startScan() {
        _connection.value = ConnectionStatus.SCANNING
        scanner.startScan(scanCallback)
    }

    private fun connect(device: BluetoothDevice) {
        _connection.value = ConnectionStatus.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connection.value = ConnectionStatus.CONNECTED
                g.discoverServices()
            } else {
                _connection.value = ConnectionStatus.DISCONNECTED
            }
        }
    }

    fun send(cmd: String) {
        val service = gatt?.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(CMD_UUID) ?: return
        char.value = cmd.toByteArray()
        gatt?.writeCharacteristic(char)
    }
}
