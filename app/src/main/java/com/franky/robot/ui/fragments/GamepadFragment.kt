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

    // ── Toolbar ────────────────────────────────────────────────────────────

    private fun configureToolbar() {
        b.toolbar.tbSubtitle.text = "Control Manual"
        b.toolbar.tbBadge.text = "GAMEPAD"
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
            ContextCompat.getColor(ctx, if (dpadActive) R.color.acc else R.color.bg3)
        )
        b.btnModeJoy.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (dpadActive) R.color.bg3 else R.color.acc)
        )
    }

    private fun resetCommands() {
        activePointers.clear()
        lastDpadCmd = "S"
        lastJoyCmd  = "S"
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
            val cmd = "DRIVE:$left,$right"
            val now = System.currentTimeMillis()
            // 20Hz cap: prevents write channel flood during continuous joystick rotation
            if (cmd != lastJoyCmd && (now - lastJoySentMs) >= 50L) {
                lastJoyCmd = cmd; lastJoySentMs = now; vm.drive(left, right)
            }
        }
        b.joystick.onStop = {
            if (lastJoyCmd != "S") { lastJoyCmd = "S"; vm.stop() }
        }
    }

    // ── Speed & Turbo ──────────────────────────────────────────────────────

    private fun setupSpeedControls() {
        b.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                b.tvSpeedPct.text = "${(p * 100f / 255f).roundToInt()}%"
                if (fromUser) vm.setSpeed(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        b.btnTurbo.setOnClickListener { b.seekSpeed.progress = 255; vm.setSpeed(255) }
        b.btnStop.setOnClickListener  { resetCommands() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        vm.stop()
        _b = null
    }
}
