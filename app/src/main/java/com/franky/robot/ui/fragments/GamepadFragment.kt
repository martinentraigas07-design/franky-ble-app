package com.franky.robot.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.franky.robot.MainActivity
import com.franky.robot.R
import com.franky.robot.databinding.FragmentGamepadBinding
import com.franky.robot.ui.viewmodel.RobotViewModel
import kotlin.math.roundToInt

class GamepadFragment : Fragment() {

    private var _b: FragmentGamepadBinding? = null
    private val b get() = _b!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    // D-PAD multi-touch state
    private val activePointers = mutableMapOf<Int, String>()
    private var lastDpadCmd = "S"
    private var lastJoyCmd  = "S"

    // ── Trim por motor — permite calibrar la dirección recta ──────────────
    // trimL y trimR son factores de escala en rango -100..+100
    // trimL = 0 → sin corrección | trimL > 0 → motor L más rápido
    // La velocidad efectiva aplicada = baseSpeed * (1 + trim/100)
    // Enviado al firmware como TRIM:trimL,trimR
    private var trimL = 0   // -100..+100 (centro=0)
    private var trimR = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentGamepadBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureToolbar()
        setupModeButtons()
        setupDpad()
        setupJoystick()
        setupSpeedControls()
        b.btnBack.setOnClickListener { vm.stop(); findNavController().popBackStack() }
    }

    private fun configureToolbar() {
        b.toolbar.tbSubtitle.text = "Control Manual"
        b.toolbar.tbBadge.text    = "GAMEPAD"
    }

    // ── Mode selector ──────────────────────────────────────────────────────
    private fun setupModeButtons() {
        applyModeStyle(dpadActive = true)
        b.btnModeDpad.setOnClickListener {
            b.frameDpad.visibility = View.VISIBLE
            b.frameJoy.visibility  = View.GONE
            applyModeStyle(dpadActive = true)
            resetCommands()
        }
        b.btnModeJoy.setOnClickListener {
            b.frameDpad.visibility = View.GONE
            b.frameJoy.visibility  = View.VISIBLE
            applyModeStyle(dpadActive = false)
            resetCommands()
        }
    }

    private fun applyModeStyle(dpadActive: Boolean) {
        val ctx = requireContext()
        b.btnModeDpad.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (dpadActive) R.color.acc else R.color.bg3))
        b.btnModeJoy.backgroundTintList  = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (dpadActive) R.color.bg3 else R.color.acc))
    }

    private fun resetCommands() {
        activePointers.clear()
        lastDpadCmd = "S"; lastJoyCmd = "S"
        vm.stop()
    }

    // ── D-PAD ──────────────────────────────────────────────────────────────
    private fun setupDpad() {
        val tl = View.OnTouchListener { v, e ->
            val dir = when (v.id) {
                R.id.btnU -> "U"; R.id.btnD -> "D"
                R.id.btnL -> "L"; R.id.btnR -> "R"
                else -> return@OnTouchListener false
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    activePointers[e.getPointerId(e.actionIndex)] = dir
                    recalcDpad()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> {
                    activePointers.remove(e.getPointerId(e.actionIndex))
                    recalcDpad()
                }
            }
            true
        }
        b.btnU.setOnTouchListener(tl)
        b.btnD.setOnTouchListener(tl)
        b.btnL.setOnTouchListener(tl)
        b.btnR.setOnTouchListener(tl)

        b.btnDpadStop.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                activePointers.clear(); vm.stop(); lastDpadCmd = "S"
            }
            true
        }
    }

    private fun recalcDpad() {
        val d = activePointers.values
        val cmd = when {
            d.contains("U") && d.contains("R") -> "FR"
            d.contains("U") && d.contains("L") -> "FL"
            d.contains("D") && d.contains("R") -> "BR"
            d.contains("D") && d.contains("L") -> "BL"
            d.contains("U") -> "F"
            d.contains("D") -> "B"
            d.contains("L") -> "L"
            d.contains("R") -> "R"
            else -> "S"
        }
        if (cmd != lastDpadCmd) {
            lastDpadCmd = cmd
            if (cmd == "S") vm.stop() else vm.sendFast(cmd)
        }
    }

    // ── Joystick ───────────────────────────────────────────────────────────
    private fun setupJoystick() {
        var lastJoySentMs = 0L
        b.joystick.onMove = { left, right ->
            // Aplicar trim a la salida del joystick
            val lTrimmed = applyTrim(left, trimL)
            val rTrimmed = applyTrim(right, trimR)
            val cmd = "DRIVE:$lTrimmed,$rTrimmed"
            val now = System.currentTimeMillis()
            if (cmd != lastJoyCmd && (now - lastJoySentMs) >= 50L) {
                lastJoyCmd = cmd; lastJoySentMs = now
                vm.drive(lTrimmed, rTrimmed)
            }
        }
        b.joystick.onStop = {
            if (lastJoyCmd != "S") { lastJoyCmd = "S"; vm.stop() }
        }
    }

    // ── Speed & Trim ───────────────────────────────────────────────────────
    private fun setupSpeedControls() {
        // Velocidad base global
        b.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                b.tvSpeedPct.text = "${(p * 100f / 255f).roundToInt()}%"
                if (fromUser) vm.setSpeed(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Trim motor L — seekbar va de 0..200, centro=100, trim= progress-100
        b.seekTrimL.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                trimL = p - 100
                b.tvTrimL.text = if (trimL >= 0) "+$trimL" else "$trimL"
                if (fromUser) sendTrim()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Trim motor R
        b.seekTrimR.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                trimR = p - 100
                b.tvTrimR.text = if (trimR >= 0) "+$trimR" else "$trimR"
                if (fromUser) sendTrim()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Turbo: velocidad base 255 + reset trim
        b.btnTurbo.setOnClickListener {
            b.seekSpeed.progress = 255
            vm.setSpeed(255)
        }

        b.btnStop.setOnClickListener { resetCommands() }
    }

    // Envía trim al firmware a través del método correcto del ViewModel.
    // Respeta MVVM: la lógica de construcción del comando y el coerceIn
    // viven en RobotViewModel.sendMotorTrim(), no en el Fragment.
    private fun sendTrim() {
        vm.sendMotorTrim(trimL, trimR)
    }

    // Aplica el factor de trim a un valor de velocidad (-255..255)
    // trim en rango -100..+100: trim>0 amplifica, trim<0 reduce
    private fun applyTrim(speed: Int, trim: Int): Int {
        if (trim == 0 || speed == 0) return speed
        val factor = 1f + (trim / 100f)
        return (speed * factor).toInt().coerceIn(-255, 255)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        vm.stop()
        _b = null
    }
}
