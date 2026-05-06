package com.franky.robot.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.franky.robot.data.ble.BleManager
import com.franky.robot.domain.ConnectionStatus
import com.franky.robot.domain.HardwareConfig
import com.franky.robot.domain.SensorData
import com.franky.robot.domain.SumoConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UiEvent {
    data class Toast(val msg: String)       : UiEvent()
    object ProgSending                      : UiEvent()
    object ProgSuccess                      : UiEvent()
    object ProgError                        : UiEvent()
    data class ConfigError(val msg: String) : UiEvent()
}

class RobotViewModel(val ble: BleManager) : ViewModel() {

    val connectionStatus: StateFlow<ConnectionStatus> = ble.connectionStatus
    val configResult:     StateFlow<String?>          = ble.configResult
    val sensorData:       StateFlow<SensorData>       = ble.sensorData
    val stateData:        StateFlow<String>           = ble.stateData

    private val _events     = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _events

    private val _hwConfig   = MutableStateFlow(HardwareConfig())
    val hwConfig: StateFlow<HardwareConfig> = _hwConfig.asStateFlow()

    private val _sumoConfig = MutableStateFlow(SumoConfig())
    val sumoConfig: StateFlow<SumoConfig> = _sumoConfig.asStateFlow()

    // ── Movement ──────────────────────────────────────────────────────────
    fun drive(left: Int, right: Int) =
        ble.sendDrive("DRIVE:${left.coerceIn(-255,255)},${right.coerceIn(-255,255)}")
    fun stop()  = ble.sendDrive("DRIVE:0,0")
    fun eStop() { ble.sendDrive("DRIVE:0,0"); ble.sendFast("MODE:MANUAL") }
    fun setSpeed(pwm: Int)    = ble.sendFast("SPD:${pwm.coerceIn(0,255)}")
    fun sendFast(cmd: String) = ble.sendFast(cmd)

    // ── Trim de motores — calibración de dirección recta ─────────────────
    // trimL/trimR: -100..+100. 0=sin corrección.
    // TRIM:trimL,trimR → firmware ajusta velocidad de cada motor
    fun sendMotorTrim(trimL: Int, trimR: Int) =
        ble.sendFast("TRIM:${trimL.coerceIn(-100,100)},${trimR.coerceIn(-100,100)}")

    // ── LED ───────────────────────────────────────────────────────────────
    fun ledOn()          = ble.sendFast("LED:ON")
    fun ledOff()         = ble.sendFast("LED:OFF")
    fun ledBlink()       = ble.sendFast("LED:BLINK")
    fun ledPwm(pct: Int) = ble.sendFast("LED:PWM:${pct.coerceIn(0,100)}")

    // ── GPIO ──────────────────────────────────────────────────────────────
    fun gpioOut(pin: Int, value: Int) = ble.sendFast("GPIO:$pin:${if (value!=0) 1 else 0}")
    fun gpioRead(pin: Int)            = ble.sendFast("GPIO_READ:$pin")

    // ── Sonar (Panel Industrial) ──────────────────────────────────────────
    fun sonarStart(trig: Int, echo: Int) = ble.sendFast("SONAR:$trig,$echo")
    fun sonarStop()                      = ble.sendFast("SONAR:STOP")

    // ── Buses ─────────────────────────────────────────────────────────────
    fun setI2c(on: Boolean) = ble.sendFast("I2C:${if (on) 1 else 0}")
    fun setSpi(on: Boolean) = ble.sendFast("SPI:${if (on) 1 else 0}")
    fun scanI2c()           = ble.sendFast("I2C_SCAN")

    // ── Hardware config (sensores + buses) ────────────────────────────────
    // Valida y envía la config completa al firmware.
    // Se re-envía automáticamente al reconectar BLE.
    fun applyHwConfig(cfg: HardwareConfig) {
        val err = cfg.validate()
        if (err != null) {
            viewModelScope.launch { _events.emit(UiEvent.ConfigError(err)) }
            return
        }
        _hwConfig.value = cfg
        // Primero enviar estado de buses (I2C/SPI) por separado para compatibilidad
        ble.sendFast("I2C:${if (cfg.i2cEnabled) 1 else 0}")
        ble.sendFast("SPI:${if (cfg.spiEnabled) 1 else 0}")
        // Luego enviar la config completa de sensores
        viewModelScope.launch { ble.sendReliable(cfg.toBleCmd()) }
    }

    // ── Sumo ──────────────────────────────────────────────────────────────
    fun applySumoConfig(cfg: SumoConfig) {
        _sumoConfig.value = cfg
        ble.sendFast(cfg.toBleCmd())
    }

    fun sumoStop() {
        ble.sendFast("SUMO:STOP")
        ble.sendFast("LED:OFF")
    }

    // ── Blockly program ───────────────────────────────────────────────────
    fun sendXml(xml: String) {
        viewModelScope.launch {
            _events.emit(UiEvent.ProgSending)
            val json = xmlToJson(xml)
            if (json == null) { _events.emit(UiEvent.ProgError); return@launch }
            val ok = ble.sendProgram(json)
            _events.emit(if (ok) UiEvent.ProgSuccess else UiEvent.ProgError)
        }
    }

    // ── Connection ────────────────────────────────────────────────────────
    fun disconnect() = ble.disconnect()

    // ── Auto-resync al reconectar ─────────────────────────────────────────
    init {
        viewModelScope.launch {
            connectionStatus.collect { status ->
                if (status == ConnectionStatus.CONNECTED) {
                    val cfg = _hwConfig.value
                    if (cfg != HardwareConfig()) {
                        kotlinx.coroutines.delay(800)
                        if (connectionStatus.value == ConnectionStatus.CONNECTED) {
                            ble.sendFast("I2C:${if (cfg.i2cEnabled) 1 else 0}")
                            ble.sendFast("SPI:${if (cfg.spiEnabled) 1 else 0}")
                            ble.sendReliable(cfg.toBleCmd())
                        }
                    }
                }
            }
        }
    }

    // ── XML → JSON (Blockly) ──────────────────────────────────────────────
    private fun xmlToJson(xml: String): String? = try {
        val steps = mutableListOf<String>()
        parseXmlBlocks(xml, steps)
        if (steps.isEmpty()) null
        else "{\"v\":1,\"steps\":[${steps.joinToString(",")}]}"
    } catch (_: Exception) { null }

    private fun parseXmlBlocks(xml: String, out: MutableList<String>, max: Int = 64) {
        var pos = 0
        while (out.size < max) {
            val bt = xml.indexOf("<block type=\"", pos); if (bt < 0) break
            val ts = bt + 13; val te = xml.indexOf("\"", ts); if (te < 0) break
            val type = xml.substring(ts, te); pos = te + 1
            val be = findBlockEnd(xml, bt)
            when (type) {
                "franky_adelante"      -> out.add("{\"t\":\"F\"}")
                "franky_atras"         -> out.add("{\"t\":\"B\"}")
                "franky_izquierda"     -> out.add("{\"t\":\"L\"}")
                "franky_derecha"       -> out.add("{\"t\":\"R\"}")
                "franky_stop"          -> out.add("{\"t\":\"S\"}")
                "franky_velocidad"     -> out.add("{\"t\":\"spd\",\"v\":${field(xml,bt,be,"SPD").toIntOrNull()?:200}}")
                "franky_led_on"        -> out.add("{\"t\":\"led\",\"v\":\"ON\"}")
                "franky_led_off"       -> out.add("{\"t\":\"led\",\"v\":\"OFF\"}")
                "franky_led_pwm"       -> out.add("{\"t\":\"led\",\"v\":\"PWM\",\"p\":${field(xml,bt,be,"PCT").toIntOrNull()?:50}}")
                "franky_esperar"       -> out.add("{\"t\":\"wait\",\"ms\":${field(xml,bt,be,"MS").toLongOrNull()?:500}}")
                "franky_gpio_out"      -> out.add("{\"t\":\"gpio\",\"pin\":${field(xml,bt,be,"PIN").toIntOrNull()?:9},\"val\":${field(xml,bt,be,"VAL").toIntOrNull()?:0}}")
                "franky_i2c_habilitar" -> out.add("{\"t\":\"i2c\",\"v\":1}")
                "franky_spi_habilitar" -> out.add("{\"t\":\"spi\",\"v\":1}")
                "controls_repeat_ext"  -> {
                    val n = (field(xml,bt,be,"TIMES").toIntOrNull()?:1).coerceIn(1,20)
                    val inner = extractStatement(if(be>0) xml.substring(bt,be+8) else xml.substring(bt), "DO")
                    if (inner.isNotEmpty()) repeat(n) { if(out.size<max) parseXmlBlocks(inner,out,max) }
                }
            }
            pos = if (be > 0) be + 8 else pos
        }
    }

    private fun findBlockEnd(xml: String, from: Int): Int {
        var depth = 0; var i = from
        while (i < xml.length) {
            when {
                xml.startsWith("<block", i) && (xml.getOrNull(i+6).let { it==' '||it=='>' }) -> depth++
                xml.startsWith("</block>", i) -> { depth--; if(depth==0) return i }
            }; i++
        }; return -1
    }

    private fun field(xml: String, start: Int, end: Int, key: String): String {
        val sub = if(end>0) xml.substring(start,end+8) else xml.substring(start)
        val pat = "<field name=\"$key\">"; val fs = sub.indexOf(pat); if(fs<0) return ""
        val vs = fs+pat.length; val ve = sub.indexOf("</field>",vs)
        return if(ve>=0) sub.substring(vs,ve).trim() else ""
    }

    private fun extractStatement(xml: String, key: String): String {
        val pat = "<statement name=\"$key\">"; val s = xml.indexOf(pat); if(s<0) return ""
        val c = xml.substring(s+pat.length); val e = c.indexOf("</statement>")
        return if(e>=0) c.substring(0,e) else c
    }
}
