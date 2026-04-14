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

class ScannerFragment : Fragment() {

    private var _b: FragmentScannerBinding? = null
    private val b get() = _b!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    private val deviceList   = mutableListOf<BluetoothDevice>()
    private val displayList  = mutableListOf<String>()
    private lateinit var listAdapter: ArrayAdapter<String>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentScannerBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Toolbar ────────────────────────────────────────────────────
        b.toolbar.tbSubtitle.text = "Buscar dispositivo"
        b.toolbar.tbBadge.text    = "BLE"

        // ── Device list adapter ────────────────────────────────────────
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
        b.lvDevices.adapter = listAdapter

        b.lvDevices.setOnItemClickListener { _, _, i, _ ->
            val device = deviceList.getOrNull(i) ?: return@setOnItemClickListener
            b.tvScanStatus.text = "Conectando a ${device.name}…"
            b.progressScan.visibility = View.VISIBLE
            vm.ble.connect(device)
        }

        b.btnScan.setOnClickListener { startScanning() }

        // ── Connection status observer ─────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.connectionStatus.collect { status ->
                    updateStatusUi(status)
                    if (status == ConnectionStatus.CONNECTED &&
                        findNavController().currentDestination?.id == R.id.scannerFragment) {
                        findNavController().navigate(R.id.action_scanner_to_dashboard)
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
        b.tvEmpty.visibility      = View.GONE
        b.progressScan.visibility = View.VISIBLE
        b.tvScanStatus.text       = "Buscando dispositivos FRANKY…"

        vm.ble.startScan { device ->
            // BLE callbacks are on a background thread — switch to Main
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                if (!isAdded) return@launch
                if (deviceList.none { it.address == device.address }) {
                    deviceList.add(device)
                    displayList.add(device.name ?: "FRANKY (desconocido)")
                    listAdapter.notifyDataSetChanged()
                    b.tvEmpty.visibility = View.GONE
                }
            }
        }

        // Show "no devices" after 8 s timeout
        b.root.postDelayed({
            if (!isAdded) return@postDelayed
            if (deviceList.isEmpty()) {
                b.tvEmpty.visibility      = View.VISIBLE
                b.progressScan.visibility = View.GONE
                b.tvScanStatus.text       = "Sin dispositivos encontrados"
            }
        }, 8_000)
    }

    private fun updateStatusUi(status: ConnectionStatus) {
        if (!isAdded) return
        when (status) {
            ConnectionStatus.SCANNING    -> {
                b.tvScanStatus.text       = "Escaneando…"
                b.progressScan.visibility = View.VISIBLE
            }
            ConnectionStatus.CONNECTING  -> {
                b.tvScanStatus.text       = "Conectando…"
                b.progressScan.visibility = View.VISIBLE
            }
            ConnectionStatus.CONNECTED   -> {
                b.tvScanStatus.text       = "¡Conectado!"
                b.progressScan.visibility = View.GONE
            }
            ConnectionStatus.DISCONNECTED -> {
                b.progressScan.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        vm.ble.stopScan()
        _b = null
    }
}
