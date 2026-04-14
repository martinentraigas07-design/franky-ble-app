package com.franky.robot.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.franky.robot.MainActivity
import com.franky.robot.R
import com.franky.robot.databinding.FragmentPanelBinding
import com.franky.robot.ui.viewmodel.RobotViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class PanelFragment : Fragment() {

    private var _b: FragmentPanelBinding? = null
    private val b get() = _b!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentPanelBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Toolbar ────────────────────────────────────────────────────
        b.toolbar.tbSubtitle.text = "Panel Industrial"
        b.toolbar.tbBadge.text    = "PANEL"

        // ── Buttons ────────────────────────────────────────────────────
        b.btnPanelEStop.setOnClickListener { vm.eStop() }
        b.btnPanelBack.setOnClickListener  { findNavController().popBackStack() }

        // ── Speed slider ───────────────────────────────────────────────
        b.seekSpeedPanel.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    b.tvSpeedPanel.text = "${(p * 100f / 255f).roundToInt()}%"
                    if (fromUser) vm.setSpeed(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )

        // ── State ──────────────────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.stateData.collect { state ->
                    b.tvPanelMode.text    = state
                    b.toolbar.tbBadge.text = state
                }
            }
        }

        // ── Sensors ────────────────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sensorData.collect { s ->
                    // ADC0
                    b.tvAdc0.text = s.adc0.toString()
                    val pct = (s.adc0 * 100f / 4095f).roundToInt().coerceIn(0, 100)
                    b.tvAdc0Pct.text = "$pct%"
                    b.barAdc0.post {
                        val parentW = (b.barAdc0.parent as? View)?.width ?: 0
                        if (parentW > 0) {
                            val lp = b.barAdc0.layoutParams
                            lp.width = (parentW * pct / 100f).toInt()
                            b.barAdc0.layoutParams = lp
                        }
                    }
                    // DHT22
                    b.tvDhtTemp.text = if (s.temp == 0f) "-- °C" else "%.1f °C".format(s.temp)
                    b.tvDhtHum.text  = if (s.hum  == 0f) "-- %"  else "%.1f %%".format(s.hum)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
