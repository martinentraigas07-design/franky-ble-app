package com.franky.robot.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.franky.robot.MainActivity
import com.franky.robot.R
import com.franky.robot.databinding.FragmentSumoBinding
import com.franky.robot.domain.SumoConfig
import com.franky.robot.ui.viewmodel.RobotViewModel
import kotlinx.coroutines.launch

class SumoFragment : Fragment() {

    private var _b: FragmentSumoBinding? = null
    private val b get() = _b!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    private var sumoRunning = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentSumoBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.toolbar.tbSubtitle.text = "Módulo Sumo"
        b.toolbar.tbBadge.text    = "SUMO"

        setupSliders()

        // Buttons — IDs exactly match fragment_sumo.xml
        b.btnSumoApply.setOnClickListener { sendConfig() }
        b.btnSumoStart.setOnClickListener { sendConfig(); vm.sumoStart(); setSumoRunning(true) }
        b.btnSumoStop.setOnClickListener  { vm.sumoStop(); setSumoRunning(false) }
        b.btnSumoBack.setOnClickListener  { vm.sumoStop(); findNavController().popBackStack() }

        observeState()
        observeSensors()
    }

    // ── Sliders ───────────────────────────────────────────────────────────

    private fun setupSliders() {
        b.seekSearchL.setOnSeekBarChangeListener(sl { b.tvSearchL.text = (it - 255).toString() })
        b.seekSearchR.setOnSeekBarChangeListener(sl { b.tvSearchR.text = (it - 255).toString() })
        b.seekAttackL.setOnSeekBarChangeListener(sl { b.tvAttackL.text = it.toString() })
        b.seekAttackR.setOnSeekBarChangeListener(sl { b.tvAttackR.text = it.toString() })

        // Load current config into UI
        vm.sumoConfig.value.let { cfg ->
            b.seekSearchL.progress = cfg.searchL + 255; b.tvSearchL.text = cfg.searchL.toString()
            b.seekSearchR.progress = cfg.searchR + 255; b.tvSearchR.text = cfg.searchR.toString()
            b.seekAttackL.progress = cfg.attackL;       b.tvAttackL.text = cfg.attackL.toString()
            b.seekAttackR.progress = cfg.attackR;       b.tvAttackR.text = cfg.attackR.toString()
            // Strategy radio buttons (rbAggressive, rbDefensive, rbCustom are in layout)
            when (cfg.strategyId) {
                0    -> b.rbAggressive.isChecked = true
                1    -> b.rbDefensive.isChecked  = true
                else -> b.rbCustom.isChecked     = true
            }
        }
    }

    // ── Build config from current UI state ────────────────────────────────

    private fun buildConfig() = SumoConfig(
        strategyId = when {
            b.rbAggressive.isChecked -> 0
            b.rbDefensive.isChecked  -> 1
            else                     -> 2
        },
        searchL = b.seekSearchL.progress - 255,
        searchR = b.seekSearchR.progress - 255,
        attackL = b.seekAttackL.progress,
        attackR = b.seekAttackR.progress
    )

    private fun sendConfig() { vm.applySumoConfig(buildConfig()) }

    // ── UI state: running / stopped ───────────────────────────────────────

    private fun setSumoRunning(running: Boolean) {
        if (!isAdded) return
        sumoRunning = running
        b.btnSumoStart.isEnabled = !running
        b.btnSumoStop.isEnabled  = running
        b.toolbar.tbBadge.text = if (running) "CORRIENDO" else "SUMO"
        val color = if (running) R.color.ok else R.color.acc2
        b.toolbar.tbBadge.setTextColor(
            ContextCompat.getColor(requireContext(), color))
    }

    // ── Observers ─────────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.stateData.collect { state ->
                    if (!isAdded) return@collect
                    // tvSumoStatus matches layout ID
                    b.tvSumoStatus.text = "Modo: $state"
                    // If robot goes back to IDLE/MANUAL externally, reflect it
                    if ((state == "IDLE" || state == "MANUAL") && sumoRunning) {
                        setSumoRunning(false)
                    }
                }
            }
        }
    }

    private fun observeSensors() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sensorData.collect { s ->
                    if (!isAdded) return@collect

                    // tvDistances — single TextView summarising all distances
                    b.tvDistances.text = "Dist: " + s.distances
                        .take(3)
                        .mapIndexed { _, v -> if (v >= 0) "${v}cm" else "--" }
                        .joinToString("  ")

                    // tvLine0 / tvLine1 are TextViews in the new layout
                    b.tvLine0.text = lineLabel(s.lineValues.getOrElse(0) { -1 })
                    b.tvLine1.text = lineLabel(s.lineValues.getOrElse(1) { -1 })

                    // Line sensor background color for immediate visual feedback
                    b.tvLine0.setBackgroundColor(lineColor(s.lineValues.getOrElse(0) { -1 }))
                    b.tvLine1.setBackgroundColor(lineColor(s.lineValues.getOrElse(1) { -1 }))
                }
            }
        }
    }

    private fun lineLabel(v: Int) = when (v) {
        0    -> "⬜ BLANCO"
        1    -> "⬛ NEGRO"
        else -> "--"
    }

    private fun lineColor(v: Int): Int {
        if (!isAdded) return 0
        return ContextCompat.getColor(requireContext(), when (v) {
            0    -> R.color.ok
            1    -> R.color.danger
            else -> R.color.bg3
        })
    }

    private fun sl(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) { onChange(p) }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (sumoRunning) vm.sumoStop()
        _b = null
    }
}
