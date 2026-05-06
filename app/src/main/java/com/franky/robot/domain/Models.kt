package com.franky.robot.domain

// ── Connection lifecycle ───────────────────────────────────────────────────
enum class ConnectionStatus { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

// ── Robot operating mode ───────────────────────────────────────────────────
enum class RobotMode { MANUAL, SUMO, BLOCKED }

// ─────────────────────────────────────────────────────────────────────────
// SensorType — tipo de sensor físico
//   NONE        → ranura vacía / no conectado
//   HC_SR04     → ultrasónico, necesita 2 pines GPIO (TRIG + ECHO)
//   SHARP       → IR analógico GP2Y0A21, necesita 1 pin ADC (GPIO0/GPIO1)
//                 Se usa en modo digital: ADC > umbral → detectado
//   JS40F       → IR digital JJsumo, necesita 1 pin GPIO
//                 Salida HIGH o LOW según lógica (configurable)
//   LINE_ADC    → Sensor de línea analógico (TCRT5000 etc), pin ADC
//   LINE_DIG    → Sensor de línea digital (salida HIGH/LOW), pin GPIO
// ─────────────────────────────────────────────────────────────────────────
enum class SensorType { NONE, HC_SR04, SHARP, JS40F, LINE_ADC, LINE_DIG }

// ─────────────────────────────────────────────────────────────────────────
// SensorSlot — modelo de una ranura de sensor configurada
//
//   type        → tipo de sensor (SensorType)
//   pin1        → pin principal (ADC para Sharp/LineADC; TRIG para Sonar; DATA para JS40F/LineDig)
//   pin2        → pin secundario (ECHO para HC-SR04, -1 para el resto)
//   threshold   → umbral ADC para Sharp (0-4095); 0=sin umbral
//   invertLogic → para JS40F: false=HIGH detecta, true=LOW detecta
//
// Serialización para BLE: "type:pin1:pin2:thresh:inv"
//   Ejemplo sonar: "SONAR:6:7:0:0"
//   Ejemplo sharp: "SHARP:0:-1:2000:0"
//   Ejemplo JS40F: "JS40F:10:-1:0:0"  (HIGH = detectado)
//   Ejemplo JS40F invertido: "JS40F:10:-1:0:1"  (LOW = detectado)
// ─────────────────────────────────────────────────────────────────────────
data class SensorSlot(
    val type:        SensorType = SensorType.NONE,
    val pin1:        Int        = -1,
    val pin2:        Int        = -1,    // solo HC-SR04
    val threshold:   Int        = 2000,  // solo SHARP (ADC 0-4095)
    val invertLogic: Boolean    = false  // solo JS40F
) {
    fun isValid(): Boolean = when (type) {
        SensorType.NONE                       -> true
        SensorType.HC_SR04                    -> pin1 >= 0 && pin2 >= 0 && pin1 != pin2
        SensorType.SHARP, SensorType.LINE_ADC -> pin1 in listOf(0, 1)
        SensorType.JS40F, SensorType.LINE_DIG -> pin1 >= 0
    }

    fun toBleSlot(): String = when (type) {
        SensorType.NONE     -> "NONE:-1:-1:0:0"
        SensorType.HC_SR04  -> "SONAR:$pin1:$pin2:0:0"
        SensorType.SHARP    -> "SHARP:$pin1:-1:$threshold:0"
        SensorType.JS40F    -> "JS40F:$pin1:-1:0:${if (invertLogic) 1 else 0}"
        SensorType.LINE_ADC -> "LADC:$pin1:-1:0:0"
        SensorType.LINE_DIG -> "LDIG:$pin1:-1:0:${if (invertLogic) 1 else 0}"
    }

    fun label(): String = when (type) {
        SensorType.NONE     -> "Sin sensor"
        SensorType.HC_SR04  -> "HC-SR04 TRIG=GPIO$pin1 ECHO=GPIO$pin2"
        SensorType.SHARP    -> "Sharp GP2Y ADC${pin1} umbral=$threshold"
        SensorType.JS40F    -> "JS40F GPIO$pin1${if (invertLogic) " (INV)" else ""}"
        SensorType.LINE_ADC -> "Línea ADC GPIO$pin1"
        SensorType.LINE_DIG -> "Línea DIG GPIO$pin1"
    }
}

// ─────────────────────────────────────────────────────────────────────────
// HardwareConfig — configuración completa de hardware del robot.
//
// Pines disponibles según estado de buses:
//   I2C OFF → GPIO6, GPIO7 disponibles como GPIO
//   SPI OFF → GPIO20, GPIO21, GPIO1, GPIO10 disponibles; ADC1 disponible
//   GPIO2,3,4,5 → SIEMPRE reservados para motores
//   GPIO8 → SIEMPRE reservado para LED
//   GPIO9 → SIEMPRE reservado para botón
//
// distSlots: hasta 3 ranuras de sensor de distancia (HC_SR04, SHARP, JS40F)
// lineSlots: hasta 2 ranuras de sensor de línea (LINE_ADC, LINE_DIG)
// ─────────────────────────────────────────────────────────────────────────
data class HardwareConfig(
    val i2cEnabled: Boolean         = false,
    val spiEnabled: Boolean         = false,
    val distSlots:  List<SensorSlot> = listOf(SensorSlot()),
    val lineSlots:  List<SensorSlot> = emptyList()
) {
    // Pines reservados: motores (2,3,4,5) + LED (8) + BTN (9)
    private val reservedPins = setOf(2, 3, 4, 5, 8, 9)

    // Pines disponibles como GPIO según estado de buses
    fun availableDigitalPins(): List<Int> {
        val pins = mutableListOf<Int>()
        if (!i2cEnabled) { pins.add(6); pins.add(7) }
        if (!spiEnabled) { pins.add(10); pins.add(20); pins.add(21) }
        return pins
    }

    // Pines ADC disponibles
    fun availableAdcPins(): List<Int> = if (spiEnabled) listOf(0) else listOf(0, 1)

    // Todos los pines en uso por sensores configurados (para evitar duplicados)
    private fun usedPins(): Set<Int> {
        val used = mutableSetOf<Int>()
        (distSlots + lineSlots).forEach { slot ->
            if (slot.pin1 >= 0) used.add(slot.pin1)
            if (slot.pin2 >= 0) used.add(slot.pin2)
        }
        return used
    }

    // Validación completa — null = válido
    fun validate(): String? {
        val digPins = availableDigitalPins()
        val adcPins = availableAdcPins()
        val allUsed = mutableListOf<Int>()

        (distSlots + lineSlots).forEachIndexed { idx, slot ->
            val label = if (idx < distSlots.size) "Dist${idx+1}" else "Línea${idx-distSlots.size+1}"
            if (!slot.isValid()) return "Sensor $label: configuración incompleta o pines inválidos"

            when (slot.type) {
                SensorType.HC_SR04 -> {
                    if (slot.pin1 !in digPins) return "$label TRIG=GPIO${slot.pin1} no disponible"
                    if (slot.pin2 !in digPins) return "$label ECHO=GPIO${slot.pin2} no disponible"
                    if (slot.pin1 == slot.pin2) return "$label: TRIG y ECHO no pueden ser el mismo pin"
                }
                SensorType.SHARP, SensorType.LINE_ADC -> {
                    if (slot.pin1 !in adcPins) return "$label pin ADC${slot.pin1} no disponible (SPI activo)"
                }
                SensorType.JS40F, SensorType.LINE_DIG -> {
                    if (slot.pin1 !in digPins) return "$label GPIO${slot.pin1} no disponible"
                }
                SensorType.NONE -> {}
            }

            // Verificar duplicados de pines
            if (slot.pin1 >= 0) {
                if (slot.pin1 in allUsed) return "Pin GPIO${slot.pin1} asignado a más de un sensor"
                allUsed.add(slot.pin1)
            }
            if (slot.pin2 >= 0) {
                if (slot.pin2 in allUsed) return "Pin GPIO${slot.pin2} asignado a más de un sensor"
                allUsed.add(slot.pin2)
            }
        }

        // Validar I2C con HC-SR04 en GPIO6/7
        if (i2cEnabled) {
            (distSlots + lineSlots).forEach { slot ->
                if (slot.type == SensorType.HC_SR04 &&
                    (slot.pin1 in listOf(6,7) || slot.pin2 in listOf(6,7)))
                    return "Pines 6/7 (I2C) no pueden usarse como TRIG/ECHO"
            }
        }
        return null
    }

    // Serialización al firmware
    // Protocolo: CONFIG:I2C=N,SPI=N,DS=N,LS=N,S0=type:p1:p2:th:inv,...,L0=...
    fun toBleCmd(): String {
        val sb = StringBuilder()
        sb.append("CONFIG:")
        sb.append("I2C=${if (i2cEnabled) 1 else 0},")
        sb.append("SPI=${if (spiEnabled) 1 else 0},")
        sb.append("DS=${distSlots.size},")
        sb.append("LS=${lineSlots.size}")
        distSlots.forEachIndexed { i, s -> sb.append(",D$i=${s.toBleSlot()}") }
        lineSlots.forEachIndexed  { i, s -> sb.append(",L$i=${s.toBleSlot()}") }
        return sb.toString()
    }

    // Resumen legible para UI
    fun summary(): String {
        val parts = mutableListOf<String>()
        distSlots.filter { it.type != SensorType.NONE }.forEach { parts.add(it.label()) }
        lineSlots.filter { it.type != SensorType.NONE }.forEach { parts.add(it.label()) }
        return if (parts.isEmpty()) "Sin sensores configurados" else parts.joinToString(" | ")
    }
}

// ─────────────────────────────────────────────────────────────────────────
// SumoConfig — parámetros de estrategia y velocidades
// ─────────────────────────────────────────────────────────────────────────
data class SumoConfig(
    val strategyId: Int   = 0,
    val searchL:    Int   = 120,
    val searchR:    Int   = -80,
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
// SensorData — snapshot en vivo del firmware (JSON batch)
// ─────────────────────────────────────────────────────────────────────────
data class SensorData(
    val adc0:        Int           = 0,
    val adc1:        Int           = 0,
    val btn:         Int           = 0,
    val ledPct:      Int           = 0,
    val temp:        Float         = 0f,
    val hum:         Float         = 0f,
    val distances:   List<Int>     = listOf(-1, -1, -1),
    val lineValues:  List<Int>     = listOf(-1, -1),
    val i2cEnabled:  Boolean       = false,
    val spiEnabled:  Boolean       = false,
    val digitalPins: Map<Int, Int> = mapOf(6 to 0, 7 to 0, 9 to 0, 10 to 0),
    val i2cDevices:  List<String>  = emptyList()
)
