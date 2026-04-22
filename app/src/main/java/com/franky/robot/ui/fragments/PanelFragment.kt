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
import com.franky.robot.ui.viewmodel.RobotViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class PanelFragment : Fragment() {

    private var _b: FragmentPanelBinding? = null
    private val b get() = _b!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    // ── FIX B3: In ViewBinding, b.rowAdc0 IS already ItemAdcRowBinding.
    //    Do NOT call ItemAdcRowBinding.bind(b.rowAdc0).
    //    The correct pattern is simply assigning b.rowAdc0 directly.
    private lateinit var adc0: ItemAdcRowBinding
    private lateinit var adc1: ItemAdcRowBinding

    // Sonar state
    private var sonarActive = false

    // LED blink animation
    private var blinkHandler: Handler? = null
    private var blinkOn = false

    // ── FIX B4: Spinner data — parallel arrays kept in sync.
    //    selectedItemPosition maps directly to these arrays.
    private val SONAR_PIN_VALUES  = intArrayOf(20, 21,  6,  7, 10)
    private val SONAR_PIN_LABELS  = arrayOf("GPIO20","GPIO21","GPIO6","GPIO7","GPIO10")
    private val OUTPUT_PIN_VALUES = intArrayOf( 9,  6,  7, 10, 20, 21)
    private val OUTPUT_PIN_LABELS = arrayOf("GPIO9","GPIO6","GPIO7","GPIO10","GPIO20","GPIO21")

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentPanelBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── FIX B3: b.rowAdc0 / b.rowAdc1 are already ItemAdcRowBinding instances.
        //    ViewBinding generates typed fields for <include> tags.
        //    Do NOT wrap with ItemAdcRowBinding.bind() — that causes ClassCastException.
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

        observeState()
        observeSensors()
    }

    // ── ADC row labels ────────────────────────────────────────────────────

    private fun setupAdcLabels() {
        adc0.tvAdcBadge.text = "ADC0"
        adc0.tvAdcLabel.text = "GPIO0"
        adc1.tvAdcBadge.text = "ADC1"
        adc1.tvAdcLabel.text = "GPIO1"
    }

    // ── Sonar ─────────────────────────────────────────────────────────────

    private fun setupSonar() {
        b.spinnerTrig.adapter = simpleAdapter(SONAR_PIN_LABELS.toList())
        b.spinnerEcho.adapter = simpleAdapter(SONAR_PIN_LABELS.toList())
        // Default echo to index 1 (GPIO21) to avoid same-pin selection
        b.spinnerEcho.setSelection(1)

        b.btnSonarToggle.setOnClickListener {
            sonarActive = !sonarActive
            if (sonarActive) {
                // Both spinners use the same SONAR_PIN_VALUES array (no rotation)
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

    // ── LED ───────────────────────────────────────────────────────────────

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

    // ── GPIO outputs ──────────────────────────────────────────────────────

    private fun setupGpioOutputs() {
        // ── FIX B4: Spinners use their own parallel arrays.
        //    The adapter list order matches OUTPUT_PIN_VALUES order exactly (no rotation).
        b.spinnerOut1.adapter = simpleAdapter(OUTPUT_PIN_LABELS.toList())
        b.spinnerOut2.adapter = simpleAdapter(OUTPUT_PIN_LABELS.toList())

        b.btnOut1High.setOnClickListener {
            vm.gpioOut(OUTPUT_PIN_VALUES[b.spinnerOut1.selectedItemPosition], 1)
        }
        b.btnOut1Low.setOnClickListener {
            vm.gpioOut(OUTPUT_PIN_VALUES[b.spinnerOut1.selectedItemPosition], 0)
        }
        b.btnOut2High.setOnClickListener {
            vm.gpioOut(OUTPUT_PIN_VALUES[b.spinnerOut2.selectedItemPosition], 1)
        }
        b.btnOut2Low.setOnClickListener {
            vm.gpioOut(OUTPUT_PIN_VALUES[b.spinnerOut2.selectedItemPosition], 0)
        }
    }

    // ── Buses ─────────────────────────────────────────────────────────────

    private fun setupBuses() {
        b.switchI2c.setOnCheckedChangeListener { _, on -> vm.setI2c(on) }
        b.switchSpi.setOnCheckedChangeListener { _, on -> vm.setSpi(on) }
        b.btnI2cScan.setOnClickListener {
            b.tvI2cDevices.visibility = View.VISIBLE
            b.tvI2cDevices.text = "Escaneando…"
            vm.scanI2c()
        }
    }

    // ── Speed slider ──────────────────────────────────────────────────────

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

    // ── State observer ────────────────────────────────────────────────────

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

    // ── Sensor observer ───────────────────────────────────────────────────

    private fun observeSensors() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sensorData.collect { s ->
                    if (!isAdded) return@collect

                    // ── ADC ───────────────────────────────────────────────
                    updateAdcRow(adc0, s.adc0)
                    if (s.adc1 < 0) {
                        adc1.tvAdcValue.text = "N/A"
                        adc1.tvAdcPct.text   = "(SPI)"
                    } else {
                        updateAdcRow(adc1, s.adc1)
                    }

                    // ── Digital pins ──────────────────────────────────────
                    // ── FIX B2: digitalPins is Map<GPIO_number, value>.
                    //    Access by key (GPIO number), not by list index.
                    //    getOrDefault prevents NPE / missing key crashes.
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

                    // ── DHT22 (temp/hum from SensorData) ─────────────────
                    // ── FIX B1: s.temp and s.hum now exist in SensorData
                    b.tvDhtTemp.text = if (s.temp == 0f) "-- °C" else "%.1f °C".format(s.temp)
                    b.tvDhtHum.text  = if (s.hum  == 0f) "-- %"  else "%.1f %%".format(s.hum)

                    // ── Sonar ─────────────────────────────────────────────
                    // distances[0] is the first sensor from firmware "snr" array
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

                    // ── LED from firmware state ───────────────────────────
                    // ledPct comes from batch JSON field "led"
                    // Only update if user is not dragging the slider
                    // (seekLed.isPressed check prevents feedback loop)
                    if (!b.seekLed.isPressed) {
                        b.seekLed.progress = s.ledPct
                        b.tvLedPct.text    = "${s.ledPct}%"
                        setLedRing(s.ledPct > 5)
                    }

                    // ── Buses ─────────────────────────────────────────────
                    updateChipLabel(b.tvI2cChip, s.i2cEnabled)
                    updateChipLabel(b.tvSpiChip, s.spiEnabled)

                    // Sync switches without re-firing listeners
                    // Only update if value actually changed to avoid listener churn
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

    // ── Helpers ───────────────────────────────────────────────────────────

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

    /**
     * Creates a simple spinner adapter with styled dark-theme text.
     * The adapter list order matches the source [labels] list with optional [startIndex] rotation.
     *
     * ── FIX B4: The parallel value array MUST be rotated the same way as labels
     *    when startIndex != 0. The calling code is responsible for this.
     *    For OUTPUT spinners we DON'T rotate (startIndex=0) so the arrays stay aligned.
     */
    private fun simpleAdapter(labels: List<String>, startIndex: Int = 0): ArrayAdapter<String> {
        val ordered = if (startIndex == 0) labels
                      else labels.drop(startIndex) + labels.take(startIndex)
        return object : ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_spinner_item,
            ordered
        ) {
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
        }.also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun tintColor(colorRes: Int) =
        ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes))

    // ── Cleanup ───────────────────────────────────────────────────────────

    override fun onDestroyView() {
        super.onDestroyView()
        stopBlink()
        if (sonarActive) vm.sonarStop()
        _b = null
    }
}
