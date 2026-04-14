package com.franky.robot.domain

enum class ConnectionStatus { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

/**
 * Snapshot of all robot sensor data.
 * Updated by parsing the JSON batch notify from the firmware:
 *   {"s":"IDLE","a0":1234,"a1":2048,"b":1,"led":0,"snr":-1,"i":0,"p":0,"d":[0,1,1,0]}
 *
 * And JSON event notifies:
 *   {"evt":"I2C_DEV","devs":["0x3C","0x68"]}
 *   {"evt":"GPIO","pin":9,"val":1}
 */
data class SensorData(
    val adc0:       Int     = 0,
    val adc1:       Int     = 0,
    val btn:        Int     = 0,       // GPIO9, 1=pressed
    val ledPct:     Int     = 0,       // 0-100%
    val sonarCm:    Int     = -1,      // -1 = inactive
    val i2cEnabled: Boolean = false,
    val spiEnabled: Boolean = false,
    // d[0]=GPIO6(SDA), d[1]=GPIO7(SCL), d[2]=GPIO9(BTN), d[3]=GPIO10(SPI_CS)
    // -1 means pin is in use by bus
    val digitalPins: List<Int> = listOf(-1, -1, -1, -1),
    val i2cDevices:  List<String> = emptyList()
)
