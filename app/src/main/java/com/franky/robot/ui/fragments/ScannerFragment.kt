package com.franky.robot.ui.fragments

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.franky.robot.MainActivity
import com.franky.robot.R
import com.franky.robot.databinding.FragmentScannerBinding
import com.franky.robot.domain.ConnectionStatus
import com.franky.robot.ui.viewmodel.RobotViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScannerFragment : Fragment() {

    private var _binding: FragmentScannerBinding? = null
    private val binding get() = _binding!!

    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    private val deviceList = mutableListOf<BluetoothDevice>()
    private val displayList = mutableListOf<String>()
    private lateinit var listAdapter: ArrayAdapter<String>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listAdapter = object : ArrayAdapter<String>(
            requireContext(), R.layout.item_device, R.id.tvDevName, displayList
        ) {
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(pos, convertView, parent)
                v.findViewById<TextView>(R.id.tvDevAddr)?.text =
                    deviceList.getOrNull(pos)?.address ?: ""
                return v
            }
        }
        binding.lvDevices.adapter = listAdapter

        binding.lvDevices.setOnItemClickListener { _, _, i, _ ->
            val device = deviceList.getOrNull(i) ?: return@setOnItemClickListener
            binding.tvScanStatus.text = "Conectando a ${device.name}…"
            binding.progressScan.visibility = View.VISIBLE
            vm.ble.connect(device)
        }

        binding.btnScan.setOnClickListener { startScanning() }

        // Observe status → navigate to dashboard when connected
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.connectionStatus.collect { status ->
                    updateStatusUi(status)
                    if (status == ConnectionStatus.CONNECTED) {
                        // Guard: only navigate if we're still on this screen
                        if (findNavController().currentDestination?.id == R.id.scannerFragment) {
                            findNavController().navigate(R.id.action_scanner_to_dashboard)
                        }
                    }
                }
            }
        }

        startScanning()
    }

    private fun startScanning() {
        vm.ble.stopScan()
        deviceList.clear()
        displayList.clear()
        listAdapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = View.GONE
        binding.progressScan.visibility = View.VISIBLE
        binding.tvScanStatus.text = "Buscando dispositivos FRANKY…"

        vm.ble.startScan { device ->
            // BLE callbacks arrive on a background thread → switch to Main
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                if (!isAdded) return@launch
                if (deviceList.none { it.address == device.address }) {
                    deviceList.add(device)
                    displayList.add(device.name ?: "FRANKY (desconocido)")
                    listAdapter.notifyDataSetChanged()
                    binding.tvEmpty.visibility = View.GONE
                }
            }
        }

        // Show "no devices" message after 8-second timeout
        binding.root.postDelayed({
            if (!isAdded) return@postDelayed
            if (deviceList.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.progressScan.visibility = View.GONE
                binding.tvScanStatus.text = "Sin dispositivos encontrados"
            }
        }, 8000)
    }

    private fun updateStatusUi(status: ConnectionStatus) {
        when (status) {
            ConnectionStatus.SCANNING -> {
                binding.tvScanStatus.text = "Escaneando…"
                binding.progressScan.visibility = View.VISIBLE
            }
            ConnectionStatus.CONNECTING -> {
                binding.tvScanStatus.text = "Conectando…"
                binding.progressScan.visibility = View.VISIBLE
            }
            ConnectionStatus.CONNECTED -> {
                binding.tvScanStatus.text = "¡Conectado!"
                binding.progressScan.visibility = View.GONE
            }
            ConnectionStatus.DISCONNECTED -> {
                binding.progressScan.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        vm.ble.stopScan()
        _binding = null
    }
}
