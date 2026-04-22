package com.franky.robot.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.franky.robot.MainActivity
import com.franky.robot.R
import com.franky.robot.databinding.FragmentConfigBinding
import com.franky.robot.domain.DistSensorType
import com.franky.robot.domain.HardwareConfig
import com.franky.robot.ui.viewmodel.RobotViewModel
import com.franky.robot.ui.viewmodel.UiEvent
import kotlinx.coroutines.launch

class ConfigFragment : Fragment() {

    private var _b: FragmentConfigBinding? = null
    private val b get() = _b!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View { _b = FragmentConfigBinding.inflate(inflater, container, false); return b.root }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.toolbar.tbSubtitle.text = "Configuración Hardware"
        b.toolbar.tbBadge.text    = "CONFIG"

        setupSpinners()
        loadCurrentConfig()
        setupListeners()
        observeEvents()
    }

    override fun onResume() {
        super.onResume()
        // Reset stale configResult so previous "OK" isn't shown on re-entry
        // StateFlow replays the last value, so we must clear it manually.
        b.tvConfigStatus.visibility = android.view.View.GONE
        b.tvConfigStatus.text = ""
    }

    private fun setupSpinners() {
        val distItems  = listOf("1", "2", "3")
        val lineItems  = listOf("0", "1", "2")
        b.spinnerDistCount.adapter = simpleAdapter(distItems)
        b.spinnerLineCount.adapter = simpleAdapter(lineItems)
    }

    private fun loadCurrentConfig() {
        val cfg = vm.hwConfig.value
        b.switchI2c.isChecked   = cfg.i2cEnabled
        b.switchSpi.isChecked   = cfg.spiEnabled
        b.spinnerDistCount.setSelection((cfg.distCount - 1).coerceIn(0, 2))
        b.spinnerLineCount.setSelection(cfg.lineCount.coerceIn(0, 2))
        when (cfg.distSensor) {
            DistSensorType.NONE      -> b.rbDistNone.isChecked  = true
            DistSensorType.HC_SR04   -> b.rbDistSonar.isChecked = true
            DistSensorType.SHARP     -> b.rbDistSharp.isChecked = true
            DistSensorType.I2C_TOF   -> b.rbDistTof.isChecked   = true
        }
        updateAdcBanner()
    }

    private fun setupListeners() {
        b.switchSpi.setOnCheckedChangeListener { _, _ -> updateAdcBanner() }
        b.rbDistTof.setOnCheckedChangeListener { _, checked ->
            if (checked && !b.switchI2c.isChecked) {
                b.switchI2c.isChecked = true // auto-enable I2C for ToF
            }
        }
        b.btnApplyConfig.setOnClickListener { applyConfig() }
        b.btnConfigBack.setOnClickListener  { findNavController().popBackStack() }
    }

    /**
     * Updates the ADC availability banner.
     * RULE: SPI active → GPIO1 (ADC1) unavailable → max lineCount = 1.
     * This enforces the hardware constraint at the UI level BEFORE sending to firmware.
     */
    private fun updateAdcBanner() {
        val spiOn    = b.switchSpi.isChecked
        val maxAdc   = if (spiOn) 1 else 2
        val maxLine  = maxAdc

        b.tvAdcBanner.text = if (spiOn)
            "ADC disponibles: 1 (GPIO0 únicamente — GPIO1 ocupado por SPI)"
        else
            "ADC disponibles: 2 (GPIO0 + GPIO1)"

        // Rebuild line spinner with constrained options
        val lineOpts = (0..maxLine).map { it.toString() }
        b.spinnerLineCount.adapter = simpleAdapter(lineOpts)

        // Show warning if current selection exceeds max
        val curLine = b.spinnerLineCount.selectedItemPosition
        if (curLine > maxLine) b.spinnerLineCount.setSelection(maxLine)

        b.tvLineWarning.isVisible = spiOn
        b.tvLineWarning.text      = "⚠ Con SPI activo: máximo 1 sensor de línea (1 ADC disponible)"
    }

    private fun applyConfig() {
        val distSensor = when {
            b.rbDistSonar.isChecked -> DistSensorType.HC_SR04
            b.rbDistSharp.isChecked -> DistSensorType.SHARP
            b.rbDistTof.isChecked   -> DistSensorType.I2C_TOF
            else                    -> DistSensorType.NONE
        }
        val cfg = HardwareConfig(
            i2cEnabled = b.switchI2c.isChecked,
            spiEnabled = b.switchSpi.isChecked,
            distSensor = distSensor,
            distCount  = b.spinnerDistCount.selectedItemPosition + 1,
            lineCount  = b.spinnerLineCount.selectedItemPosition
        )
        val err = cfg.validate()
        if (err != null) {
            b.tvConfigStatus.isVisible = true
            b.tvConfigStatus.text      = err
        } else {
            b.tvConfigStatus.isVisible = false
            vm.applyHwConfig(cfg)
        }
    }

    private fun observeEvents() {
        // Observe local validation errors from ViewModel
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiEvents.collect { event ->
                    if (!isAdded) return@collect
                    when (event) {
                        is UiEvent.ConfigError -> {
                            b.tvConfigStatus.isVisible = true
                            b.tvConfigStatus.text      = event.msg
                        }
                        else -> {}
                    }
                }
            }
        }
        // Observe firmware ACK: CFG_OK or CFG_ERR:message
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.configResult.collect { result ->
                    if (!isAdded || result == null) return@collect
                    if (result == "OK") {
                        b.tvConfigStatus.isVisible = true
                        b.tvConfigStatus.text      = "✓ Configuración confirmada por el robot"
                        b.tvConfigStatus.setTextColor(
                            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.ok))
                    } else {
                        b.tvConfigStatus.isVisible = true
                        b.tvConfigStatus.text      = "❌ Firmware: ${result.removePrefix("ERR:")}"
                        b.tvConfigStatus.setTextColor(
                            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.danger))
                    }
                }
            }
        }
    }

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(
            requireContext(), android.R.layout.simple_spinner_item, items
        ) {
            override fun getView(p: Int, v: View?, parent: ViewGroup): View =
                super.getView(p, v, parent).also { root ->
                    (root as? TextView)?.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.txt))
                }
            override fun getDropDownView(p: Int, v: View?, parent: ViewGroup): View =
                super.getDropDownView(p, v, parent).also { root ->
                    (root as? TextView)?.apply {
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.txt))
                        setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.bg2))
                    }
                }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
