package com.franky.robot.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
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
import com.franky.robot.databinding.FragmentGamepadBinding
import com.franky.robot.ui.viewmodel.RobotViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class GamepadFragment : Fragment() {

    private var _binding: FragmentGamepadBinding? = null
    private val binding get() = _binding!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    // Maps pointerId → direction string for multi-touch D-PAD
    private val activePointers = mutableMapOf<Int, String>()
    private var lastDpadCmd = "S"
    private var lastJoyCmd  = "S"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGamepadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupModeButtons()
        setupDpad()
        setupJoystick()
        setupSpeedControls()
        setupSensors()
        binding.btnBack.setOnClickListener {
            vm.stop()
            findNavController().popBackStack()
        }
    }

    // ── Mode selector ─────────────────────────────────────────────────────────

    private fun setupModeButtons() {
        setModeStyle(dpadActive = true)   // D-PAD is default

        binding.btnModeDpad.setOnClickListener {
            binding.frameDpad.visibility = View.VISIBLE
            binding.frameJoy.visibility  = View.GONE
            setModeStyle(dpadActive = true)
            vm.stop()
            lastDpadCmd = "S"
            lastJoyCmd  = "S"
        }
        binding.btnModeJoy.setOnClickListener {
            binding.frameDpad.visibility = View.GONE
            binding.frameJoy.visibility  = View.VISIBLE
            setModeStyle(dpadActive = false)
            vm.stop()
            lastDpadCmd = "S"
            lastJoyCmd  = "S"
        }
    }

    /** Applies active/inactive tint to the two mode buttons using ContextCompat. */
    private fun setModeStyle(dpadActive: Boolean) {
        val ctx = requireContext()
        binding.btnModeDpad.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, if (dpadActive) R.color.acc else R.color.bg3)
            )
        binding.btnModeJoy.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, if (!dpadActive) R.color.acc else R.color.bg3)
            )
    }

    // ── D-PAD ─────────────────────────────────────────────────────────────────

    private fun setupDpad() {
        val touchListener = View.OnTouchListener { v, event ->
            val dir = when (v.id) {
                R.id.btnU -> "U"
                R.id.btnD -> "D"
                R.id.btnL -> "L"
                R.id.btnR -> "R"
                else       -> return@OnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    activePointers[event.getPointerId(event.actionIndex)] = dir
                    recalcDpad()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> {
                    activePointers.remove(event.getPointerId(event.actionIndex))
                    recalcDpad()
                }
            }
            true
        }

        binding.btnU.setOnTouchListener(touchListener)
        binding.btnD.setOnTouchListener(touchListener)
        binding.btnL.setOnTouchListener(touchListener)
        binding.btnR.setOnTouchListener(touchListener)

        binding.btnDpadStop.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                activePointers.clear()
                vm.stop()
                lastDpadCmd = "S"
            }
            true
        }
    }

    private fun recalcDpad() {
        val dirs = activePointers.values
        val cmd = when {
            dirs.contains("U") && dirs.contains("R") -> "FR"
            dirs.contains("U") && dirs.contains("L") -> "FL"
            dirs.contains("D") && dirs.contains("R") -> "BR"
            dirs.contains("D") && dirs.contains("L") -> "BL"
            dirs.contains("U")                       -> "F"
            dirs.contains("D")                       -> "B"
            dirs.contains("L")                       -> "L"
            dirs.contains("R")                       -> "R"
            else                                     -> "S"
        }
        if (cmd != lastDpadCmd) {
            lastDpadCmd = cmd
            if (cmd == "S") vm.stop() else vm.sendFast(cmd)
        }
    }

    // ── Joystick ──────────────────────────────────────────────────────────────

    private fun setupJoystick() {
        binding.joystick.onMove = { left, right ->
            val cmd = "M:$left,$right"
            if (cmd != lastJoyCmd) {
                lastJoyCmd = cmd
                vm.sendFast(cmd)
            }
        }
        binding.joystick.onStop = {
            if (lastJoyCmd != "S") {
                lastJoyCmd = "S"
                vm.stop()
            }
        }
    }

    // ── Speed & Turbo ─────────────────────────────────────────────────────────

    private fun setupSpeedControls() {
        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = (progress * 100f / 255f).roundToInt()
                binding.tvSpeedPct.text = "$pct%"
                if (fromUser) vm.setSpeed(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.btnTurbo.setOnClickListener {
            binding.seekSpeed.progress = 255
            vm.setSpeed(255)
        }

        binding.btnStop.setOnClickListener {
            activePointers.clear()
            lastDpadCmd = "S"
            lastJoyCmd  = "S"
            vm.stop()
        }
    }

    // ── Live sensor readout ───────────────────────────────────────────────────

    private fun setupSensors() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sensorData.collect { s ->
                    // ADC0 value + percentage bar
                    binding.tvAdcVal.text = s.adc0.toString()
                    val pct = (s.adc0 * 100f / 4095f).roundToInt().coerceIn(0, 100)
                    binding.tvAdcPct.text = "$pct%"

                    // Proportional bar — update width post-layout
                    binding.adcBar.post {
                        val parentW = (binding.adcBar.parent as? View)?.width ?: 0
                        if (parentW > 0) {
                            val lp = binding.adcBar.layoutParams
                            lp.width = (parentW * pct / 100f).toInt()
                            binding.adcBar.layoutParams = lp
                        }
                    }

                    binding.tvTempVal.text =
                        if (s.temp == 0f) "-- °C" else "%.1f °C".format(s.temp)
                    binding.tvHumVal.text =
                        if (s.hum == 0f) "-- %"  else "%.1f %%".format(s.hum)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        vm.stop()
        _binding = null
    }
}
