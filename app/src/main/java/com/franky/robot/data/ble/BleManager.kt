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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG         = "BleManager"
        private const val MTU_REQUEST = 512
        private const val MAX_RETRY   = 3
        // Safe chunk size = MTU - 3 (ATT header) - 4 (WRITE overhead) = 505 bytes at MTU 512
        // Use 180 as conservative safe size to handle MTU negotiation failures
        private const val CHUNK_SIZE  = 180

        val UUID_SERVICE: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val UUID_CMD:     UUID = UUID.fromString("abcd1234-5678-1234-5678-abcdef123456")
        val UUID_STATUS:  UUID = UUID.fromString("abcd1234-5678-1234-5678-abcdef123457")
        val UUID_CCCD:    UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    // ── BLE infrastructure ────────────────────────────────────────────────
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val scanner   = btManager.adapter?.bluetoothLeScanner

    private var gatt:          BluetoothGatt?   = null
    private var lastDevice:    BluetoothDevice? = null
    private var retryCount     = 0
    private var isManualDisc   = false
    private var negotiatedMtu  = 23  // updated after MTU exchange

    // ── Thread-safe receive buffer ────────────────────────────────────────
    // onCharacteristicChanged runs on the BLE callback thread.
    // We use AtomicReference<String> to accumulate without locks.
    private val rxAccum = AtomicReference("")

    // ── Write queue — prevents concurrent characteristic writes ───────────
    // WRITE_TYPE_NO_RESPONSE doesn't guarantee delivery if called rapidly.
    // We serialize all writes through a Channel with a coroutine worker.
    private val writeChannel = Channel<WriteRequest>(capacity = 64)
    private var writeScope   = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private sealed class WriteRequest {
        data class Fast(val cmd: String) : WriteRequest()
        data class Reliable(val cmd: String, val result: CompletableDeferred<Boolean>) : WriteRequest()
    }

    // ACK signal for WRITE_TYPE_DEFAULT (reliable) writes
    private val writeAck = CompletableDeferred<Boolean>().also { it.complete(true) }
    private var pendingAck: CompletableDeferred<Boolean>? = null

    // ── Public flows ──────────────────────────────────────────────────────
    private val _connStatus  = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connStatus

    private val _sensorData  = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData

    private val _stateData   = MutableStateFlow("IDLE")
    val stateData: StateFlow<String> = _stateData

    // ── Scan callback ─────────────────────────────────────────────────────
    private var onFound: ((BluetoothDevice) -> Unit)? = null
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(ct: Int, r: ScanResult) {
            val n = r.device.name ?: return
            if (n.startsWith("FRANKY")) onFound?.invoke(r.device)
        }
        override fun onScanFailed(code: Int) {
            Log.e(TAG, "Scan failed: $code")
            _connStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    // ── GATT callback ─────────────────────────────────────────────────────
    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected (status=$status) — requesting MTU $MTU_REQUEST")
                    retryCount = 0
                    _connStatus.value = ConnectionStatus.CONNECTED
                    // Negotiate MTU before service discovery
                    g?.requestMtu(MTU_REQUEST)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Disconnected (status=$status)")
                    cleanup(g)
                    _connStatus.value = ConnectionStatus.DISCONNECTED
                    _stateData.value  = "IDLE"
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt?, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            Log.d(TAG, "MTU negotiated: $mtu (status=$status)")
            // Now safe to discover services
            g?.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered — enabling notifications")
                enableNotifications(g)
                startWriteWorker()
            } else {
                Log.e(TAG, "Service discovery failed: $status")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt?,
                                              c: BluetoothGattCharacteristic?) {
            val bytes = c?.value ?: return
            val chunk = String(bytes, Charsets.UTF_8)
            // Append atomically — no mutex needed for append+getAndSet pattern
            while (true) {
                val old = rxAccum.get()
                val new = old + chunk
                if (rxAccum.compareAndSet(old, new)) break
            }
            // Process complete JSON objects / lines
            processRxBuffer()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt?,
                                            c: BluetoothGattCharacteristic?,
                                            status: Int) {
            pendingAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingAck = null
        }
    }

    // ── RX buffer processing ──────────────────────────────────────────────
    // The firmware sends complete JSON objects terminated with implicit end-of-packet.
    // We detect complete JSON by balanced braces.

    private fun processRxBuffer() {
        while (true) {
            val current = rxAccum.get()
            if (current.isEmpty()) break

            // Find a complete JSON object (balanced braces)
            val start = current.indexOf('{')
            if (start < 0) { rxAccum.set(""); break }

            var depth = 0
            var end = -1
            for (i in start until current.length) {
                when (current[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            if (end < 0) break // incomplete JSON — wait for more data

            val jsonStr  = current.substring(start, end + 1)
            val remaining = current.substring(end + 1)

            if (!rxAccum.compareAndSet(current, remaining)) continue // retry if modified

            try { parseJson(jsonStr) } catch (e: Exception) {
                Log.w(TAG, "Parse error: ${e.message} on: $jsonStr")
            }
        }
    }

    private fun parseJson(json: String) {
        Log.v(TAG, "RX: $json")
        val obj = JSONObject(json)

        // ── Status batch: {"s":"IDLE","a0":...} ───────────────────────────
        if (obj.has("s")) {
            _stateData.value = obj.getString("s")
            val snr = obj.optInt("snr", -1)
            val d   = obj.optJSONArray("d")
            val dp  = if (d != null && d.length() == 4)
                listOf(d.getInt(0), d.getInt(1), d.getInt(2), d.getInt(3))
            else listOf(-1, -1, -1, -1)

            _sensorData.value = _sensorData.value.copy(
                adc0        = obj.optInt("a0", 0),
                adc1        = obj.optInt("a1", 0),
                btn         = obj.optInt("b",  0),
                ledPct      = obj.optInt("led", 0),
                sonarCm     = snr,
                i2cEnabled  = obj.optInt("i", 0) == 1,
                spiEnabled  = obj.optInt("p", 0) == 1,
                digitalPins = dp
            )
            return
        }

        // ── Event: {"evt":"..."} ───────────────────────────────────────────
        val evt = obj.optString("evt", "")
        when (evt) {
            "I2C_DEV" -> {
                val arr  = obj.optJSONArray("devs") ?: JSONArray()
                val devs = (0 until arr.length()).map { arr.getString(it) }
                _sensorData.value = _sensorData.value.copy(i2cDevices = devs)
            }
            "GPIO" -> {
                // Single pin read result — store in digitalPins if it's a known pin
                val pin = obj.optInt("pin", -1)
                val v   = obj.optInt("val", 0)
                if (pin >= 0) {
                    val idx = when (pin) { 6->0; 7->1; 9->2; 10->3; else->-1 }
                    if (idx >= 0) {
                        val dp = _sensorData.value.digitalPins.toMutableList()
                        if (dp.size == 4) dp[idx] = v
                        _sensorData.value = _sensorData.value.copy(digitalPins = dp)
                    }
                }
            }
            "I2C"  -> _sensorData.value = _sensorData.value.copy(
                i2cEnabled = obj.optInt("v",0) == 1)
            "SPI"  -> _sensorData.value = _sensorData.value.copy(
                spiEnabled = obj.optInt("v",0) == 1)
            "PROG_DONE" -> _stateData.value = "IDLE"
            "PROG_ERR"  -> Log.e(TAG, "PROG_ERR: ${obj.optString("msg")}")
        }
    }

    // ── Write worker — serializes all writes to the CMD characteristic ─────
    private fun startWriteWorker() {
        writeScope.cancel()
        writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        writeScope.launch {
            for (req in writeChannel) {
                when (req) {
                    is WriteRequest.Fast     -> doWrite(req.cmd, false)
                    is WriteRequest.Reliable -> req.result.complete(doWrite(req.cmd, true))
                }
            }
        }
    }

    private suspend fun doWrite(cmd: String, reliable: Boolean): Boolean {
        if (_connStatus.value != ConnectionStatus.CONNECTED) return false
        val ch = gatt?.getService(UUID_SERVICE)?.getCharacteristic(UUID_CMD) ?: return false
        val data = "$cmd\n".toByteArray(Charsets.UTF_8)

        return withContext(Dispatchers.IO) {
            try {
                if (reliable) {
                    val ack = CompletableDeferred<Boolean>()
                    pendingAck = ack
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = data
                    val ok = gatt?.writeCharacteristic(ch) ?: false
                    if (!ok) { pendingAck = null; return@withContext false }
                    withTimeoutOrNull(3000) { ack.await() } ?: false
                } else {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ch.value = data
                    gatt?.writeCharacteristic(ch) ?: false
                    // No-response writes — small delay to prevent flooding
                    delay(15)
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "doWrite: ${e.message}")
                false
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun startScan(cb: (BluetoothDevice) -> Unit) {
        onFound = cb
        _connStatus.value = ConnectionStatus.SCANNING
        scanner?.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCb
        )
    }

    fun stopScan() { try { scanner?.stopScan(scanCb) } catch (_: Exception) {} }

    fun connect(device: BluetoothDevice) {
        stopScan()
        lastDevice = device
        isManualDisc = false
        _connStatus.value = ConnectionStatus.CONNECTING
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        isManualDisc = true
        retryCount   = MAX_RETRY
        writeScope.cancel()
        gatt?.disconnect()
    }

    /** Enqueue a fire-and-forget command */
    fun sendFast(cmd: String) {
        if (_connStatus.value != ConnectionStatus.CONNECTED) return
        writeChannel.trySend(WriteRequest.Fast(cmd))
    }

    /** Enqueue a reliable write (waits for GATT ACK) */
    suspend fun sendReliable(cmd: String): Boolean {
        if (_connStatus.value != ConnectionStatus.CONNECTED) return false
        val result = CompletableDeferred<Boolean>()
        writeChannel.send(WriteRequest.Reliable(cmd, result))
        return result.await()
    }

    /**
     * Send a Blockly JSON program to the firmware.
     *
     * Protocol (matches firmware CmdCallbacks):
     *   Small program (fits in one BLE write): PROG:{json}\n
     *   Large program: PROG_START\n  →  PROG_DATA:{chunk}\n  ×N  →  PROG_END\n
     *
     * The firmware must implement the multi-chunk protocol (PROG_START/DATA/END).
     * safeChunk is calculated from the negotiated MTU minus BLE/ATT overhead.
     */
    suspend fun sendProgram(json: String): Boolean {
        if (json.isBlank()) return false
        val payload   = "PROG:$json"
        val safeChunk = (negotiatedMtu - 8).coerceIn(20, CHUNK_SIZE)

        // Single-packet path (most programs fit here after JSON compaction)
        if (payload.length <= safeChunk) {
            return sendReliable(payload)
        }

        // Multi-chunk path for large programs
        Log.d(TAG, "Program too large for single BLE write (${payload.length} bytes), chunking…")
        if (!sendReliable("PROG_START")) return false
        var offset = 5 // skip "PROG:" prefix, data starts at json[0]
        while (offset < json.length) {
            val end   = (offset + safeChunk - 10).coerceAtMost(json.length) // 10 = "PROG_DATA:".length
            val chunk = json.substring(offset, end)
            if (!sendReliable("PROG_DATA:$chunk")) return false
            offset = end
            delay(20)
        }
        return sendReliable("PROG_END")
    }

    // ── Notification enablement ───────────────────────────────────────────

    private fun enableNotifications(g: BluetoothGatt?) {
        val svc = g?.getService(UUID_SERVICE) ?: return
        svc.getCharacteristic(UUID_STATUS)?.let { setNotify(g, it) }
    }

    private fun setNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        c.getDescriptor(UUID_CCCD)?.let { d ->
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }
    }

    // ── Reconnect with exponential backoff ────────────────────────────────

    private fun scheduleReconnect() {
        if (isManualDisc || lastDevice == null || retryCount >= MAX_RETRY) return
        val delayMs = 1000L * (1 shl retryCount) // 1s, 2s, 4s
        retryCount++
        Log.d(TAG, "Reconnect attempt $retryCount in ${delayMs}ms")
        writeScope.launch {
            delay(delayMs)
            if (!isManualDisc) connect(lastDevice!!)
        }
    }

    private fun cleanup(g: BluetoothGatt?) {
        g?.close()
        gatt = null
        pendingAck?.complete(false)
        pendingAck = null
    }
}
