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

    private var _binding: FragmentPanelBinding? = null
    private val binding get() = _binding!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPanelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPanelEStop.setOnClickListener { vm.eStop() }
        binding.btnPanelBack.setOnClickListener { findNavController().popBackStack() }

        binding.seekSpeedPanel.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val pct = (progress * 100f / 255f).roundToInt()
                    binding.tvSpeedPanel.text = "$pct%"
                    if (fromUser) vm.setSpeed(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.stateData.collect { state ->
                    binding.tvPanelMode.text = state
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sensorData.collect { s ->
                    // ADC0
                    binding.tvAdc0.text = s.adc0.toString()
                    val pct = (s.adc0 * 100f / 4095f).roundToInt()
                    binding.tvAdc0Pct.text = "$pct%"
                    val parentW = (binding.barAdc0.parent as? View)?.width ?: 0
                    if (parentW > 0) {
                        val p = binding.barAdc0.layoutParams
                        p.width = (parentW * pct / 100f).toInt()
                        binding.barAdc0.layoutParams = p
                    }
                    // DHT22
                    binding.tvDhtTemp.text =
                        if (s.temp == 0f) "-- °C" else "%.1f °C".format(s.temp)
                    binding.tvDhtHum.text =
                        if (s.hum == 0f) "-- %" else "%.1f %%".format(s.hum)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
