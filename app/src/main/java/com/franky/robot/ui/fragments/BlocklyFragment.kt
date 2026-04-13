package com.franky.robot.ui.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
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

    // ---- Tab switching -------------------------------------------------------

    private fun setupTabs() {
        selectTab(editorActive = true)   // initial state

        binding.tabEditor.setOnClickListener { selectTab(editorActive = true) }
        binding.tabInstructions.setOnClickListener {
            binding.tvInstructions.text = sentInstructions.toString()
            selectTab(editorActive = false)
        }
        binding.btnBackFromBlockly.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    /**
     * Switches the visual state of the two tabs.
     * Uses ContextCompat — no deprecated API.
     */
    private fun selectTab(editorActive: Boolean) {
        val ctx = requireContext()
        if (editorActive) {
            binding.panelEditor.visibility = View.VISIBLE
            binding.panelInstructions.visibility = View.GONE
            binding.tabEditor.background =
                ContextCompat.getDrawable(ctx, R.drawable.bg_tab_active)
            binding.tabEditor.setTextColor(ContextCompat.getColor(ctx, R.color.white))
            binding.tabInstructions.background =
                ContextCompat.getDrawable(ctx, R.drawable.bg_tab_inactive)
            binding.tabInstructions.setTextColor(ContextCompat.getColor(ctx, R.color.txt2))
        } else {
            binding.panelEditor.visibility = View.GONE
            binding.panelInstructions.visibility = View.VISIBLE
            binding.tabInstructions.background =
                ContextCompat.getDrawable(ctx, R.drawable.bg_tab_active)
            binding.tabInstructions.setTextColor(ContextCompat.getColor(ctx, R.color.white))
            binding.tabEditor.background =
                ContextCompat.getDrawable(ctx, R.drawable.bg_tab_inactive)
            binding.tabEditor.setTextColor(ContextCompat.getColor(ctx, R.color.txt2))
        }
    }

    // ---- WebView setup -------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!isAdded) return
                    binding.statusDot.setBackgroundColor(
                        ContextCompat.getColor(requireContext(), R.color.ok)
                    )
                    binding.tvStatusText.text = "Editor listo"
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean = true
            }

            addJavascriptInterface(AndroidBridge(), "AndroidInterface")
            loadUrl("file:///android_asset/blockly/index.html")
        }
        binding.tvStatusText.text = "Cargando editor Blockly…"
    }

    // ---- Send button ---------------------------------------------------------

    private fun setupSendButton() {
        binding.btnSendBlockly.setOnClickListener {
            binding.webView.evaluateJavascript("getXmlForAndroid()") { xmlRaw ->
                if (!isAdded) return@evaluateJavascript
                // evaluateJavascript returns a JSON string — strip the outer quotes
                // and unescape internal escape sequences
                val xml = xmlRaw
                    ?.removePrefix("\"")
                    ?.removeSuffix("\"")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\'", "'")
                    ?.replace("\\n", "\n")
                    ?.replace("\\\\", "\\")
                    ?: ""

                if (xml.length < 30) {
                    showNotif("Workspace vacío", "#FFA000")
                } else {
                    vm.sendXml(xml)
                }
            }
        }
    }

    // ---- UI event observer ---------------------------------------------------

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
                            sentInstructions.clear()
                            sentInstructions.append("// Último programa enviado exitosamente")
                            showNotif("¡Cargado en FRANKY!", "#00C853")
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

    // ---- Helpers -------------------------------------------------------------

    private fun showNotif(msg: String, color: String) {
        if (!isAdded) return
        binding.webView.post {
            binding.webView.evaluateJavascript("showNotif('$msg','$color')", null)
        }
    }

    // ---- JS Bridge -----------------------------------------------------------

    inner class AndroidBridge {
        @JavascriptInterface
        fun enviar(xml: String) {
            if (xml.length < 30) {
                requireActivity().runOnUiThread { showNotif("Workspace vacío", "#FFA000") }
                return
            }
            vm.sendXml(xml)
        }

        @JavascriptInterface
        fun updateBlockCount(count: Int) {
            requireActivity().runOnUiThread {
                if (isAdded) binding.tvBlockCount.text = "$count bloques"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.webView.destroy()
        _binding = null
    }
}
