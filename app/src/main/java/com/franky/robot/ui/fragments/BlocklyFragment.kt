package com.franky.robot.ui.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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

    companion object {
        private const val TAG = "BlocklyFragment"
    }

    private var _b: FragmentBlocklyBinding? = null
    private val b get() = _b!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    private val sentLog = StringBuilder("// Sin instrucciones enviadas")

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentBlocklyBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureToolbar()
        setupTabs()
        setupWebView()
        setupSendButton()
        observeEvents()
    }

    // ── Toolbar ────────────────────────────────────────────────────────────

    private fun configureToolbar() {
        b.toolbar.tbSubtitle.text = "Programación con Bloques"
        b.toolbar.tbBadge.text = "EDITOR"
    }

    // ── Tabs ───────────────────────────────────────────────────────────────

    private fun setupTabs() {
        selectTab(editorActive = true)
        b.tabEditor.setOnClickListener { selectTab(editorActive = true) }
        b.tabInstructions.setOnClickListener {
            b.tvInstructions.text = sentLog.toString()
            selectTab(editorActive = false)
        }
        b.btnBackFromBlockly.setOnClickListener { findNavController().popBackStack() }
    }

    private fun selectTab(editorActive: Boolean) {
        val ctx = requireContext()
        b.panelEditor.visibility       = if (editorActive) View.VISIBLE else View.GONE
        b.panelInstructions.visibility = if (editorActive) View.GONE    else View.VISIBLE

        b.tabEditor.background = ContextCompat.getDrawable(
            ctx, if (editorActive) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive)
        b.tabEditor.setTextColor(ContextCompat.getColor(
            ctx, if (editorActive) R.color.white else R.color.txt2))

        b.tabInstructions.background = ContextCompat.getDrawable(
            ctx, if (editorActive) R.drawable.bg_tab_inactive else R.drawable.bg_tab_active)
        b.tabInstructions.setTextColor(ContextCompat.getColor(
            ctx, if (editorActive) R.color.txt2 else R.color.white))
    }

    // ── WebView — all required settings for file:// + Blockly ─────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true) // enable Chrome devtools during dev

        b.webView.apply {
            setBackgroundColor(Color.parseColor("#1A1A1A"))

            settings.apply {
                javaScriptEnabled           = true
                domStorageEnabled           = true
                allowFileAccess             = true
                // Required for file:// to load other file:// scripts
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs  = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                // Avoid blocking mixed content on file:// pages
                @Suppress("DEPRECATION")
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Performance
                setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!isAdded) return
                    Log.d(TAG, "Page loaded: $url")
                    // Tell Blockly to resize in case layout shifted
                    postDelayed({
                        if (isAdded) evaluateJavascript(
                            "if(window.resizeBlockly) resizeBlockly();", null)
                    }, 300)
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    val url  = request?.url?.toString() ?: "?"
                    val desc = error?.description?.toString() ?: "unknown"
                    Log.e(TAG, "WebView error — $url : $desc")
                    if (!isAdded) return
                    requireActivity().runOnUiThread {
                        if (isAdded) {
                            b.statusDot.setBackgroundColor(
                                ContextCompat.getColor(requireContext(), R.color.danger))
                            b.tvStatusText.text = "Error: $desc"
                        }
                    }
                }
            }

            // Forward ALL console messages to Logcat for easy debugging
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    val level = msg?.messageLevel()?.name ?: "LOG"
                    val text  = msg?.message() ?: ""
                    val src   = msg?.sourceId()?.substringAfterLast('/') ?: ""
                    val line  = msg?.lineNumber() ?: 0
                    Log.d(TAG, "[$level] $src:$line — $text")
                    return true // suppress default WebView console spam in production
                }
            }

            addJavascriptInterface(AndroidBridge(), "AndroidInterface")

            // CRITICAL: load with file:// so relative paths resolve correctly
            loadUrl("file:///android_asset/blockly/index.html")
        }

        b.tvStatusText.text = "Cargando editor Blockly…"
        b.statusDot.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.warn))
    }

    // ── Send button ────────────────────────────────────────────────────────

    private fun setupSendButton() {
        b.btnSendBlockly.setOnClickListener {
            b.webView.evaluateJavascript("getXmlForAndroid()") { rawResult ->
                if (!isAdded) return@evaluateJavascript
                val xml = rawResult
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\'", "'")
                    ?.replace("\\n", "\n")
                    ?.replace("\\\\", "\\")
                    ?: ""
                Log.d(TAG, "XML length from Blockly: ${xml.length}")
                when {
                    xml.length < 30 -> showNotif("Workspace vacío — agregá bloques", "#FFA000")
                    else            -> vm.sendXml(xml)
                }
            }
        }
    }

    // ── ViewModel event observer ───────────────────────────────────────────

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiEvents.collect { event ->
                    when (event) {
                        is UiEvent.XmlSending -> {
                            b.pbXml.visibility = View.VISIBLE
                            b.toolbar.tbBadge.text = "ENVIANDO"
                            b.tvStatusText.text    = "Enviando programa…"
                        }
                        is UiEvent.XmlSuccess -> {
                            b.pbXml.visibility = View.GONE
                            b.toolbar.tbBadge.text = "OK"
                            b.tvStatusText.text    = "Programa cargado ✓"
                            sentLog.clear()
                            sentLog.append("// Último programa enviado exitosamente")
                            showNotif("¡Cargado en FRANKY!", "#00C853")
                        }
                        is UiEvent.XmlError -> {
                            b.pbXml.visibility = View.GONE
                            b.toolbar.tbBadge.text = "ERROR"
                            b.tvStatusText.text    = "Error al enviar"
                            showNotif("Error al enviar", "#E53935")
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun showNotif(msg: String, color: String) {
        if (!isAdded) return
        b.webView.post {
            b.webView.evaluateJavascript("showNotif('$msg','$color')", null)
        }
    }

    // ── JavaScript Bridge ──────────────────────────────────────────────────

    inner class AndroidBridge {

        /** Called when Blockly finishes injecting (editor is ready) */
        @JavascriptInterface
        fun onEditorReady() {
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                b.statusDot.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.ok))
                b.tvStatusText.text    = "Editor listo"
                b.toolbar.tbBadge.text = "EDITOR"
            }
        }

        /** Called from enviarCodigo() in the HTML (button inside Blockly) */
        @JavascriptInterface
        fun enviar(xml: String) {
            when {
                xml.length < 30 -> requireActivity().runOnUiThread {
                    if (isAdded) showNotif("Workspace vacío", "#FFA000")
                }
                else -> vm.sendXml(xml)
            }
        }

        /** Block count update */
        @JavascriptInterface
        fun updateBlockCount(count: Int) {
            requireActivity().runOnUiThread {
                if (isAdded) b.tvBlockCount.text = "$count bloques"
            }
        }

        /** Error logging from JavaScript */
        @JavascriptInterface
        fun logError(msg: String) {
            Log.e(TAG, "JS Error: $msg")
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                b.statusDot.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.danger))
                b.tvStatusText.text = "Error JS: ${msg.take(60)}"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        b.webView.apply {
            stopLoading()
            clearHistory()
            destroy()
        }
        _b = null
    }
}
