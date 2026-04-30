package com.franky.robot.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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

    private var _b: FragmentDashboardBinding? = null
    private val b get() = _b!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentDashboardBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.toolbar.tbSubtitle.text = "Panel de Control"
        b.toolbar.tbBadge.text    = "IDLE"

        b.btnEStop.setOnClickListener       { vm.eStop() }
        b.btnGoGamepad.setOnClickListener   { nav(R.id.action_dashboard_to_gamepad) }
        b.btnGoPanel.setOnClickListener     { nav(R.id.action_dashboard_to_panel) }
        b.btnGoSumo.setOnClickListener      { nav(R.id.action_dashboard_to_sumo) }
        b.btnGoConfig.setOnClickListener    { nav(R.id.action_dashboard_to_config) }
        b.btnDisconnect.setOnClickListener  { vm.disconnect() }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.stateData.collect { state ->
                    if (!isAdded) return@collect
                    b.tvModeChip.text = state
                    b.toolbar.tbBadge.text = state
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sensorData.collect { s ->
                    if (!isAdded) return@collect
                    b.tvAdc.text  = "ADC: ${s.adc0}"
                    b.tvTemp.text = if (s.temp == 0f) "-- °C" else "%.1f °C".format(s.temp)
                    b.tvHum.text  = if (s.hum  == 0f) "-- %"  else "%.1f %%".format(s.hum)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.connectionStatus.collect { status ->
                    if (!isAdded) return@collect
                    when (status) {
                        ConnectionStatus.CONNECTED -> {
                            b.tvConnChip.text = "CONECTADO"
                            b.tvConnChip.setTextColor(
                                ContextCompat.getColor(requireContext(), R.color.ok))
                        }
                        ConnectionStatus.DISCONNECTED -> {
                            b.tvConnChip.text = "DESCONECTADO"
                            b.tvConnChip.setTextColor(
                                ContextCompat.getColor(requireContext(), R.color.danger))
                            if (findNavController().currentDestination?.id == R.id.dashboardFragment)
                                nav(R.id.action_dashboard_to_scanner)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun nav(id: Int) {
        try { findNavController().navigate(id) }
        catch (_: IllegalArgumentException) {}
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
