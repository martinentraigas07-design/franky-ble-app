package com.franky.robot.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
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
import com.franky.robot.domain.HardwareConfig
import com.franky.robot.domain.SensorSlot
import com.franky.robot.domain.SensorType
import com.franky.robot.ui.viewmodel.RobotViewModel
import com.franky.robot.ui.viewmodel.UiEvent
import kotlinx.coroutines.launch

class ConfigFragment : Fragment() {

    private var _b: FragmentConfigBinding? = null
    private val b get() = _b!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    // ── Pines GPIO disponibles según estado de buses ──────────────────────
    // Se recalculan cada vez que cambia I2C/SPI.
    // Pines reservados siempre: 2,3,4,5 (motores) + 8 (LED) + 9 (BTN)
    private var digPins  = listOf(6, 7, 10, 20, 21)   // sin I2C ni SPI
    private var adcPins  = listOf(0, 1)                 // sin SPI

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View { _b = FragmentConfigBinding.inflate(inflater, container, false); return b.root }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.toolbar.tbSubtitle.text = "Configuración Hardware"
        b.toolbar.tbBadge.text    = "CONFIG"

        setupCountSpinners()
        setupBusListeners()
        setupDistTypeListener()
        setupLineTypeListener()
        setupSeekBars()
        loadCurrentConfig()
        setupButtons()
        observeEvents()
    }

    override fun onResume() {
        super.onResume()
        b.tvConfigStatus.visibility = View.GONE
        b.tvConfigStatus.text = ""
    }

    // ── Contadores ────────────────────────────────────────────────────────
    private fun setupCountSpinners() {
        b.spinnerDistCount.adapter = simpleAdapter(listOf("1", "2", "3"))
        b.spinnerLineCount.adapter = simpleAdapter(listOf("0", "1", "2"))
        b.spinnerDistCount.setSelection(0)
        b.spinnerLineCount.setSelection(0)

        // Al cambiar cantidad de sensores de distancia → mostrar/ocultar panels
        b.spinnerDistCount.onItemSelectedListener = object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateDistPinPanels(pos + 1)
                updateSummary()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        b.spinnerLineCount.onItemSelectedListener = object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateLinePinPanels(pos)
                updateSummary()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    // ── Buses (I2C / SPI) ─────────────────────────────────────────────────
    private fun setupBusListeners() {
        b.switchI2c.setOnCheckedChangeListener { _, _ -> recalcAvailablePins() }
        b.switchSpi.setOnCheckedChangeListener { _, _ -> recalcAvailablePins() }
    }

    private fun recalcAvailablePins() {
        val i2c = b.switchI2c.isChecked
        val spi = b.switchSpi.isChecked

        digPins = mutableListOf<Int>().apply {
            if (!i2c) { add(6); add(7) }
            if (!spi) { add(10); add(20); add(21) }
        }
        adcPins = if (spi) listOf(0) else listOf(0, 1)

        val adcMsg = if (spi) "ADC disponibles: 1 (GPIO0 únicamente — GPIO1 ocupado por SPI)"
                     else     "ADC disponibles: 2 (GPIO0 + GPIO1)"
        b.tvAdcBanner.text = adcMsg

        // Actualizar todos los spinners de pin
        refreshAllPinSpinners()
        updateSummary()
    }

    // ── Tipo de sensor de distancia ───────────────────────────────────────
    private fun setupDistTypeListener() {
        b.rgDistType.setOnCheckedChangeListener { _, _ ->
            val type = selectedDistType()
            b.panelSonarPins.isVisible  = (type == SensorType.HC_SR04)
            b.panelSharpPins.isVisible  = (type == SensorType.SHARP)
            b.panelJs40fPins.isVisible  = (type == SensorType.JS40F)
            updateSummary()
        }
    }

    private fun selectedDistType(): SensorType = when {
        b.rbDistSonar.isChecked  -> SensorType.HC_SR04
        b.rbDistSharp.isChecked  -> SensorType.SHARP
        b.rbDistJs40f.isChecked  -> SensorType.JS40F
        b.rbDistTof.isChecked    -> SensorType.JS40F // ToF → futuro, tratamos igual que JS40F por ahora
        else                     -> SensorType.NONE
    }

    // Muestra/oculta pines del 2do y 3er sonar/sharp/js40f según cantidad
    private fun updateDistPinPanels(count: Int) {
        // HC-SR04
        val show2sonar = count >= 2
        val show3sonar = count >= 3
        b.tvSonar2Label.isVisible     = show2sonar
        b.panelSonar2Pins.isVisible   = show2sonar
        b.tvSonar3Label.isVisible     = show3sonar
        b.panelSonar3Pins.isVisible   = show3sonar
        // Sharp
        b.panelSharp2.isVisible       = show2sonar
        // JS40F
        b.panelJs2.isVisible          = show2sonar
        b.panelJs3.isVisible          = show3sonar
    }

    // ── Tipo de sensor de línea ───────────────────────────────────────────
    private fun setupLineTypeListener() {
        b.rgLineType.setOnCheckedChangeListener { _, _ ->
            refreshLinePinSpinners()
            updateSummary()
        }
    }

    private fun updateLinePinPanels(count: Int) {
        b.panelLine1.isVisible = count >= 1
        b.panelLine2.isVisible = count >= 2
    }

    // ── SeekBars (umbral Sharp) ───────────────────────────────────────────
    private fun setupSeekBars() {
        fun bindSeek(seek: SeekBar, tv: TextView) {
            tv.text = seek.progress.toString()
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                    tv.text = p.toString()
                    updateSummary()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        bindSeek(b.seekSharp1Thresh, b.tvSharp1Thresh)
        bindSeek(b.seekSharp2Thresh, b.tvSharp2Thresh)
    }

    // ── Spinners de pines ─────────────────────────────────────────────────
    private fun refreshAllPinSpinners() {
        // HC-SR04 spinners (GPIO digital)
        val digLabels = digPins.map { "GPIO$it" }
        val digType = selectedDistType()
        listOf(
            b.spinnerSonar1Trig, b.spinnerSonar1Echo,
            b.spinnerSonar2Trig, b.spinnerSonar2Echo,
            b.spinnerSonar3Trig, b.spinnerSonar3Echo
        ).forEach { it.adapter = simpleAdapter(digLabels) }

        // Sharp spinners (ADC)
        val adcLabels = adcPins.map { if (it == 0) "GPIO0 (ADC0)" else "GPIO1 (ADC1)" }
        listOf(b.spinnerSharp1Pin, b.spinnerSharp2Pin).forEach {
            it.adapter = simpleAdapter(adcLabels)
        }

        // JS40F spinners (GPIO digital)
        listOf(b.spinnerJs1Pin, b.spinnerJs2Pin, b.spinnerJs3Pin).forEach {
            it.adapter = simpleAdapter(digLabels)
        }

        refreshLinePinSpinners()
    }

    private fun refreshLinePinSpinners() {
        val isAdc = b.rbLineAdc.isChecked
        val labels = if (isAdc) adcPins.map { if (it == 0) "GPIO0 (ADC0)" else "GPIO1 (ADC1)" }
                     else digPins.map { "GPIO$it" }
        listOf(b.spinnerLine1Pin, b.spinnerLine2Pin).forEach {
            it.adapter = simpleAdapter(labels)
        }
    }

    // ── Cargar config guardada en ViewModel ───────────────────────────────
    private fun loadCurrentConfig() {
        val cfg = vm.hwConfig.value
        b.switchI2c.isChecked = cfg.i2cEnabled
        b.switchSpi.isChecked = cfg.spiEnabled

        recalcAvailablePins() // actualiza spinners primero

        // Cargar slots de distancia
        val ds = cfg.distSlots
        if (ds.isNotEmpty() && ds[0].type != SensorType.NONE) {
            b.spinnerDistCount.setSelection((ds.size - 1).coerceIn(0, 2))
            when (ds[0].type) {
                SensorType.HC_SR04 -> b.rbDistSonar.isChecked = true
                SensorType.SHARP   -> b.rbDistSharp.isChecked = true
                SensorType.JS40F   -> b.rbDistJs40f.isChecked = true
                else               -> b.rbDistNone.isChecked  = true
            }
            // Restaurar pines del primer sonar
            if (ds[0].type == SensorType.HC_SR04) {
                setSpinnerByValue(b.spinnerSonar1Trig, digPins, ds[0].pin1)
                setSpinnerByValue(b.spinnerSonar1Echo, digPins, ds[0].pin2)
                if (ds.size >= 2) {
                    setSpinnerByValue(b.spinnerSonar2Trig, digPins, ds[1].pin1)
                    setSpinnerByValue(b.spinnerSonar2Echo, digPins, ds[1].pin2)
                }
                if (ds.size >= 3) {
                    setSpinnerByValue(b.spinnerSonar3Trig, digPins, ds[2].pin1)
                    setSpinnerByValue(b.spinnerSonar3Echo, digPins, ds[2].pin2)
                }
            }
            if (ds[0].type == SensorType.SHARP) {
                setSpinnerByValue(b.spinnerSharp1Pin, adcPins, ds[0].pin1)
                b.seekSharp1Thresh.progress = ds[0].threshold
                if (ds.size >= 2) {
                    setSpinnerByValue(b.spinnerSharp2Pin, adcPins, ds[1].pin1)
                    b.seekSharp2Thresh.progress = ds[1].threshold
                }
            }
            if (ds[0].type == SensorType.JS40F) {
                setSpinnerByValue(b.spinnerJs1Pin, digPins, ds[0].pin1)
                b.cbJs1Invert.isChecked = ds[0].invertLogic
                if (ds.size >= 2) {
                    setSpinnerByValue(b.spinnerJs2Pin, digPins, ds[1].pin1)
                    b.cbJs2Invert.isChecked = ds[1].invertLogic
                }
                if (ds.size >= 3) {
                    setSpinnerByValue(b.spinnerJs3Pin, digPins, ds[2].pin1)
                    b.cbJs3Invert.isChecked = ds[2].invertLogic
                }
            }
        }

        // Cargar slots de línea
        val ls = cfg.lineSlots
        b.spinnerLineCount.setSelection(ls.size.coerceIn(0, 2))
        if (ls.isNotEmpty()) {
            if (ls[0].type == SensorType.LINE_DIG) b.rbLineDig.isChecked = true
            else b.rbLineAdc.isChecked = true
            val isAdc = ls[0].type == SensorType.LINE_ADC
            val pinList = if (isAdc) adcPins else digPins
            setSpinnerByValue(b.spinnerLine1Pin, pinList, ls[0].pin1)
            if (ls.size >= 2) setSpinnerByValue(b.spinnerLine2Pin, pinList, ls[1].pin1)
        }

        updateDistPinPanels(b.spinnerDistCount.selectedItemPosition + 1)
        updateLinePinPanels(b.spinnerLineCount.selectedItemPosition)
        // Trigger type panels
        b.panelSonarPins.isVisible = b.rbDistSonar.isChecked
        b.panelSharpPins.isVisible = b.rbDistSharp.isChecked
        b.panelJs40fPins.isVisible = b.rbDistJs40f.isChecked
        updateSummary()
    }

    private fun setSpinnerByValue(spinner: Spinner, values: List<Int>, value: Int) {
        val idx = values.indexOf(value)
        if (idx >= 0) spinner.setSelection(idx)
    }

    // ── Botones ───────────────────────────────────────────────────────────
    private fun setupButtons() {
        b.btnApplyConfig.setOnClickListener { applyConfig() }
        b.btnConfigBack.setOnClickListener  { findNavController().popBackStack() }
    }

    // ── Construir y aplicar config ────────────────────────────────────────
    private fun applyConfig() {
        val cfg = buildConfig()
        val err = cfg.validate()
        if (err != null) {
            b.tvConfigStatus.isVisible = true
            b.tvConfigStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
            b.tvConfigStatus.text = "❌ $err"
        } else {
            b.tvConfigStatus.isVisible = false
            vm.applyHwConfig(cfg)
        }
    }

    private fun buildConfig(): HardwareConfig {
        val i2c = b.switchI2c.isChecked
        val spi = b.switchSpi.isChecked
        val distType  = selectedDistType()
        val distCount = b.spinnerDistCount.selectedItemPosition + 1
        val lineCount = b.spinnerLineCount.selectedItemPosition
        val lineIsAdc = b.rbLineAdc.isChecked

        // Construir slots de distancia
        val distSlots = mutableListOf<SensorSlot>()
        if (distType != SensorType.NONE) {
            when (distType) {
                SensorType.HC_SR04 -> {
                    distSlots.add(SensorSlot(SensorType.HC_SR04,
                        digPins.getOrElse(b.spinnerSonar1Trig.selectedItemPosition) { 20 },
                        digPins.getOrElse(b.spinnerSonar1Echo.selectedItemPosition) { 21 }))
                    if (distCount >= 2) distSlots.add(SensorSlot(SensorType.HC_SR04,
                        digPins.getOrElse(b.spinnerSonar2Trig.selectedItemPosition) { 6 },
                        digPins.getOrElse(b.spinnerSonar2Echo.selectedItemPosition) { 7 }))
                    if (distCount >= 3) distSlots.add(SensorSlot(SensorType.HC_SR04,
                        digPins.getOrElse(b.spinnerSonar3Trig.selectedItemPosition) { 10 },
                        digPins.getOrElse(b.spinnerSonar3Echo.selectedItemPosition) { 21 }))
                }
                SensorType.SHARP -> {
                    distSlots.add(SensorSlot(SensorType.SHARP,
                        adcPins.getOrElse(b.spinnerSharp1Pin.selectedItemPosition) { 0 },
                        threshold = b.seekSharp1Thresh.progress))
                    if (distCount >= 2) distSlots.add(SensorSlot(SensorType.SHARP,
                        adcPins.getOrElse(b.spinnerSharp2Pin.selectedItemPosition) { 1 },
                        threshold = b.seekSharp2Thresh.progress))
                }
                SensorType.JS40F -> {
                    distSlots.add(SensorSlot(SensorType.JS40F,
                        digPins.getOrElse(b.spinnerJs1Pin.selectedItemPosition) { 6 },
                        invertLogic = b.cbJs1Invert.isChecked))
                    if (distCount >= 2) distSlots.add(SensorSlot(SensorType.JS40F,
                        digPins.getOrElse(b.spinnerJs2Pin.selectedItemPosition) { 7 },
                        invertLogic = b.cbJs2Invert.isChecked))
                    if (distCount >= 3) distSlots.add(SensorSlot(SensorType.JS40F,
                        digPins.getOrElse(b.spinnerJs3Pin.selectedItemPosition) { 10 },
                        invertLogic = b.cbJs3Invert.isChecked))
                }
                else -> {}
            }
        }
        if (distSlots.isEmpty()) distSlots.add(SensorSlot()) // slot vacío

        // Construir slots de línea
        val lineSlots = mutableListOf<SensorSlot>()
        val lineType = if (lineIsAdc) SensorType.LINE_ADC else SensorType.LINE_DIG
        val linePins = if (lineIsAdc) adcPins else digPins
        if (lineCount >= 1) lineSlots.add(SensorSlot(lineType,
            linePins.getOrElse(b.spinnerLine1Pin.selectedItemPosition) { if (lineIsAdc) 0 else 6 }))
        if (lineCount >= 2) lineSlots.add(SensorSlot(lineType,
            linePins.getOrElse(b.spinnerLine2Pin.selectedItemPosition) { if (lineIsAdc) 1 else 7 }))

        return HardwareConfig(
            i2cEnabled = i2c,
            spiEnabled = spi,
            distSlots  = distSlots,
            lineSlots  = lineSlots
        )
    }

    // ── Resumen visual ────────────────────────────────────────────────────
    private fun updateSummary() {
        if (!isAdded) return
        try {
            val cfg = buildConfig()
            b.tvConfigSummary.text = "Config: ${cfg.summary()}"
        } catch (_: Exception) {
            b.tvConfigSummary.text = "Config: (incompleta)"
        }
    }

    // ── Observar eventos ──────────────────────────────────────────────────
    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiEvents.collect { event ->
                    if (!isAdded) return@collect
                    when (event) {
                        is UiEvent.ConfigError -> {
                            b.tvConfigStatus.isVisible = true
                            b.tvConfigStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
                            b.tvConfigStatus.text = "❌ ${event.msg}"
                        }
                        else -> {}
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.configResult.collect { result ->
                    if (!isAdded || result == null) return@collect
                    b.tvConfigStatus.isVisible = true
                    if (result == "OK") {
                        b.tvConfigStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.ok))
                        b.tvConfigStatus.text = "✓ Configuración confirmada por el robot"
                    } else {
                        b.tvConfigStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
                        b.tvConfigStatus.text = "❌ Firmware: ${result.removePrefix("ERR:")}"
                    }
                }
            }
        }
    }

    // ── Adapter helper ────────────────────────────────────────────────────
    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, items) {
            override fun getView(p: Int, v: View?, parent: ViewGroup): View =
                super.getView(p, v, parent).also { root ->
                    (root as? TextView)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.txt))
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
