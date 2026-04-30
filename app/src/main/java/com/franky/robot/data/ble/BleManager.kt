package com.franky.robot.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import com.franky.robot.domain.ConnectionStatus
import com.franky.robot.domain.SensorData
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG         = "BleManager"
        private const val MTU_REQUEST = 512
        private const val MAX_RETRY   = 3
        private const val CHUNK_SIZE  = 180

        val UUID_SERVICE: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val UUID_CMD:     UUID = UUID.fromString("abcd1234-5678-1234-5678-abcdef123456")
        val UUID_STATUS:  UUID = UUID.fromString("abcd1234-5678-1234-5678-abcdef123457")
        val UUID_CCCD:    UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val btMgr   = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val scanner = btMgr.adapter?.bluetoothLeScanner

    private var gatt:         BluetoothGatt?   = null
    private var lastDevice:   BluetoothDevice? = null
    private var retryCount    = 0
    private var isManualDisc  = false
    private var negotiatedMtu = 23

    private val rxAccum = AtomicReference("")

 //   private val writeCh       = Channel<WriteRequest>(capacity = 128)  // increased from 64
 private val driveChannel = Channel<String>(capacity = Channel.CONFLATED)
private val cmdChannel   = Channel<WriteRequest>(capacity = 128)
    private var writeScope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Dedicated scope for reconnection — never cancelled so retry delays survive
    private val reconnectScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingAck: CompletableDeferred<Boolean>? = null

    private sealed class WriteRequest {
        data class Fast(val cmd: String)                                          : WriteRequest()
        data class Reliable(val cmd: String, val ack: CompletableDeferred<Boolean>) : WriteRequest()
    }

    // ── Public flows ──────────────────────────────────────────────────────
    private val _conn   = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _conn

    private val _sensor = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensor

    private val _state  = MutableStateFlow("IDLE")
    val stateData: StateFlow<String> = _state

    // Config ACK event — emitted when firmware replies CFG_OK or CFG_ERR
    private val _configResult = MutableStateFlow<String?>(null)
    val configResult: StateFlow<String?> = _configResult

    // ── Scan ──────────────────────────────────────────────────────────────
    private var onFound: ((BluetoothDevice) -> Unit)? = null

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(ct: Int, r: ScanResult) {
            val n = r.device.name ?: return
            if (n.startsWith("FRANKY")) onFound?.invoke(r.device)
        }
        override fun onScanFailed(code: Int) {
            Log.e(TAG, "Scan failed: $code")
            _conn.value = ConnectionStatus.DISCONNECTED
        }
    }

    // ── GATT callbacks ────────────────────────────────────────────────────
    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    retryCount = 0
                    _conn.value = ConnectionStatus.CONNECTED
                    g?.requestMtu(MTU_REQUEST)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGatt(g)
                    _conn.value  = ConnectionStatus.DISCONNECTED
                    _state.value = "IDLE"
                    // Reset sensor data so UI doesn't display stale values from
                    // the previous session while disconnected / reconnecting
                    _sensor.value = SensorData()
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt?, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            g?.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableNotify(g)
                startWriteWorker()
                // Request immediate state snapshot — don't wait for 500ms batch timer
                writeScope.launch {
				sendFast("GET:STATE")
			}
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt?, c: BluetoothGattCharacteristic?
        ) {
            val chunk = c?.value?.let { String(it, Charsets.UTF_8) } ?: return
            while (true) {
                val old = rxAccum.get()
                if (rxAccum.compareAndSet(old, old + chunk)) break
            }
            drainRxBuffer()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt?, c: BluetoothGattCharacteristic?, status: Int
        ) {
            pendingAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingAck = null
        }
    }

    // ── RX drain — JSON brace-balanced extraction ─────────────────────────
    private fun drainRxBuffer() {
        while (true) {
            val buf   = rxAccum.get()
            val start = buf.indexOf('{')
            if (start < 0) { rxAccum.set(""); return }

            var depth = 0; var end = -1
            for (i in start until buf.length) {
                when (buf[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            if (end < 0) return

            val json      = buf.substring(start, end + 1)
            val remaining = buf.substring(end + 1)
            if (!rxAccum.compareAndSet(buf, remaining)) continue

            try { handleJson(json) } catch (e: Exception) {
                Log.w(TAG, "JSON: ${e.message}")
            }
        }
    }

    // ── JSON handler ──────────────────────────────────────────────────────
    private fun handleJson(raw: String) {
        Log.v(TAG, "RX: $raw")
        val o = JSONObject(raw)

        if (o.has("s")) {
            _state.value = o.getString("s")

            // distances: firmware sends "snr":[cm1,cm2,cm3]
            val snrArr = o.optJSONArray("snr")
            val distances = if (snrArr != null)
                (0 until snrArr.length()).map { snrArr.getInt(it) }
            else List(3) { -1 }

            // line sensors: firmware sends "line":[v1,v2]
            val lineArr = o.optJSONArray("line")
            val lineValues = if (lineArr != null)
                (0 until lineArr.length()).map { lineArr.getInt(it) }
            else listOf(-1, -1)

            val dArr  = o.optJSONArray("d")
            val dPins = if (dArr != null && dArr.length() == 4)
                mapOf(6 to dArr.getInt(0), 7 to dArr.getInt(1),
                      9 to dArr.getInt(2), 10 to dArr.getInt(3))
            else _sensor.value.digitalPins

            _sensor.value = _sensor.value.copy(
                adc0        = o.optInt("a0", 0),
                adc1        = o.optInt("a1", 0),
                btn         = o.optInt("b",  0),
                ledPct      = o.optInt("led", 0),
                temp        = o.optDouble("t", 0.0).toFloat(),
                hum         = o.optDouble("h", 0.0).toFloat(),
                distances   = distances,
                lineValues  = lineValues,
                i2cEnabled  = o.optInt("i", 0) == 1,
                spiEnabled  = o.optInt("p", 0) == 1,
                digitalPins = dPins
            )
            return
        }

        when (o.optString("evt")) {
            "CFG_OK"    -> _configResult.value = "OK"
            "CFG_ERR"   -> _configResult.value = "ERR:${o.optString("msg")}"
            "I2C_DEV"   -> {
                val arr = o.optJSONArray("devs") ?: JSONArray()
                _sensor.value = _sensor.value.copy(
                    i2cDevices = (0 until arr.length()).map { arr.getString(it) })
            }
            "GPIO"      -> {
                val pin = o.optInt("pin", -1); val v = o.optInt("val", 0)
                if (pin >= 0) {
                    val m = _sensor.value.digitalPins.toMutableMap()
                    m[pin] = v
                    _sensor.value = _sensor.value.copy(digitalPins = m)
                }
            }
            "I2C"       -> _sensor.value = _sensor.value.copy(i2cEnabled = o.optInt("v",0)==1)
            "SPI"       -> _sensor.value = _sensor.value.copy(spiEnabled  = o.optInt("v",0)==1)
            "PROG_DONE" -> _state.value = "IDLE"
            "PROG_ERR"  -> Log.e(TAG, "PROG_ERR: ${o.optString("msg")}")
        }
    }

    // ── Write worker ──────────────────────────────────────────────────────
    // [FIX-CRÍTICO] El worker original usaba cmdChannel.receive() bloqueante
    // después de drenar driveChannel. Si no llegaba ningún cmd normal, el
    // worker quedaba bloqueado indefinidamente y los comandos DRIVE nunca
    // se transmitían. Corregido con select{} que espera en ambos canales
    // simultáneamente: el primero en tener datos gana, sin bloquear al otro.
    private fun startWriteWorker() {
        writeScope.cancel()
        writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        writeScope.launch {
            while (isActive) {
                select {
                    // driveChannel es CONFLATED: solo retiene el último comando.
                    // Esto es correcto para joystick/D-PAD: queremos el estado
                    // más reciente, no una cola de movimientos obsoletos.
                    driveChannel.onReceive { cmd ->
                        performWrite(cmd, false)
                    }
                    // cmdChannel: cola de 128 — procesa en orden FIFO.
                    cmdChannel.onReceive { req ->
                        when (req) {
                            is WriteRequest.Fast     -> performWrite(req.cmd, false)
                            is WriteRequest.Reliable -> req.ack.complete(performWrite(req.cmd, true))
                        }
                    }
                }
            }
        }
    }

    private suspend fun performWrite(cmd: String, reliable: Boolean): Boolean {
        if (_conn.value != ConnectionStatus.CONNECTED) return false
        val ch   = gatt?.getService(UUID_SERVICE)?.getCharacteristic(UUID_CMD) ?: return false
        val data = "$cmd\n".toByteArray(Charsets.UTF_8)
        return withContext(Dispatchers.IO) {
            try {
                if (reliable) {
                    val ack = CompletableDeferred<Boolean>()
                    pendingAck = ack
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = data
                    if (gatt?.writeCharacteristic(ch) != true) { pendingAck = null; return@withContext false }
                    withTimeoutOrNull(3_000) { ack.await() } ?: false
                } else {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ch.value = data
                    gatt?.writeCharacteristic(ch) ?: false
                    delay(15)
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "write: ${e.message}"); pendingAck = null; false
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun startScan(cb: (BluetoothDevice) -> Unit) {
        onFound = cb; _conn.value = ConnectionStatus.SCANNING
        scanner?.startScan(null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCb)
    }

    fun stopScan() { try { scanner?.stopScan(scanCb) } catch (_: Exception) {} }

    fun connect(device: BluetoothDevice) {
        stopScan(); lastDevice = device; isManualDisc = false
        _conn.value = ConnectionStatus.CONNECTING
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        isManualDisc = true; retryCount = MAX_RETRY
        writeScope.cancel(); gatt?.disconnect()
    }

    fun sendFast(cmd: String) {
        if (_conn.value != ConnectionStatus.CONNECTED) return
        val result = if (cmd.startsWith("DRIVE:") || cmd == "F" || cmd == "B" ||
    cmd == "L" || cmd == "R" || cmd == "FR" || cmd == "FL" ||
    cmd == "BR" || cmd == "BL") {

    driveChannel.trySend(cmd)

} else {
    cmdChannel.trySend(WriteRequest.Fast(cmd))
}

if (!result.isSuccess) {
    Log.w(TAG, "Command dropped: $cmd")
}
    }
	fun sendDrive(cmd: String) {
    if (_conn.value != ConnectionStatus.CONNECTED) return

    val result = driveChannel.trySend(cmd)
    if (!result.isSuccess) {
        Log.w(TAG, "Drive dropped: $cmd")
    }
}

    suspend fun sendReliable(cmd: String): Boolean {
        if (_conn.value != ConnectionStatus.CONNECTED) return false
        val ack = CompletableDeferred<Boolean>()
        cmdChannel.send(WriteRequest.Reliable(cmd, ack))
        return ack.await()
    }

    suspend fun sendProgram(json: String): Boolean {
        if (json.isBlank()) return false
        val payload   = "PROG:$json"
        val safeChunk = (negotiatedMtu - 8).coerceIn(20, CHUNK_SIZE)
        if (payload.length <= safeChunk) return sendReliable(payload)
        if (!sendReliable("PROG_START")) return false
        val prefix = "PROG_DATA:"; val chunkData = safeChunk - prefix.length
        var offset = 0
        while (offset < json.length) {
            val end = (offset + chunkData).coerceAtMost(json.length)
            if (!sendReliable("$prefix${json.substring(offset, end)}")) return false
            offset = end; delay(20)
        }
        return sendReliable("PROG_END")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (isManualDisc || lastDevice == null || retryCount >= MAX_RETRY) return
        val ms = 1_000L * (1 shl retryCount); retryCount++
        reconnectScope.launch { delay(ms); if (!isManualDisc) connect(lastDevice!!) }
    }

    private fun enableNotify(g: BluetoothGatt?) {
        g?.getService(UUID_SERVICE)?.getCharacteristic(UUID_STATUS)?.let { c ->
            g.setCharacteristicNotification(c, true)
            c.getDescriptor(UUID_CCCD)?.let { d ->
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        }
    }

    private fun closeGatt(g: BluetoothGatt?) {
        g?.close(); gatt = null
        pendingAck?.complete(false); pendingAck = null
    }
}
