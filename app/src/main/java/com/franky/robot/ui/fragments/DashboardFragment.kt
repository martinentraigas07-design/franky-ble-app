package com.franky.robot.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.franky.robot.MainActivity
import com.franky.robot.R
import com.franky.robot.databinding.FragmentDashboardBinding
import com.franky.robot.domain.ConnectionStatus
import com.franky.robot.ui.viewmodel.RobotViewModel
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnEStop.setOnClickListener { vm.eStop() }
        binding.btnGoGamepad.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_gamepad)
        }
        binding.btnGoBlockly.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_blockly)
        }
        binding.btnGoPanel.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_panel)
        }
        binding.btnDisconnect.setOnClickListener {
            vm.disconnect()
            findNavController().navigate(R.id.action_dashboard_to_scanner)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.stateData.collect { state ->
                    binding.tvModeBadge.text = state
                    binding.tvModeChip.text = state
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sensorData.collect { s ->
                    binding.tvAdc.text = s.adc0.toString()
                    binding.tvTemp.text = if (s.temp == 0f) "-- °C" else "%.1f °C".format(s.temp)
                    binding.tvHum.text = if (s.hum == 0f) "-- %" else "%.1f %%".format(s.hum)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.connectionStatus.collect { status ->
                    when (status) {
                        ConnectionStatus.CONNECTED -> {
                            binding.tvConnChip.text = "CONECTADO"
                            binding.tvConnChip.setTextColor(
                                resources.getColor(R.color.ok, null)
                            )
                        }
                        ConnectionStatus.DISCONNECTED -> {
                            binding.tvConnChip.text = "DESCONECTADO"
                            binding.tvConnChip.setTextColor(
                                resources.getColor(R.color.danger, null)
                            )
                            // Auto-navigate back to scanner
                            findNavController().navigate(R.id.action_dashboard_to_scanner)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
