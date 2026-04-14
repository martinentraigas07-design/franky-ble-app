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
import org.json.JSONArray
import org.json.JSONObject

// ── UI Events (consumed once by the UI layer) ─────────────────────────────
sealed class UiEvent {
    data class Message(val text: String) : UiEvent()
    object ProgSending : UiEvent()
    object ProgSuccess : UiEvent()
    object ProgError   : UiEvent()
}

// Aliases used by BlocklyFragment (keeps existing call sites compiling)
typealias XmlSending = UiEvent.ProgSending
typealias XmlSuccess = UiEvent.ProgSuccess
typealias XmlError   = UiEvent.ProgError

class RobotViewModel(val ble: BleManager) : ViewModel() {

    // ── Exposed state ─────────────────────────────────────────────────────
    val connectionStatus: StateFlow<ConnectionStatus> = ble.connectionStatus
    val sensorData:       StateFlow<SensorData>       = ble.sensorData
    val stateData:        StateFlow<String>            = ble.stateData

    private val _events = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _events

    // ── Movement ──────────────────────────────────────────────────────────
    fun sendFast(cmd: String)      = ble.sendFast(cmd)
    fun stop()                     = ble.sendFast("S")
    fun eStop() {
        ble.sendFast("X")
        ble.sendFast("PROG:STOP")
    }
    fun turbo(on: Boolean)         = ble.sendFast("T:${if (on) 1 else 0}")
    fun setSpeed(pwm: Int)         = ble.sendFast("SPD:$pwm")

    // ── LED (GPIO8, logic-inverted) ───────────────────────────────────────
    fun ledOn()              = ble.sendFast("LED:ON")
    fun ledOff()             = ble.sendFast("LED:OFF")
    fun ledBlink()           = ble.sendFast("LED:BLINK")
    fun ledPwm(pct: Int)     = ble.sendFast("LED:PWM:${pct.coerceIn(0, 100)}")

    // ── GPIO ──────────────────────────────────────────────────────────────
    fun gpioOut(pin: Int, value: Int) =
        ble.sendFast("GPIO:$pin:${if (value != 0) 1 else 0}")
    fun gpioRead(pin: Int) = ble.sendFast("GPIO_READ:$pin")

    // ── Sonar ─────────────────────────────────────────────────────────────
    fun sonarStart(trig: Int, echo: Int) = ble.sendFast("SONAR:START:$trig:$echo")
    fun sonarStop()                      = ble.sendFast("SONAR:STOP")

    // ── Buses ─────────────────────────────────────────────────────────────
    fun setI2c(enabled: Boolean)  = ble.sendFast("I2C:${if (enabled) 1 else 0}")
    fun setSpi(enabled: Boolean)  = ble.sendFast("SPI:${if (enabled) 1 else 0}")
    fun scanI2c()                 = ble.sendFast("I2C_SCAN")

    // ── Blockly program ───────────────────────────────────────────────────
    // BlocklyFragment calls sendXml() with the XML string from the WebView.
    // We convert it to the compact JSON format the firmware expects, then send.
    fun sendXml(xml: String) {
        viewModelScope.launch {
            _events.emit(UiEvent.ProgSending)
            val json = xmlToJson(xml)
            if (json == null) {
                _events.emit(UiEvent.ProgError)
                return@launch
            }
            val ok = ble.sendProgram(json)
            _events.emit(if (ok) UiEvent.ProgSuccess else UiEvent.ProgError)
        }
    }

    // ── Connection ────────────────────────────────────────────────────────
    fun disconnect() = ble.disconnect()

    // ── XML → JSON conversion ─────────────────────────────────────────────
    // Converts Blockly XML workspace to the compact JSON format the firmware parses:
    // {"v":1,"steps":[{"t":"F"},{"t":"wait","ms":500}, ...]}
    //
    // Supported block types:
    //   franky_adelante → {t:F}      franky_atras → {t:B}
    //   franky_izquierda → {t:L}     franky_derecha → {t:R}
    //   franky_stop → {t:S}
    //   franky_velocidad → {t:spd, v:N}
    //   franky_led_on → {t:led,v:ON}  franky_led_off → {t:led,v:OFF}
    //   franky_esperar → {t:wait, ms:N}
    //   franky_gpio_out → {t:gpio, pin:N, val:N}
    //   controls_repeat_ext → unrolled N times (max 20 iterations)
    //   controls_whileUntil → not supported (requires runtime sensor data)
    //
    // This is a flat sequential unrolling — no runtime branching on the firmware.
    // Conditional blocks (if/while) are excluded because the firmware executes
    // a pre-compiled step list without a sensor feedback loop at parse time.
    private fun xmlToJson(xml: String): String? {
        return try {
            val steps = JSONArray()
            parseBlocksToSteps(xml, steps)
            if (steps.length() == 0) return null
            JSONObject().apply {
                put("v", 1)
                put("steps", steps)
            }.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun parseBlocksToSteps(xml: String, steps: JSONArray, maxSteps: Int = 128) {
        // Walk through all <block type="..."> elements in document order
        var pos = 0
        var depth = 0 // track nesting to handle next blocks correctly
        while (steps.length() < maxSteps) {
            val blockStart = xml.indexOf("<block type=\"", pos)
            if (blockStart < 0) break

            val typeStart = blockStart + 13
            val typeEnd   = xml.indexOf("\"", typeStart)
            if (typeEnd < 0) break
            val type = xml.substring(typeStart, typeEnd)

            // Find the closing > of the opening tag to know where content starts
            val tagClose = xml.indexOf(">", typeEnd)
            if (tagClose < 0) break
            pos = tagClose + 1

            // Find matching </block> for this block
            val blockEnd = findBlockEnd(xml, blockStart)
            val blockContent = if (blockEnd > 0) xml.substring(tagClose + 1, blockEnd) else ""

            when (type) {
                "franky_adelante"  -> steps.put(JSONObject().put("t", "F"))
                "franky_atras"     -> steps.put(JSONObject().put("t", "B"))
                "franky_izquierda" -> steps.put(JSONObject().put("t", "L"))
                "franky_derecha"   -> steps.put(JSONObject().put("t", "R"))
                "franky_stop"      -> steps.put(JSONObject().put("t", "S"))
                "franky_velocidad" -> {
                    val spd = extractField(xml, blockStart, blockEnd, "SPD")
                    steps.put(JSONObject().apply { put("t", "spd"); put("v", spd.toIntOrNull() ?: 200) })
                }
                "franky_led_on"    -> steps.put(JSONObject().apply { put("t", "led"); put("v", "ON") })
                "franky_led_off"   -> steps.put(JSONObject().apply { put("t", "led"); put("v", "OFF") })
                "franky_esperar"   -> {
                    val ms = extractField(xml, blockStart, blockEnd, "MS")
                    steps.put(JSONObject().apply { put("t", "wait"); put("ms", ms.toLongOrNull() ?: 500L) })
                }
                "franky_gpio_out"  -> {
                    val pin = extractField(xml, blockStart, blockEnd, "PIN")
                    val value = extractField(xml, blockStart, blockEnd, "VAL")
                    steps.put(JSONObject().apply {
                        put("t", "gpio")
                        put("pin", pin.toIntOrNull() ?: 9)
                        put("val", value.toIntOrNull() ?: 0)
                    })
                }
                "controls_repeat_ext" -> {
                    // Unroll repeat loop up to 20 iterations
                    val timesField = extractField(xml, blockStart, blockEnd, "TIMES")
                    val n = (timesField.toIntOrNull() ?: 1).coerceIn(1, 20)
                    // Extract the STATEMENT DO block content
                    val doBody = extractStatement(blockContent, "DO")
                    if (doBody.isNotEmpty()) {
                        repeat(n) {
                            if (steps.length() < maxSteps) parseBlocksToSteps(doBody, steps, maxSteps)
                        }
                    }
                }
                // Skip unsupported blocks (controls_if, controls_whileUntil, etc.)
                // They require runtime sensor values not available at compile time
            }
            pos = if (blockEnd > 0) blockEnd + 8 else pos // skip past </block>
        }
    }

    // Find the matching </block> for the <block> starting at startIdx
    private fun findBlockEnd(xml: String, startIdx: Int): Int {
        var depth = 0
        var i = startIdx
        while (i < xml.length) {
            when {
                xml.startsWith("<block", i) && (xml[i + 6] == ' ' || xml[i + 6] == '>') -> depth++
                xml.startsWith("</block>", i) -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    // Extract <field name="KEY">VALUE</field> within [start, end] range
    private fun extractField(xml: String, start: Int, end: Int, key: String): String {
        val safe = if (end > 0) xml.substring(start, end + 8) else xml.substring(start)
        val pattern = "<field name=\"$key\">"
        val fs = safe.indexOf(pattern)
        if (fs < 0) return ""
        val vs = fs + pattern.length
        val ve = safe.indexOf("</field>", vs)
        return if (ve >= 0) safe.substring(vs, ve).trim() else ""
    }

    // Extract <statement name="KEY">...</statement> content
    private fun extractStatement(xml: String, key: String): String {
        val pattern = "<statement name=\"$key\">"
        val start = xml.indexOf(pattern)
        if (start < 0) return ""
        val content = xml.substring(start + pattern.length)
        val end = content.indexOf("</statement>")
        return if (end >= 0) content.substring(0, end) else content
    }
}
