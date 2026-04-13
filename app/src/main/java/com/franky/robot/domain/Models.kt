package com.franky.robot.domain

enum class ConnectionStatus { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

data class SensorData(
    val adc0: Int = 0,
    val btn: Int = 0,
    val temp: Float = 0f,
    val hum: Float = 0f
)
