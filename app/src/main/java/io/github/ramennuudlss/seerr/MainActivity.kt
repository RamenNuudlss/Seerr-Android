package io.github.ramennuudlss.seerr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.ramennuudlss.seerr.databinding.ActivityMainBinding
import io.github.ramennuudlss.seerr.databinding.DialogHttpAuthBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val bridge = WebBridge()
    private var baseUrl: String = ""

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastBackPress = 0L
    private var mainFrameFailed = false
    private var bridgeInstalled = false
    private var webViewDestroyed = false

    /** Popup whose Plex sign-in page was handed to a Custom Tab. */
    private var plexPopup: WebView? = null
    private var popupDialog: Dialog? = null
    private val authAttempts = mutableMapOf<String, Int>()

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            fileCallback = null
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                prefs.notificationsEnabled = false
                RequestNotifier.schedule(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val server = prefs.serverUrl
        if (server == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        baseUrl = server

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        setupWebView()
        setupRefresh()

        binding.retryButton.setOnClickListener { retry() }
        binding.errorSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        val restored = savedInstanceState?.let { binding.webView.restoreState(it) } != null
        if (!restored) binding.webView.loadUrl(urlFor(intent) ?: baseUrl)

        maybeAskNotificationPermission()
        InstantAlerts.ensureRunning(this)
        UpdateChecker.checkIfDue(this)
    }

    // ---------------------------------------------------------------- layout

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            if (customView != null) {
                v.setPadding(0, 0, 0, 0)
            } else {
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupRefresh() {
        binding.swipeRefresh.apply {
            setColorSchemeColors(getColor(R.color.seerr_accent_light))
            setProgressBackgroundColorSchemeColor(getColor(R.color.seerr_surface))
            setOnChildScrollUpCallback { _, _ ->
                binding.webView.scrollY > 0 || !bridge.pullAllowed
            }
            setOnRefreshListener {
                if (binding.errorView.isVisible) retry() else binding.webView.reload()
            }
        }
    }

    // ---------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = binding.webView
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        wv.setBackgroundColor(getColor(R.color.seerr_bg))
        configureSettings(wv.settings)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        // Expose the bridge only to the user's own server.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.addWebMessageListener(
                    wv, WebBridge.NAME, setOf(originOf(baseUrl))
                ) { _, message, _, _, _ -> bridge.onMessage(message.data) }
                bridgeInstalled = true
            } catch (_: IllegalArgumentException) {
                bridgeInstalled = false
            }
        }

        wv.webViewClient = MainClient()
        wv.webChromeClient = MainChrome()
        wv.setDownloadListener { url, _, _, _, _ -> openExternal(url) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureSettings(s: WebSettings) {
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        if (!s.userAgentString.contains(Server.USER_AGENT_SUFFIX)) {
            s.userAgentString = "${s.userAgentString} ${Server.USER_AGENT_SUFFIX}/${BuildConfig.VERSION_NAME}"
        }
    }

    private fun originOf(url: String): String {
        val u = Uri.parse(url)
        return buildString {
            append(u.scheme).append("://").append(u.host)
            if (u.port != -1) append(':').append(u.port)
        }
    }

    private inner class MainClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
            if (scheme != "http" && scheme != "https") {
                openNonHttp(uri)
                return true
            }
            if (Server.isSameOrigin(uri, baseUrl)) return false
            // Redirects (e.g. an Authelia/Authentik login in front of Seerr) and frames stay
            // in the app; links the user taps to other sites open in their apps/browser.
            if (!request.isForMainFrame || request.isRedirect || !request.hasGesture()) return false
            openExternal(uri.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            mainFrameFailed = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.swipeRefresh.isRefreshing = false
            if (!mainFrameFailed) {
                hideError()
                authAttempts.clear()
            }
            if (bridgeInstalled && url != null && Server.isSameOrigin(Uri.parse(url), baseUrl)) {
                view.evaluateJavascript(WebBridge.PULL_SCRIPT, null)
            }
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                mainFrameFailed = true
                showError(error.description?.toString().orEmpty())
            }
        }

        override fun onReceivedHttpError(
            view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse,
        ) {
            if (request.isForMainFrame && errorResponse.statusCode in 502..504 &&
                Server.isSameOrigin(request.url, baseUrl)
            ) {
                mainFrameFailed = true
                showError("The server responded with HTTP ${errorResponse.statusCode}. Seerr may be starting up or offline.")
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (Server.isSameOrigin(Uri.parse(error.url), baseUrl)) {
                mainFrameFailed = true
                showError(
                    "The server's security certificate isn't trusted. If you use your own " +
                        "certificate authority, install its certificate in Android settings."
                )
            }
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView, handler: HttpAuthHandler, host: String, realm: String?,
        ) {
            val attempts = authAttempts.getOrDefault(host, 0)
            authAttempts[host] = attempts + 1
            val saved = prefs.httpAuth(host)
            if (saved != null && attempts == 0) {
                handler.proceed(saved.first, saved.second)
            } else {
                showHttpAuthDialog(host, handler)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer crashed or was killed to free memory: rebuild the screen.
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            if (view === binding.webView) webViewDestroyed = true
            recreate()
            return true
        }
    }

    private inner class MainChrome : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progress.isVisible = newProgress in 1..99
            binding.progress.setProgressCompat(newProgress, true)
        }

        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message,
        ): Boolean = createPopup(resultMsg)

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                fileChooser.launch(fileChooserParams.createIntent())
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            binding.fullscreenContainer.addView(view)
            binding.fullscreenContainer.isVisible = true
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            ViewCompat.requestApplyInsets(binding.root)
        }

        override fun onHideCustomView() = hideCustomView()
    }

    private fun hideCustomView() {
        val view = customView ?: return
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.isVisible = false
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(binding.root)
    }

    // ---------------------------------------------------------------- popups (Plex login, new-tab links)

    /**
     * Seerr opens Plex sign-in with window.open("/login/plex/loading") and then points that
     * window at app.plex.tv while the main page polls Plex for the result. Links with
     * target="_blank" also land here. Route each popup to the right place.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createPopup(resultMsg: Message): Boolean {
        val popup = WebView(this)
        popup.setBackgroundColor(getColor(R.color.seerr_bg))
        configureSettings(popup.settings)
        popup.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

        var decided = false
        fun route(url: String): Boolean {
            if (decided || url == "about:blank") return false
            val uri = Uri.parse(url)
            if (Server.isSameOrigin(uri, baseUrl)) {
                if (uri.path?.endsWith("/login/plex/loading") == true) return false
                decided = true
                binding.webView.loadUrl(url)
                disposePopup(popup)
                return true
            }
            decided = true
            if (isPlexAuth(uri)) {
                return if (prefs.plexInBrowser) {
                    plexPopup = popup
                    openCustomTab(url)
                    true
                } else {
                    binding.root.post { showPopupDialog(popup) }
                    false
                }
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") openExternal(url) else openNonHttp(uri)
            disposePopup(popup)
            return true
        }

        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                route(request.url.toString())

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && route(url)) view.stopLoading()
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) = onPopupClosed(popup)
        }

        (resultMsg.obj as WebView.WebViewTransport).webView = popup
        resultMsg.sendToTarget()
        return true
    }

    private fun isPlexAuth(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host == "plex.tv" || host.endsWith(".plex.tv")
    }

    private fun showPopupDialog(popup: WebView) {
        val dialog = Dialog(this, R.style.Theme_Seerr)
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.plex_sign_in)
            setBackgroundColor(getColor(R.color.seerr_surface))
            setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
            navigationContentDescription = getString(R.string.close)
            setNavigationOnClickListener { dialog.dismiss() }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.seerr_bg))
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(popup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        dialog.setContentView(layout)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnDismissListener {
            if (popupDialog === dialog) popupDialog = null
            disposePopup(popup)
        }
        popupDialog = dialog
        dialog.show()
    }

    private fun onPopupClosed(popup: WebView) {
        popupDialog?.dismiss()
        popupDialog = null
        if (popup === plexPopup) {
            plexPopup = null
            // Plex sign-in finished: close the Custom Tab by bringing the app back to front.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        disposePopup(popup)
    }

    private fun disposePopup(popup: WebView) {
        binding.root.post {
            (popup.parent as? ViewGroup)?.removeView(popup)
            popup.stopLoading()
            popup.destroy()
        }
    }

    // ---------------------------------------------------------------- links

    private fun openExternal(url: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCustomTab(url: String) {
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(getColor(R.color.seerr_surface))
            .setNavigationBarColor(getColor(R.color.seerr_bg))
            .build()
        val tab = CustomTabsIntent.Builder()
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setDefaultColorSchemeParams(colors)
            .setShowTitle(true)
            .build()
        try {
            tab.launchUrl(this, Uri.parse(url))
        } catch (_: ActivityNotFoundException) {
            openExternal(url)
        }
    }

    private fun openNonHttp(uri: Uri) {
        if (uri.scheme.equals("intent", true)) {
            try {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
                try {
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    intent.getStringExtra("browser_fallback_url")?.let { openExternal(it) }
                }
            } catch (_: Exception) {
                // Malformed intent URI: ignore.
            }
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    /** Works out which page to open for a launcher shortcut, notification or share. */
    private fun urlFor(intent: Intent?): String? {
        intent ?: return null
        intent.getStringExtra(EXTRA_PATH)?.let { path ->
            return baseUrl + if (path.startsWith("/")) path else "/$path"
        }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (text.isEmpty()) return null
            Regex("""themoviedb\.org/(movie|tv)/(\d+)""").find(text)?.let { m ->
                return "$baseUrl/${m.groupValues[1]}/${m.groupValues[2]}"
            }
            val query = text.replace(Regex("""https?://\S+"""), " ")
                .lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?.take(120) ?: return null
            return "$baseUrl/search?query=${Uri.encode(query)}"
        }
        return null
    }

    // ---------------------------------------------------------------- dialogs & errors

    private fun showHttpAuthDialog(host: String, handler: HttpAuthHandler) {
        val view = DialogHttpAuthBinding.inflate(layoutInflater)
        prefs.httpAuth(host)?.let { view.username.setText(it.first) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.http_auth_title, host))
            .setView(view.root)
            .setCancelable(false)
            .setPositiveButton(R.string.sign_in) { _, _ ->
                val user = view.username.text?.toString().orEmpty()
                val pass = view.password.text?.toString().orEmpty()
                if (view.remember.isChecked) prefs.setHttpAuth(host, user, pass)
                handler.proceed(user, pass)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> handler.cancel() }
            .show()
    }

    private fun showError(message: String) {
        binding.swipeRefresh.isRefreshing = false
        binding.errorMessage.text = buildString {
            if (message.isNotBlank()) append(message.removePrefix("net::")).append("\n\n")
            append(baseUrl)
        }
        binding.errorView.isVisible = true
    }

    private fun hideError() {
        binding.errorView.isVisible = false
    }

    private fun retry() {
        hideError()
        val current = binding.webView.url
        if (current.isNullOrEmpty() || current == "about:blank") binding.webView.loadUrl(baseUrl)
        else binding.webView.reload()
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!prefs.notificationsEnabled || prefs.askedNotificationPermission) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        prefs.askedNotificationPermission = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---------------------------------------------------------------- back

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> hideCustomView()
                binding.errorView.isVisible -> {
                    if (binding.webView.canGoBack()) {
                        hideError()
                        binding.webView.goBack()
                    } else exitOrWarn()
                }
                else -> binding.webView.evaluateJavascript(WebBridge.BACK_SCRIPT) { result ->
                    if (result == "true") return@evaluateJavascript
                    if (binding.webView.canGoBack()) binding.webView.goBack() else exitOrWarn()
                }
            }
        }
    }

    private fun exitOrWarn() {
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
            finish()
        } else {
            lastBackPress = now
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::binding.isInitialized) return
        urlFor(intent)?.let {
            hideError()
            binding.webView.loadUrl(it)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        val server = prefs.serverUrl
        if (server == null || server != baseUrl) {
            // Server was changed or removed in settings.
            startActivity(
                Intent(this, if (server == null) SetupActivity::class.java else MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return
        }
        binding.swipeRefresh.isEnabled = prefs.pullToRefresh
        binding.webView.onResume()
    }

    override fun onPause() {
        if (::binding.isInitialized) binding.webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::binding.isInitialized) binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        popupDialog?.dismiss()
        if (::binding.isInitialized && !webViewDestroyed) {
            (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
