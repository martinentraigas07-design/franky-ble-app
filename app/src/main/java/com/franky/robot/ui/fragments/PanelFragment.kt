package com.franky.robot.ui.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.franky.robot.MainActivity
import com.franky.robot.R
import com.franky.robot.databinding.FragmentPanelBinding
import com.franky.robot.databinding.ItemAdcRowBinding
import com.franky.robot.domain.SensorSlot
import com.franky.robot.domain.SensorType
import com.franky.robot.ui.viewmodel.RobotViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class PanelFragment : Fragment() {

    private var _b: FragmentPanelBinding? = null
    private val b get() = _b!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    private lateinit var adc0: ItemAdcRowBinding
    private lateinit var adc1: ItemAdcRowBinding

    // ── Sonar — único estado local permitido: control operacional de UI
    // El sonar en Panel Industrial es una sesión de prueba, no config permanente.
    // No forma parte de HardwareConfig porque no es un sensor de combate;
    // es una herramienta de diagnóstico ad-hoc (pin configurable en tiempo real).
    private var sonarActive = false

    // LED blink — animación local de UI, no estado de sistema
    private var blinkHandler: Handler? = null
    private var blinkOn = false

    // ── Parallel arrays para spinners Sonar — inmutables, solo lectura
    private val SONAR_PIN_VALUES  = intArrayOf(20, 21,  6,  7, 10)
    private val SONAR_PIN_LABELS  = arrayOf("GPIO20","GPIO21","GPIO6","GPIO7","GPIO10")
    private val OUTPUT_PIN_VALUES = intArrayOf( 9,  6,  7, 10, 20, 21)
    private val OUTPUT_PIN_LABELS = arrayOf("GPIO9","GPIO6","GPIO7","GPIO10","GPIO20","GPIO21")

    // ── Sharp: pin y umbral activos derivados de vm.hwConfig (fuente de verdad)
    // Estos valores se leen del HardwareConfig configurado en ConfigFragment.
    // El Panel NUNCA modifica estos valores — solo los usa para visualización.
    private var activeSharpPin: Int = 0        // 0=ADC0, 1=ADC1
    private var activeSharpThreshold: Int = 0  // 0=sin sensor configurado

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentPanelBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adc0 = b.rowAdc0
        adc1 = b.rowAdc1

        b.toolbar.tbSubtitle.text = "Panel Industrial"
        b.toolbar.tbBadge.text    = "PANEL"

        setupAdcLabels()
        setupSonar()
        setupLed()
        setupGpioOutputs()
        setupBuses()
        setupSpeedSlider()

        b.btnPanelEStop.setOnClickListener { vm.eStop() }
        b.btnPanelBack.setOnClickListener  { findNavController().popBackStack() }

        observeHwConfig()
        observeState()
        observeSensors()
    }

    // ─────────────────────────────────────────────────────────────────────
    // ADC labels
    // ─────────────────────────────────────────────────────────────────────

    private fun setupAdcLabels() {
        adc0.tvAdcBadge.text = "ADC0"
        adc0.tvAdcLabel.text = "GPIO0"
        adc1.tvAdcBadge.text = "ADC1"
        adc1.tvAdcLabel.text = "GPIO1"
    }

    // ─────────────────────────────────────────────────────────────────────
    // HardwareConfig observer — única fuente de verdad para sensores
    // Muestra/oculta cards y actualiza parámetros de visualización.
    // El Panel NUNCA modifica HardwareConfig.
    // ─────────────────────────────────────────────────────────────────────

    private fun observeHwConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.hwConfig.collect { cfg ->
                    if (!isAdded) return@collect

                    // ── Sharp ──────────────────────────────────────────────
                    val sharpSlot = cfg.distSlots
                        .firstOrNull { it.type == SensorType.SHARP && it.isValid() }
                    if (sharpSlot != null) {
                        activeSharpPin       = sharpSlot.pin1
                        activeSharpThreshold = sharpSlot.threshold
                        b.cardSharp.visibility = View.VISIBLE
                        b.tvSharpThresh.text   = sharpSlot.threshold.toString()
                        b.tvSharpStatus.text   = "--"
                        b.tvSharpRaw.text      = "--"
                    } else {
                        activeSharpPin = 0; activeSharpThreshold = 0
                        b.cardSharp.visibility = View.GONE
                    }

                    // ── Sensores de línea ──────────────────────────────────
                    // Derivado de HardwareConfig.lineSlots — no hay estado local
                    val ls = cfg.lineSlots.filter { it.type != SensorType.NONE && it.isValid() }
                    if (ls.isNotEmpty()) {
                        b.cardLine.visibility = View.VISIBLE
                        b.rowLine1.visibility = View.VISIBLE
                        b.tvLine1Pin.text     = "GPIO${ls[0].pin1}"
                        b.tvLine1Status.text  = "--"
                        b.tvLine1Raw.text     = "--"
                        if (ls.size >= 2) {
                            b.rowLine2.visibility = View.VISIBLE
                            b.tvLine2Pin.text     = "GPIO${ls[1].pin1}"
                            b.tvLine2Status.text  = "--"
                            b.tvLine2Raw.text     = "--"
                        } else {
                            b.rowLine2.visibility = View.GONE
                        }
                    } else {
                        b.cardLine.visibility = View.GONE
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Sharp visualizer — DETECTADO/LIBRE según umbral de HardwareConfig
    // ─────────────────────────────────────────────────────────────────────

    private fun updateSharpIndicator(adcRaw: Int) {
        if (!isAdded || activeSharpThreshold == 0) return
        b.tvSharpRaw.text = adcRaw.toString()
        val detected = adcRaw > activeSharpThreshold
        b.tvSharpStatus.text = if (detected) "DETECTADO" else "LIBRE"
        val bgColor = if (detected) R.color.danger else R.color.ok
        b.tvSharpStatus.setBackgroundColor(ContextCompat.getColor(requireContext(), bgColor))
        b.tvSharpStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
    }

    // ─────────────────────────────────────────────────────────────────────
    // Line sensor visualizer — BLANCO(fuera dohyo)/NEGRO(dentro dohyo)
    // threshold=0 para sensores digitales (LINE_DIG); rawValue=0/1
    // threshold>0 para analógicos (LINE_ADC); rawValue=ADC 0-4095
    // BLANCO = superficie reflectante = borde del dohyo = PELIGRO
    // ─────────────────────────────────────────────────────────────────────

    private fun updateLineIndicator(
        rawTv: TextView, statusTv: TextView,
        rawValue: Int, threshold: Int, isAnalog: Boolean
    ) {
        if (!isAdded) return
        rawTv.text = if (rawValue < 0) "--" else rawValue.toString()
        if (rawValue < 0) { statusTv.text = "--"; return }
        val onLine = if (isAnalog && threshold > 0) (rawValue > threshold) else (rawValue == 1)
        statusTv.text = if (onLine) "BLANCO" else "NEGRO"
        val bgColor = if (onLine) R.color.warn else R.color.ok
        statusTv.setBackgroundColor(ContextCompat.getColor(requireContext(), bgColor))
        statusTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
    }

    // ─────────────────────────────────────────────────────────────────────
    // Sonar — sesión de diagnóstico ad-hoc (no persiste en HardwareConfig)
    // ─────────────────────────────────────────────────────────────────────

    private fun setupSonar() {
        b.spinnerTrig.adapter = simpleAdapter(SONAR_PIN_LABELS.toList())
        b.spinnerEcho.adapter = simpleAdapter(SONAR_PIN_LABELS.toList())
        b.spinnerEcho.setSelection(1) // default GPIO21

        b.btnSonarToggle.setOnClickListener {
            sonarActive = !sonarActive
            if (sonarActive) {
                val trig = SONAR_PIN_VALUES[b.spinnerTrig.selectedItemPosition]
                val echo = SONAR_PIN_VALUES[b.spinnerEcho.selectedItemPosition]
                vm.sonarStart(trig, echo)
                b.btnSonarToggle.text = "■ Detener Sonar"
                b.btnSonarToggle.backgroundTintList = tintColor(R.color.danger)
            } else {
                vm.sonarStop()
                resetSonarUi()
            }
        }

        b.btnSonarStop.setOnClickListener {
            sonarActive = false
            vm.sonarStop()
            resetSonarUi()
        }
    }

    private fun resetSonarUi() {
        b.tvSonarVal.text = "-- cm"
        b.barSonar.layoutParams = b.barSonar.layoutParams.also { it.width = 0 }
        b.btnSonarToggle.text = "▶ Activar"
        b.btnSonarToggle.backgroundTintList = tintColor(R.color.info)
    }

    // ─────────────────────────────────────────────────────────────────────
    // LED — control operacional (no es configuración de hardware)
    // ─────────────────────────────────────────────────────────────────────

    private fun setupLed() {
        b.btnLedOn.setOnClickListener {
            stopBlink(); vm.ledOn(); setLedRing(true)
            b.seekLed.progress = 100; b.tvLedPct.text = "100%"
        }
        b.btnLedOff.setOnClickListener {
            stopBlink(); vm.ledOff(); setLedRing(false)
            b.seekLed.progress = 0; b.tvLedPct.text = "0%"
        }
        b.btnLedBlink.setOnClickListener {
            stopBlink(); vm.ledBlink(); startBlink()
        }
        b.seekLed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                b.tvLedPct.text = "$p%"
                if (fromUser) { stopBlink(); vm.ledPwm(p); setLedRing(p > 5) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setLedRing(on: Boolean) {
        if (!isAdded) return
        b.ledRing.background = ContextCompat.getDrawable(
            requireContext(),
            if (on) R.drawable.bg_led_ring_on else R.drawable.bg_led_ring_off
        )
    }

    private fun startBlink() {
        blinkHandler = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                if (!isAdded) return
                blinkOn = !blinkOn
                setLedRing(blinkOn)
                blinkHandler?.postDelayed(this, 300)
            }
        }
        blinkHandler?.postDelayed(r, 300)
    }

    private fun stopBlink() {
        blinkHandler?.removeCallbacksAndMessages(null)
        blinkHandler = null
    }

    // ─────────────────────────────────────────────────────────────────────
    // GPIO outputs — control operacional directo
    // ─────────────────────────────────────────────────────────────────────

    private fun setupGpioOutputs() {
        b.spinnerOut1.adapter = simpleAdapter(OUTPUT_PIN_LABELS.toList())
        b.spinnerOut2.adapter = simpleAdapter(OUTPUT_PIN_LABELS.toList())

        b.btnOut1High.setOnClickListener {
            vm.gpioOut(OUTPUT_PIN_VALUES[b.spinnerOut1.selectedItemPosition], 1) }
        b.btnOut1Low.setOnClickListener {
            vm.gpioOut(OUTPUT_PIN_VALUES[b.spinnerOut1.selectedItemPosition], 0) }
        b.btnOut2High.setOnClickListener {
            vm.gpioOut(OUTPUT_PIN_VALUES[b.spinnerOut2.selectedItemPosition], 1) }
        b.btnOut2Low.setOnClickListener {
            vm.gpioOut(OUTPUT_PIN_VALUES[b.spinnerOut2.selectedItemPosition], 0) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Buses — control operacional (duplicado intencional con ConfigFragment
    // porque el Panel Industrial permite activar/desactivar buses en tiempo
    // real durante diagnóstico, sin cambiar HardwareConfig permanente)
    // ─────────────────────────────────────────────────────────────────────

    private fun setupBuses() {
        b.switchI2c.setOnCheckedChangeListener { _, on -> vm.setI2c(on) }
        b.switchSpi.setOnCheckedChangeListener { _, on -> vm.setSpi(on) }
        b.btnI2cScan.setOnClickListener {
            b.tvI2cDevices.visibility = View.VISIBLE
            b.tvI2cDevices.text = "Escaneando…"
            vm.scanI2c()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Speed slider — control operacional
    // ─────────────────────────────────────────────────────────────────────

    private fun setupSpeedSlider() {
        b.seekSpeedPanel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                b.tvSpeedPanel.text = "${(p * 100f / 255f).roundToInt()}%"
                if (fromUser) vm.setSpeed(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ─────────────────────────────────────────────────────────────────────
    // State observer
    // ─────────────────────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.stateData.collect { state ->
                    if (!isAdded) return@collect
                    b.tvPanelMode.text     = state
                    b.toolbar.tbBadge.text = state
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Sensor data observer — SOLO VISUALIZACIÓN
    //
    // Este método actualiza todos los indicadores con datos del firmware.
    // Para el Sharp: usa activeSharpPin (derivado de HardwareConfig, no local).
    // ─────────────────────────────────────────────────────────────────────

    private fun observeSensors() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sensorData.collect { s ->
                    if (!isAdded) return@collect

                    // ── ADC raw ───────────────────────────────────────────
                    updateAdcRow(adc0, s.adc0)
                    if (s.adc1 < 0) {
                        adc1.tvAdcValue.text = "N/A"
                        adc1.tvAdcPct.text   = "(SPI)"
                    } else {
                        updateAdcRow(adc1, s.adc1)
                    }

                    // ── Sharp visualizador ────────────────────────────────
                    if (activeSharpThreshold > 0) {
                        val adcRaw = if (activeSharpPin == 0) s.adc0 else s.adc1
                        if (adcRaw >= 0) updateSharpIndicator(adcRaw)
                    }

                    // ── Sensores de línea visualizador ────────────────────
                    // lineValues[0],[1] vienen del batch "line":[v1,v2] del firmware
                    // Para LINE_ADC: rawValue es ADC 0-4095; threshold de HardwareConfig
                    // Para LINE_DIG: rawValue es 0/1; threshold=0
                    val lsCfg = vm.hwConfig.value.lineSlots
                        .filter { it.type != SensorType.NONE && it.isValid() }
                    if (lsCfg.isNotEmpty() && s.lineValues.isNotEmpty()) {
                        val v0 = s.lineValues.getOrElse(0) { -1 }
                        if (v0 >= 0 && b.rowLine1.visibility == View.VISIBLE) {
                            val isAdc = lsCfg[0].type == SensorType.LINE_ADC
                            updateLineIndicator(b.tvLine1Raw, b.tvLine1Status,
                                v0, lsCfg[0].threshold, isAdc)
                        }
                        if (lsCfg.size >= 2 && b.rowLine2.visibility == View.VISIBLE) {
                            val v1 = s.lineValues.getOrElse(1) { -1 }
                            if (v1 >= 0) {
                                val isAdc = lsCfg[1].type == SensorType.LINE_ADC
                                updateLineIndicator(b.tvLine2Raw, b.tvLine2Status,
                                    v1, lsCfg[1].threshold, isAdc)
                            }
                        }
                    }

                    // ── Entradas digitales ────────────────────────────────
                    val d9  = s.digitalPins.getOrDefault(9,  0)
                    val d6  = s.digitalPins.getOrDefault(6,  -1)
                    val d7  = s.digitalPins.getOrDefault(7,  -1)
                    val d10 = s.digitalPins.getOrDefault(10, -1)

                    b.tvDig9.text  = if (d9  >= 0) d9.toString()  else "-"
                    b.tvDig6.text  = if (d6  >= 0) d6.toString()  else "-"
                    b.tvDig7.text  = if (d7  >= 0) d7.toString()  else "-"
                    b.tvDig10.text = if (d10 >= 0) d10.toString() else "-"

                    b.tvDig6St.text  = if (s.i2cEnabled) "I2C on" else "Libre"
                    b.tvDig7St.text  = if (s.i2cEnabled) "I2C on" else "Libre"
                    b.tvDig10St.text = if (s.spiEnabled) "SPI on" else "Libre"

                    // ── DHT22 ─────────────────────────────────────────────
                    b.tvDhtTemp.text = if (s.temp == 0f) "-- °C" else "%.1f °C".format(s.temp)
                    b.tvDhtHum.text  = if (s.hum  == 0f) "-- %"  else "%.1f %%".format(s.hum)

                    // ── Sonar (datos del firmware) ─────────────────────────
                    val sonarCm = s.distances.getOrElse(0) { -1 }
                    if (sonarCm >= 0) {
                        b.tvSonarVal.text = "$sonarCm cm"
                        val pct = (sonarCm.coerceIn(0, 400) * 100f / 400f).toInt()
                        b.barSonar.post {
                            if (!isAdded) return@post
                            val pw = (b.barSonar.parent as? View)?.width ?: 0
                            if (pw > 0) {
                                b.barSonar.layoutParams = b.barSonar.layoutParams.also {
                                    it.width = (pw * pct / 100f).toInt()
                                }
                            }
                        }
                    }

                    // ── LED ───────────────────────────────────────────────
                    if (!b.seekLed.isPressed) {
                        b.seekLed.progress = s.ledPct
                        b.tvLedPct.text    = "${s.ledPct}%"
                        setLedRing(s.ledPct > 5)
                    }

                    // ── Buses ─────────────────────────────────────────────
                    updateChipLabel(b.tvI2cChip, s.i2cEnabled)
                    updateChipLabel(b.tvSpiChip, s.spiEnabled)

                    if (b.switchI2c.isChecked != s.i2cEnabled) b.switchI2c.isChecked = s.i2cEnabled
                    if (b.switchSpi.isChecked != s.spiEnabled)  b.switchSpi.isChecked = s.spiEnabled

                    // ── I2C scan results ──────────────────────────────────
                    if (s.i2cDevices.isNotEmpty()) {
                        b.tvI2cDevices.visibility = View.VISIBLE
                        b.tvI2cDevices.text = "Encontrados: ${s.i2cDevices.joinToString(", ")}"
                    } else if (b.tvI2cDevices.text == "Escaneando…") {
                        b.tvI2cDevices.text = "Sin dispositivos I2C"
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun updateAdcRow(row: ItemAdcRowBinding, value: Int) {
        row.tvAdcValue.text = value.toString()
        val pct = (value * 100f / 4095f).roundToInt().coerceIn(0, 100)
        row.tvAdcPct.text = "$pct%"
        row.viewAdcBar.post {
            if (!isAdded) return@post
            val pw = (row.viewAdcBar.parent as? View)?.width ?: 0
            if (pw > 0) {
                row.viewAdcBar.layoutParams = row.viewAdcBar.layoutParams.also {
                    it.width = (pw * pct / 100f).toInt()
                }
            }
        }
    }

    private fun updateChipLabel(tv: TextView, enabled: Boolean) {
        if (!isAdded) return
        tv.text = if (enabled) "ON" else "OFF"
        tv.setTextColor(ContextCompat.getColor(
            requireContext(), if (enabled) R.color.ok else R.color.txt2))
    }

    private fun simpleAdapter(labels: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, labels) {
            override fun getView(pos: Int, v: View?, parent: ViewGroup): View =
                super.getView(pos, v, parent).also { root ->
                    (root as? TextView)?.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.txt))
                }
            override fun getDropDownView(pos: Int, v: View?, parent: ViewGroup): View =
                super.getDropDownView(pos, v, parent).also { root ->
                    (root as? TextView)?.apply {
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.txt))
                        setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.bg2))
                    }
                }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun tintColor(colorRes: Int) =
        ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes))

    // ─────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────

    override fun onDestroyView() {
        super.onDestroyView()
        stopBlink()
        if (sonarActive) vm.sonarStop()
        _b = null
    }
}
