package com.franky.robot.domain

// ── Connection lifecycle ───────────────────────────────────────────────────
enum class ConnectionStatus { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

// ── Robot operating mode ───────────────────────────────────────────────────
enum class RobotMode { MANUAL, SUMO, BLOCKED }

// ── Distance sensor types ──────────────────────────────────────────────────
enum class DistSensorType { NONE, HC_SR04, SHARP, I2C_TOF }

// ─────────────────────────────────────────────────────────────────────────
// Hardware configuration — validated before sending to firmware.
//
// Pin conflict rules (enforced by validate()):
//   I2C active   → GPIO6, GPIO7 NOT available as GPIO
//   SPI active   → GPIO20, GPIO21, GPIO1, GPIO10 NOT available
//   SPI active   → ADC1 (GPIO1) NOT available → max lineCount = 1
//   GPIO2-5      → RESERVED for motors, never usable
// ─────────────────────────────────────────────────────────────────────────
data class HardwareConfig(
    val i2cEnabled:    Boolean        = false,
    val spiEnabled:    Boolean        = false,
    val distSensor:    DistSensorType = DistSensorType.NONE,
    val distCount:     Int            = 1,   // 1..3
    val lineCount:     Int            = 0,   // 0..2  (0 if not competing)
    val sonarTrig:     Int            = 20,  // GPIO for HC-SR04 trigger
    val sonarEcho:     Int            = 21   // GPIO for HC-SR04 echo
) {
    // Max available ADC channels given SPI state
    val maxAdcChannels: Int get() = if (spiEnabled) 1 else 2

    // Validation result — null means valid
    fun validate(): String? {
        if (lineCount > maxAdcChannels)
            return "Con SPI activo solo hay ${maxAdcChannels} ADC disponible(s). " +
                   "lineCount=$lineCount no permitido."
        if (lineCount < 0 || lineCount > 2)
            return "lineCount debe estar entre 0 y 2."
        if (distCount < 1 || distCount > 3)
            return "distCount debe estar entre 1 y 3."
        if (distSensor == DistSensorType.I2C_TOF && !i2cEnabled)
            return "Sensor ToF I2C requiere I2C habilitado."
        if (distSensor == DistSensorType.HC_SR04 && i2cEnabled &&
            (sonarTrig == 6 || sonarTrig == 7 || sonarEcho == 6 || sonarEcho == 7))
            return "Pines del sonar (6/7) en conflicto con I2C."
        return null // valid
    }

    // Serialize to BLE command
    fun toBleCmd(): String {
        val busI2c = if (i2cEnabled) 1 else 0
        val busSpi = if (spiEnabled) 1 else 0
        val sensor = distSensor.name
        return "CONFIG:I2C=$busI2c,SPI=$busSpi,LINE=$lineCount,DIST=$distCount,SENSOR=$sensor"
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Sumo strategy configuration
// ─────────────────────────────────────────────────────────────────────────
data class SumoConfig(
    val strategyId: Int   = 0,    // 0=aggressive, 1=defensive, 2=custom
    val searchL:    Int   = 120,  // left motor speed during search
    val searchR:    Int   = -80,  // right motor speed during search
    val attackL:    Int   = 255,
    val attackR:    Int   = 255,
    val kp:         Float = 1.2f,
    val ki:         Float = 0.0f,
    val kd:         Float = 0.4f
) {
    fun toBleCmd(): String =
        "SUMO:$strategyId,$searchL,$searchR,$attackL,$attackR," +
        "${kp.format2()},${ki.format2()},${kd.format2()}"

    private fun Float.format2() = "%.2f".format(this)
}

// ─────────────────────────────────────────────────────────────────────────
// Live sensor snapshot — updated by BleManager from firmware JSON batch:
//   {"s":"IDLE","a0":1234,"a1":2048,"b":1,"t":23.5,"h":61.2,
//    "snr":[35,-1,-1],"line":[1,0],"led":0,"i":0,"p":0,"d":[0,1,1,0]}
// ─────────────────────────────────────────────────────────────────────────
data class SensorData(
    val adc0:        Int            = 0,
    val adc1:        Int            = 0,
    val btn:         Int            = 0,         // GPIO9 (1=pressed)
    val ledPct:      Int            = 0,
    val temp:        Float          = 0f,
    val hum:         Float          = 0f,
    // Up to 3 distance readings (cm); -1 = sensor not active/present
    val distances:   List<Int>      = listOf(-1, -1, -1),
    // Up to 2 line sensor readings (0=white, 1=black); -1 = not active
    val lineValues:  List<Int>      = listOf(-1, -1),
    val i2cEnabled:  Boolean        = false,
    val spiEnabled:  Boolean        = false,
    // GPIO map: key = pin number (6,7,9,10), value = digital reading or -1 if bus-owned
    val digitalPins: Map<Int, Int>  = mapOf(6 to 0, 7 to 0, 9 to 0, 10 to 0),
    val i2cDevices:  List<String>   = emptyList()
)
