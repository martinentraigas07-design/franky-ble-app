package com.franky.robot.ui.fragments

import android.os.Bundle
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

    // Convenience binding refs to the two <include> ADC rows
    private lateinit var adc0: ItemAdcRowBinding
    private lateinit var adc1: ItemAdcRowBinding

    // Sonar active state (managed locally, toggled by button)
    private var sonarActive = false

    // LED blink state managed locally for UI ring animation
    private var ledBlinkHandler: android.os.Handler? = null
    private var ledBlinkOn = false

    // Available free GPIO pins for outputs / sonar (motors 2-5 excluded)
    private val FREE_PINS = listOf(9, 6, 7, 10, 20, 21)
    private val SONAR_PINS = listOf(20, 21, 6, 7, 10)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentPanelBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind the two included ADC rows
        adc0 = ItemAdcRowBinding.bind(b.rowAdc0)
        adc1 = ItemAdcRowBinding.bind(b.rowAdc1)

        // Toolbar
        b.toolbar.tbSubtitle.text = "Panel Industrial"
        b.toolbar.tbBadge.text    = "PANEL"

        configureAdcRows()
        configureSonar()
        configureLed()
        configureGpioOutputs()
        configureBuses()
        configureSpeedSlider()

        b.btnPanelEStop.setOnClickListener { vm.eStop() }
        b.btnPanelBack.setOnClickListener  { findNavController().popBackStack() }

        observeSensors()
        observeState()
    }

    // ── ADC row static labels ─────────────────────────────────────────────

    private fun configureAdcRows() {
        adc0.tvAdcBadge.text  = "ADC0"
        adc0.tvAdcLabel.text  = "GPIO0"
        adc1.tvAdcBadge.text  = "ADC1"
        adc1.tvAdcLabel.text  = "GPIO1"
    }

    // ── Sonar card ────────────────────────────────────────────────────────

    private fun configureSonar() {
        val trigAdapter = makeSpinnerAdapter(SONAR_PINS, listOf("GPIO20","GPIO21","GPIO6","GPIO7","GPIO10"))
        val echoAdapter = makeSpinnerAdapter(SONAR_PINS, listOf("GPIO21","GPIO20","GPIO7","GPIO6","GPIO10"))
        b.spinnerTrig.adapter = trigAdapter
        b.spinnerEcho.adapter = echoAdapter

        b.btnSonarToggle.setOnClickListener {
            sonarActive = !sonarActive
            if (sonarActive) {
                val trig = SONAR_PINS[b.spinnerTrig.selectedItemPosition]
                val echo = SONAR_PINS[b.spinnerEcho.selectedItemPosition]
                vm.sonarStart(trig, echo)
                b.btnSonarToggle.text = "■ Detener Sonar"
                b.btnSonarToggle.backgroundTintList = colorStateList(R.color.danger)
            } else {
                vm.sonarStop()
                resetSonar()
            }
        }

        b.btnSonarStop.setOnClickListener {
            sonarActive = false
            vm.sonarStop()
            resetSonar()
        }
    }

    private fun resetSonar() {
        b.tvSonarVal.text = "-- cm"
        b.barSonar.layoutParams = b.barSonar.layoutParams.also { it.width = 0 }
        b.btnSonarToggle.text = "▶ Activar Sonar"
        b.btnSonarToggle.backgroundTintList = colorStateList(R.color.info)
    }

    // ── LED card ──────────────────────────────────────────────────────────

    private fun configureLed() {
        b.btnLedOn.setOnClickListener {
            stopBlink()
            vm.ledOn()
            setLedRingOn(true)
            b.seekLed.progress = 100
            b.tvLedPct.text    = "100%"
        }
        b.btnLedOff.setOnClickListener {
            stopBlink()
            vm.ledOff()
            setLedRingOn(false)
            b.seekLed.progress = 0
            b.tvLedPct.text    = "0%"
        }
        b.btnLedBlink.setOnClickListener {
            stopBlink()
            vm.ledBlink()
            startBlinkAnimation()
        }
        b.seekLed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                b.tvLedPct.text = "$p%"
                if (fromUser) { stopBlink(); vm.ledPwm(p); setLedRingOn(p > 5) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setLedRingOn(on: Boolean) {
        b.ledRing.background = ContextCompat.getDrawable(
            requireContext(),
            if (on) R.drawable.bg_led_ring_on else R.drawable.bg_led_ring_off
        )
    }

    private fun startBlinkAnimation() {
        ledBlinkHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!isAdded) return
                ledBlinkOn = !ledBlinkOn
                setLedRingOn(ledBlinkOn)
                ledBlinkHandler?.postDelayed(this, 300)
            }
        }
        ledBlinkHandler?.postDelayed(runnable, 300)
    }

    private fun stopBlink() {
        ledBlinkHandler?.removeCallbacksAndMessages(null)
        ledBlinkHandler = null
    }

    // ── GPIO output card ──────────────────────────────────────────────────

    private fun configureGpioOutputs() {
        val labels = FREE_PINS.map { "GPIO$it" }
        b.spinnerOut1.adapter = makeSpinnerAdapter(FREE_PINS, labels)
        b.spinnerOut2.adapter = makeSpinnerAdapter(FREE_PINS, labels, startIndex = 1)

        b.btnOut1High.setOnClickListener {
            vm.gpioOut(FREE_PINS[b.spinnerOut1.selectedItemPosition], 1)
        }
        b.btnOut1Low.setOnClickListener {
            vm.gpioOut(FREE_PINS[b.spinnerOut1.selectedItemPosition], 0)
        }
        b.btnOut2High.setOnClickListener {
            vm.gpioOut(FREE_PINS[b.spinnerOut2.selectedItemPosition], 1)
        }
        b.btnOut2Low.setOnClickListener {
            vm.gpioOut(FREE_PINS[b.spinnerOut2.selectedItemPosition], 0)
        }
    }

    // ── Bus card (I2C / SPI) ──────────────────────────────────────────────

    private fun configureBuses() {
        b.switchI2c.setOnCheckedChangeListener { _, checked -> vm.setI2c(checked) }
        b.switchSpi.setOnCheckedChangeListener { _, checked -> vm.setSpi(checked) }

        b.btnI2cScan.setOnClickListener {
            b.tvI2cDevices.visibility = View.VISIBLE
            b.tvI2cDevices.text = "Escaneando…"
            vm.scanI2c()
        }
    }

    // ── Speed slider ──────────────────────────────────────────────────────

    private fun configureSpeedSlider() {
        b.seekSpeedPanel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                b.tvSpeedPanel.text = "${(p * 100f / 255f).roundToInt()}%"
                if (fromUser) vm.setSpeed(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ── Observers ─────────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.stateData.collect { state ->
                    b.tvPanelMode.text    = state
                    b.toolbar.tbBadge.text = state
                }
            }
        }
    }

    private fun observeSensors() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sensorData.collect { s ->
                    // ── ADC ────────────────────────────────────────────
                    updateAdcRow(adc0, s.adc0)
                    updateAdcRow(adc1, s.adc1)

                    // ── Digital pins ───────────────────────────────────
fun dig(tv: TextView, statusTv: TextView?, pin: Int,
        busLabel: String?, busActive: Boolean) {

    val index = when (pin) {
        6  -> 0
        7  -> 1
        9  -> 2
        10 -> 3
        else -> -1
    }

    val v = if (index >= 0 && index < s.digitalPins.size)
        s.digitalPins[index]
    else null

    tv.text = v?.toString() ?: "-"

    statusTv?.text = if (busLabel != null)
        if (busActive) "$busLabel on" else "Libre"
    else ""
}
                        tv.text = v?.toString() ?: "-"
                        statusTv?.text = if (busLabel != null)
                            if (busActive) "$busLabel on" else "Libre"
                        else ""
                    }
                    dig(b.tvDig9,  null,      9,  null,  false)
                    dig(b.tvDig6,  b.tvDig6St, 6, "I2C", s.i2cEnabled)
                    dig(b.tvDig7,  b.tvDig7St, 7, "I2C", s.i2cEnabled)
                    dig(b.tvDig10, b.tvDig10St, 10, "SPI", s.spiEnabled)

                    // ── DHT22 ──────────────────────────────────────────
                    b.tvDhtTemp.text = if (s.temp == 0f) "-- °C" else "%.1f °C".format(s.temp)
                    b.tvDhtHum.text  = if (s.hum  == 0f) "-- %"  else "%.1f %%".format(s.hum)

                    // ── Sonar ──────────────────────────────────────────
                    if (s.sonarCm >= 0) {
                        b.tvSonarVal.text = "${s.sonarCm} cm"
                        val pct = (s.sonarCm.coerceIn(0, 400) * 100f / 400f).toInt()
                        b.barSonar.post {
                            val pw = (b.barSonar.parent as? View)?.width ?: 0
                            if (pw > 0) {
                                val lp = b.barSonar.layoutParams
                                lp.width = (pw * pct / 100f).toInt()
                                b.barSonar.layoutParams = lp
                            }
                        }
                    }

                    // ── Buses ──────────────────────────────────────────
                    updateBusChip(b.tvI2cChip, s.i2cEnabled)
                    updateBusChip(b.tvSpiChip, s.spiEnabled)
                    // Sync switches without firing listener (by removing + re-adding)
                    b.switchI2c.setOnCheckedChangeListener(null)
                    b.switchSpi.setOnCheckedChangeListener(null)
                    b.switchI2c.isChecked = s.i2cEnabled
                    b.switchSpi.isChecked = s.spiEnabled
                    b.switchI2c.setOnCheckedChangeListener { _, on -> vm.setI2c(on) }
                    b.switchSpi.setOnCheckedChangeListener { _, on -> vm.setSpi(on) }

                    // ── I2C devices ────────────────────────────────────
                    if (s.i2cDevices.isNotEmpty()) {
                        b.tvI2cDevices.visibility = View.VISIBLE
                        b.tvI2cDevices.text = if (s.i2cDevices.isEmpty()) "Sin dispositivos"
                        else "Encontrados: ${s.i2cDevices.joinToString(", ")}"
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
            val pw = (row.viewAdcBar.parent as? View)?.width ?: 0
            if (pw > 0) {
                val lp = row.viewAdcBar.layoutParams
                lp.width = (pw * pct / 100f).toInt()
                row.viewAdcBar.layoutParams = lp
            }
        }
    }

    private fun updateBusChip(tv: TextView, enabled: Boolean) {
        tv.text = if (enabled) "ON" else "OFF"
        tv.setTextColor(ContextCompat.getColor(
            requireContext(), if (enabled) R.color.ok else R.color.txt2))
    }

    private fun makeSpinnerAdapter(
        pins: List<Int>, labels: List<String>, startIndex: Int = 0
    ): ArrayAdapter<String> {
        val adapter = object : ArrayAdapter<String>(
            requireContext(), android.R.layout.simple_spinner_item,
            labels.drop(startIndex) + labels.take(startIndex)
        ) {
            override fun getView(pos: Int, v: View?, parent: ViewGroup): View =
                super.getView(pos, v, parent).also {
                    (it as? TextView)?.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.txt))
                }
            override fun getDropDownView(pos: Int, v: View?, parent: ViewGroup): View =
                super.getDropDownView(pos, v, parent).also {
                    (it as? TextView)?.apply {
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.txt))
                        setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.bg2))
                    }
                }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    private fun colorStateList(colorRes: Int) =
        android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), colorRes))

    override fun onDestroyView() {
        super.onDestroyView()
        stopBlink()
        if (sonarActive) vm.sonarStop()
        _b = null
    }
}
