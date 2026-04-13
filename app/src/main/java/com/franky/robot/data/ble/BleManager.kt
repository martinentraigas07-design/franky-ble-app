package com.franky.robot.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import com.franky.robot.domain.ConnectionStatus
import com.franky.robot.domain.SensorData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val MAX_RETRIES = 3
        private const val CHUNK_SIZE = 400

        // UUIDs — must match ESP32 firmware exactly
        val UUID_SERVICE: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val UUID_CMD: UUID    = UUID.fromString("abcd1234-5678-1234-5678-abcdef123456")
        val UUID_STATE: UUID  = UUID.fromString("abcd1234-5678-1234-5678-abcdef123457")
        val UUID_SENSOR: UUID = UUID.fromString("abcd1234-5678-1234-5678-abcdef123458")
        val UUID_CCCD: UUID   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val scanner = bluetoothManager.adapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var lastDevice: BluetoothDevice? = null
    private var isManualDisconnect = false
    private var retryCount = 0
    private var isWriting = false
    private val bleBuffer = StringBuilder()

    private var onDeviceFound: ((BluetoothDevice) -> Unit)? = null
    private var writeContinuation: ((Boolean) -> Unit)? = null

    // --- Public state flows ---
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData

    private val _stateData = MutableStateFlow("IDLE")
    val stateData: StateFlow<String> = _stateData

    // --- Scan callback ---
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (name.startsWith("FRANKY")) {
                Log.d(TAG, "Found device: $name")
                onDeviceFound?.invoke(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    // --- GATT callback ---
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected, discovering services...")
                    retryCount = 0
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    g?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=$status)")
                    g?.close()
                    gatt = null
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    _stateData.value = "IDLE"
                    if (!isManualDisconnect && lastDevice != null && retryCount < MAX_RETRIES) {
                        retryCount++
                        Log.d(TAG, "Auto-reconnect attempt $retryCount")
                        connect(lastDevice!!)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered, enabling notifications")
                enableNotifications(g)
            } else {
                Log.e(TAG, "Service discovery failed: $status")
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt?,
            c: BluetoothGattCharacteristic?
        ) {
            c?.value?.let { bytes ->
                bleBuffer.append(String(bytes))
                while (bleBuffer.contains("\n")) {
                    val idx = bleBuffer.indexOf("\n")
                    val line = bleBuffer.substring(0, idx).trim()
                    bleBuffer.delete(0, idx + 1)
                    if (line.isNotEmpty()) parseLine(line)
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt?,
            c: BluetoothGattCharacteristic?,
            status: Int
        ) {
            isWriting = false
            writeContinuation?.let { it(status == BluetoothGatt.GATT_SUCCESS) }
            writeContinuation = null
        }
    }

    // --- Public API ---

    fun startScan(onFound: (BluetoothDevice) -> Unit) {
        onDeviceFound = onFound
        _connectionStatus.value = ConnectionStatus.SCANNING
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
        Log.d(TAG, "Scan started")
    }

    fun stopScan() {
        try { scanner?.stopScan(scanCallback) } catch (e: Exception) { /* ignore */ }
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        lastDevice = device
        isManualDisconnect = false
        _connectionStatus.value = ConnectionStatus.CONNECTING
        Log.d(TAG, "Connecting to ${device.name}")
        gatt = device.connectGatt(
            context,
            retryCount > 0,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    fun disconnect() {
        isManualDisconnect = true
        retryCount = MAX_RETRIES  // prevent auto-reconnect
        gatt?.disconnect()
    }

    /** Fire-and-forget fast write (no response) — for joystick / d-pad commands */
    fun sendFast(cmd: String) {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED || isWriting) return
        try {
            val ch = gatt?.getService(UUID_SERVICE)?.getCharacteristic(UUID_CMD) ?: return
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = cmd.toByteArray()
            gatt?.writeCharacteristic(ch)
        } catch (e: Exception) {
            Log.e(TAG, "sendFast error: ${e.message}")
        }
    }

    /** Reliable write with ACK — for Blockly XML chunks */
    suspend fun sendReliable(cmd: String): Boolean = suspendCancellableCoroutine { cont ->
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
            cont.resume(false); return@suspendCancellableCoroutine
        }
        writeContinuation = { ok -> cont.resume(ok) }
        isWriting = true
        try {
            val ch = gatt?.getService(UUID_SERVICE)?.getCharacteristic(UUID_CMD)
                ?: run { isWriting = false; cont.resume(false); return@suspendCancellableCoroutine }
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = cmd.toByteArray()
            if (gatt?.writeCharacteristic(ch) != true) {
                isWriting = false
                writeContinuation = null
                cont.resume(false)
            }
        } catch (e: Exception) {
            isWriting = false
            writeContinuation = null
            cont.resume(false)
        }
    }

    suspend fun sendBlocklyXml(xml: String): Boolean {
        if (xml.isBlank()) return false
        if (!sendReliable("XML_START")) return false
        for (chunk in xml.chunked(CHUNK_SIZE)) {
            if (!sendReliable("XML:$chunk")) return false
            delay(30)
        }
        return sendReliable("XML_END")
    }

    // --- Private helpers ---

    private fun parseLine(line: String) {
        when {
            line.startsWith("STATE:") -> {
                _stateData.value = line.substringAfter(":")
            }
            line.startsWith("ADC0:") -> {
                _sensorData.value = _sensorData.value.copy(
                    adc0 = line.substringAfter(":").toIntOrNull() ?: 0
                )
            }
            line.startsWith("BTN:") -> {
                _sensorData.value = _sensorData.value.copy(
                    btn = line.substringAfter(":").toIntOrNull() ?: 0
                )
            }
            line.startsWith("TEMP:") -> {
                _sensorData.value = _sensorData.value.copy(
                    temp = line.substringAfter(":").toFloatOrNull() ?: 0f
                )
            }
            line.startsWith("HUM:") -> {
                _sensorData.value = _sensorData.value.copy(
                    hum = line.substringAfter(":").toFloatOrNull() ?: 0f
                )
            }
        }
    }

    private fun enableNotifications(g: BluetoothGatt?) {
        val service = g?.getService(UUID_SERVICE) ?: return
        setNotification(g, service.getCharacteristic(UUID_STATE))
        setNotification(g, service.getCharacteristic(UUID_SENSOR))
    }

    private fun setNotification(g: BluetoothGatt, c: BluetoothGattCharacteristic?) {
        c ?: return
        g.setCharacteristicNotification(c, true)
        val descriptor = c.getDescriptor(UUID_CCCD) ?: return
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(descriptor)
    }
}
