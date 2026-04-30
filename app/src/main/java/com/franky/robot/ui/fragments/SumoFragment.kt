package com.franky.robot.ui.fragments

import android.os.Bundle
import android.os.CountDownTimer
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

    private var sumoRunning  = false
    private var countdownActive = false
    private var countdownTimer: CountDownTimer? = null

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

        b.btnSumoApply.setOnClickListener { sendConfig() }

        // ── INICIAR — reglamento LNR: 5 segundos de seguridad obligatorios ─
        // "Los robots deberán diseñarse de forma que comiencen a moverse al
        //  cabo de 5 segundos de ser activados." — Reglamento Sumo LNR/UTN
        // Durante esos 5 segundos: LED parpadea (señal visual reglamentaria)
        // Al terminar: SUMO:START al firmware → motores activos + LED fijo ON
        b.btnSumoStart.setOnClickListener {
            if (!countdownActive && !sumoRunning) {
                sendConfig()                // enviar parámetros primero
                startSafetyCountdown()
            }
        }

        b.btnSumoStop.setOnClickListener  { cancelAll(); vm.sumoStop(); setSumoRunning(false) }
        b.btnSumoBack.setOnClickListener  { cancelAll(); vm.sumoStop(); findNavController().popBackStack() }

        observeState()
        observeSensors()
    }

    // ── Safety countdown — 5 segundos reglamentarios ──────────────────────
    private fun startSafetyCountdown() {
        countdownActive = true

        // Deshabilitar botones durante countdown
        b.btnSumoStart.isEnabled = false
        b.btnSumoStop.isEnabled  = false
        b.btnSumoApply.isEnabled = false
        b.tvCountdown.visibility = View.VISIBLE

        // LED blink durante el período de seguridad (señal visual reglamentaria)
        vm.ledBlink()

        countdownTimer = object : CountDownTimer(5_000, 1_000) {
            override fun onTick(msLeft: Long) {
                if (!isAdded) return
                val secsLeft = (msLeft / 1_000) + 1
                b.tvCountdown.text = "⚠ INICIO EN  $secsLeft  seg"
                // Color progresivo: warn→danger en los últimos 2 segundos
                val color = if (secsLeft <= 2) R.color.danger else R.color.warn
                b.tvCountdown.setTextColor(ContextCompat.getColor(requireContext(), color))
                b.toolbar.tbBadge.text = "$secsLeft..."
            }

            override fun onFinish() {
                if (!isAdded) return
                countdownActive = false
                b.tvCountdown.visibility = View.GONE

                // Enviar SUMO:START al firmware
                // El firmware: activa modo autónomo + enciende LED fijo
                vm.sendFast("SUMO:START")
                setSumoRunning(true)
            }
        }.start()
    }

    private fun cancelAll() {
        countdownTimer?.cancel()
        countdownTimer    = null
        countdownActive   = false
        b.tvCountdown.visibility = View.GONE
        b.btnSumoApply.isEnabled = true
    }

    // ── Sliders ───────────────────────────────────────────────────────────

    private fun setupSliders() {
        b.seekSearchL.setOnSeekBarChangeListener(sl { b.tvSearchL.text = (it - 255).toString() })
        b.seekSearchR.setOnSeekBarChangeListener(sl { b.tvSearchR.text = (it - 255).toString() })
        b.seekAttackL.setOnSeekBarChangeListener(sl { b.tvAttackL.text = it.toString() })
        b.seekAttackR.setOnSeekBarChangeListener(sl { b.tvAttackR.text = it.toString() })

        vm.sumoConfig.value.let { cfg ->
            b.seekSearchL.progress = cfg.searchL + 255; b.tvSearchL.text = cfg.searchL.toString()
            b.seekSearchR.progress = cfg.searchR + 255; b.tvSearchR.text = cfg.searchR.toString()
            b.seekAttackL.progress = cfg.attackL;       b.tvAttackL.text = cfg.attackL.toString()
            b.seekAttackR.progress = cfg.attackR;       b.tvAttackR.text = cfg.attackR.toString()
            when (cfg.strategyId) {
                0    -> b.rbAggressive.isChecked = true
                1    -> b.rbDefensive.isChecked  = true
                else -> b.rbCustom.isChecked     = true
            }
        }
    }

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
        b.btnSumoStart.isEnabled = !running && !countdownActive
        b.btnSumoStop.isEnabled  = running
        b.btnSumoApply.isEnabled = !running && !countdownActive
        b.toolbar.tbBadge.text   = if (running) "CORRIENDO" else "SUMO"
        val color = if (running) R.color.ok else R.color.acc2
        b.toolbar.tbBadge.setTextColor(ContextCompat.getColor(requireContext(), color))
    }

    // ── Observers ─────────────────────────────────────────────────────────
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.stateData.collect { state ->
                    if (!isAdded) return@collect
                    b.tvSumoStatus.text = "Modo: $state"
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

                    b.tvDistances.text = "Dist: " + s.distances
                        .take(3)
                        .mapIndexed { _, v -> if (v >= 0) "${v}cm" else "--" }
                        .joinToString("  ")

                    b.tvLine0.text = lineLabel(s.lineValues.getOrElse(0) { -1 })
                    b.tvLine1.text = lineLabel(s.lineValues.getOrElse(1) { -1 })
                    b.tvLine0.setBackgroundColor(lineColor(s.lineValues.getOrElse(0) { -1 }))
                    b.tvLine1.setBackgroundColor(lineColor(s.lineValues.getOrElse(1) { -1 }))
                }
            }
        }
    }

    private fun lineLabel(v: Int) = when (v) { 0 -> "⬜ BLANCO"; 1 -> "⬛ NEGRO"; else -> "--" }

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
        cancelAll()
        if (sumoRunning) vm.sumoStop()
        _b = null
    }
}
