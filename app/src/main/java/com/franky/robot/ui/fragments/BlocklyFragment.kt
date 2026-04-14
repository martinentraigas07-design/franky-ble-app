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
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import com.franky.robot.MainActivity
import com.franky.robot.R
import com.franky.robot.databinding.FragmentBlocklyBinding
import com.franky.robot.ui.viewmodel.RobotViewModel
import com.franky.robot.ui.viewmodel.UiEvent
import kotlinx.coroutines.launch

class BlocklyFragment : Fragment() {

    companion object {
        private const val TAG = "BlocklyFragment"
        // The https base URL used by WebViewAssetLoader
        // All asset URLs become: https://appassets.androidplatform.net/assets/blockly/xxx
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val PAGE_URL   = "https://$ASSET_HOST/assets/blockly/index.html"
    }

    private var _b: FragmentBlocklyBinding? = null
    private val b get() = _b!!
    private val vm: RobotViewModel by lazy { (requireActivity() as MainActivity).viewModel }

    private val sentLog = StringBuilder("// Sin instrucciones enviadas")

    // ── WebViewAssetLoader — serves assets/blockly/* as https:// ──────────
    // This is the CORRECT modern way to load local files in WebView.
    // file:// + allowFileAccessFromFileURLs is deprecated and broken on API 30+.
    private lateinit var assetLoader: WebViewAssetLoader

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", AssetsPathHandler(requireContext()))
            .build()
    }

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
        b.toolbar.tbBadge.text    = "EDITOR"
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

    // ── WebView ────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true)

        b.webView.apply {
            setBackgroundColor(Color.parseColor("#1A1A1A"))

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess   = true
                // No need for deprecated allowFileAccessFromFileURLs —
                // WebViewAssetLoader serves everything as https://, so
                // same-origin policy is satisfied automatically.
            }

            webViewClient = object : WebViewClient() {

                // ── Intercept every request and serve local assets ──────
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    // Let the asset loader handle any URL on our fake https host
                    return request?.url?.let { assetLoader.shouldInterceptRequest(it) }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!isAdded) return
                    Log.d(TAG, "Page loaded: $url")
                    postDelayed({
                        if (isAdded) evaluateJavascript(
                            "if(window.resizeBlockly) resizeBlockly();", null)
                    }, 400)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    val url  = request?.url?.toString() ?: "?"
                    val desc = error?.description?.toString() ?: "?"
                    Log.e(TAG, "WebView error [$url]: $desc")
                    if (!isAdded || request?.isForMainFrame == false) return
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        b.statusDot.setBackgroundColor(
                            ContextCompat.getColor(requireContext(), R.color.danger))
                        b.tvStatusText.text = "Error cargando editor"
                    }
                }
            }

            // Forward console.log / error to Logcat
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    val lvl  = msg?.messageLevel()?.name ?: "LOG"
                    val text = msg?.message() ?: ""
                    val src  = msg?.sourceId()?.substringAfterLast('/') ?: ""
                    Log.d(TAG, "[$lvl] $src:${msg?.lineNumber()} — $text")
                    return true
                }
            }

            addJavascriptInterface(AndroidBridge(), "AndroidInterface")

            // Load via the https:// URL — asset loader intercepts and serves
            // the file from assets/blockly/index.html
            loadUrl(PAGE_URL)
        }

        b.tvStatusText.text = "Cargando editor Blockly…"
        if (isAdded) b.statusDot.setBackgroundColor(
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
                    ?.replace("\\'",  "'")
                    ?.replace("\\n",  "\n")
                    ?.replace("\\\\", "\\")
                    ?: ""
                Log.d(TAG, "XML length: ${xml.length}")
                if (xml.length < 30) showNotif("Workspace vacío — agregá bloques", "#FFA000")
                else vm.sendXml(xml)
            }
        }
    }

    // ── Events ─────────────────────────────────────────────────────────────

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiEvents.collect { event ->
                    when (event) {
                        is UiEvent.ProgSending -> {
                            b.pbXml.visibility     = View.VISIBLE
                            b.toolbar.tbBadge.text = "ENVIANDO"
                            b.tvStatusText.text    = "Enviando programa…"
                        }
                        is UiEvent.ProgSuccess -> {
                            b.pbXml.visibility     = View.GONE
                            b.toolbar.tbBadge.text = "OK"
                            b.tvStatusText.text    = "Programa cargado ✓"
                            sentLog.clear()
                            sentLog.append("// Último programa enviado exitosamente")
                            showNotif("¡Cargado en FRANKY!", "#00C853")
                        }
                        is UiEvent.ProgError -> {
                            b.pbXml.visibility     = View.GONE
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
            b.webView.evaluateJavascript(
                "if(window.showNotif) showNotif('$msg','$color');", null)
        }
    }

    // ── JS Bridge ──────────────────────────────────────────────────────────

    inner class AndroidBridge {

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

        @JavascriptInterface
        fun enviar(xml: String) {
            if (xml.length < 30) {
                requireActivity().runOnUiThread {
                    if (isAdded) showNotif("Workspace vacío", "#FFA000")
                }
            } else {
                vm.sendXml(xml)
            }
        }

        @JavascriptInterface
        fun updateBlockCount(count: Int) {
            requireActivity().runOnUiThread {
                if (isAdded) b.tvBlockCount.text = "$count bloques"
            }
        }

        @JavascriptInterface
        fun logError(msg: String) {
            Log.e(TAG, "JS: $msg")
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                b.statusDot.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.danger))
                b.tvStatusText.text = msg.take(80)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        b.webView.stopLoading()
        b.webView.clearHistory()
        b.webView.destroy()
        _b = null
    }
}
