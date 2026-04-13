package com.franky.robot.ui.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.franky.robot.MainActivity
import com.franky.robot.R
import com.franky.robot.databinding.FragmentBlocklyBinding
import com.franky.robot.ui.viewmodel.RobotViewModel
import com.franky.robot.ui.viewmodel.UiEvent
import kotlinx.coroutines.launch

class BlocklyFragment : Fragment() {

    private var _binding: FragmentBlocklyBinding? = null
    private val binding get() = _binding!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    private val sentInstructions = StringBuilder("// Sin instrucciones enviadas")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBlocklyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()
        setupWebView()
        setupSendButton()
        observeEvents()
    }

    private fun setupTabs() {
        binding.tabEditor.setOnClickListener {
            binding.panelEditor.visibility = View.VISIBLE
            binding.panelInstructions.visibility = View.GONE
            binding.tabEditor.background =
                resources.getDrawable(R.drawable.bg_tab_active, null)
            binding.tabEditor.setTextColor(resources.getColor(R.color.white, null))
            binding.tabInstructions.background =
                resources.getDrawable(R.drawable.bg_tab_inactive, null)
            binding.tabInstructions.setTextColor(resources.getColor(R.color.txt2, null))
        }
        binding.tabInstructions.setOnClickListener {
            binding.panelEditor.visibility = View.GONE
            binding.panelInstructions.visibility = View.VISIBLE
            binding.tabInstructions.background =
                resources.getDrawable(R.drawable.bg_tab_active, null)
            binding.tabInstructions.setTextColor(resources.getColor(R.color.white, null))
            binding.tabEditor.background =
                resources.getDrawable(R.drawable.bg_tab_inactive, null)
            binding.tabEditor.setTextColor(resources.getColor(R.color.txt2, null))
            binding.tvInstructions.text = sentInstructions.toString()
        }
        binding.btnBackFromBlockly.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    binding.statusDot.setBackgroundColor(
                        resources.getColor(R.color.ok, null)
                    )
                    binding.tvStatusText.text = "Editor listo"
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(
                    message: android.webkit.ConsoleMessage?
                ): Boolean = true
            }
            addJavascriptInterface(AndroidBridge(), "AndroidInterface")
            loadUrl("file:///android_asset/blockly/index.html")
        }
        binding.tvStatusText.text = "Cargando editor Blockly…"
    }

    private fun setupSendButton() {
        binding.btnSendBlockly.setOnClickListener {
            binding.webView.evaluateJavascript("getXmlForAndroid()") { xml ->
                val clean = xml?.trim('"')?.replace("\\\"", "\"")
                    ?.replace("\\'", "'")?.replace("\\n", "\n")
                if (clean.isNullOrBlank() || clean.length < 30) {
                    showNotif("Workspace vacío", "#FFA000")
                } else {
                    vm.sendXml(clean)
                }
            }
        }
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiEvents.collect { event ->
                    when (event) {
                        is UiEvent.XmlSending -> {
                            binding.pbXml.visibility = View.VISIBLE
                            binding.tvBadge.text = "ENVIANDO"
                            binding.tvStatusText.text = "Enviando programa…"
                        }
                        is UiEvent.XmlSuccess -> {
                            binding.pbXml.visibility = View.GONE
                            binding.tvBadge.text = "OK"
                            binding.tvStatusText.text = "Programa cargado ✓"
                            showNotif("¡Cargado en FRANKY!", "#00C853")
                            sentInstructions.clear()
                            sentInstructions.append("// Último programa enviado exitosamente")
                        }
                        is UiEvent.XmlError -> {
                            binding.pbXml.visibility = View.GONE
                            binding.tvBadge.text = "ERROR"
                            binding.tvStatusText.text = "Error al enviar"
                            showNotif("Error al enviar", "#E53935")
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun showNotif(msg: String, color: String) {
        val js = "showNotif('$msg', '$color')"
        binding.webView.post { binding.webView.evaluateJavascript(js, null) }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun enviar(xml: String) {
            if (xml.length < 30) {
                binding.webView.post { showNotif("Workspace vacío", "#FFA000") }
                return
            }
            vm.sendXml(xml)
        }

        @JavascriptInterface
        fun updateBlockCount(count: Int) {
            requireActivity().runOnUiThread {
                binding.tvBlockCount.text = "$count bloques"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
