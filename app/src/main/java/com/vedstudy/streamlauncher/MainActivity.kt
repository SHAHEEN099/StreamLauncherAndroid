package com.vedstudy.streamlauncher

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.vedstudy.streamlauncher.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    private val keyGenerator = KeyGenerator()

    // Dedicated OkHttp client for shouldInterceptRequest to guarantee X-Requested-With header is stripped
    private val interceptClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val originalOrientation by lazy { requestedOrientation }
    private var isGenerating = false

    companion object {
        private const val TARGET_URL = "https://stream.testuk.org"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        private const val TAG = "StreamLauncher"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        setupBackPressHandler()
        setupListeners()
        setupWebView()

        checkAndStartWorkflow()
    }

    private fun setupListeners() {
        binding.btnForce.setOnClickListener {
            sessionManager.clearSession()
            startKeyGenerationFlow()
        }

        binding.btnNewKeyWeb.setOnClickListener {
            sessionManager.clearSession()
            binding.webContainer.visibility = View.GONE
            binding.splashLayout.visibility = View.VISIBLE
            startKeyGenerationFlow()
        }

        binding.btnRefreshWeb.setOnClickListener {
            reloadStreamWithCookies()
        }

        binding.swipeRefresh.setOnRefreshListener {
            reloadStreamWithCookies()
        }
    }

    private fun reloadStreamWithCookies() {
        val cookie = sessionManager.getSessionCookie()
        val expiry = sessionManager.getSessionExpiry()
        if (!cookie.isNullOrEmpty()) {
            lifecycleScope.launch {
                injectCookiesAndLaunch(cookie, expiry)
            }
        } else {
            binding.webView.reload()
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customVideoView != null) {
                    hideCustomVideoView()
                } else if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun checkAndStartWorkflow() {
        lifecycleScope.launch {
            setUiState(
                status = getString(R.string.status_checking),
                detail = "Verifying cryptographic tokens in local registry...",
                progressVal = 0.15f,
                etaSec = 0,
                tag = getString(R.string.tag_checking)
            )
            delay(600L)

            if (sessionManager.isSessionValid()) {
                val remHours = sessionManager.getRemainingHours()
                val cookie = sessionManager.getSessionCookie()!!
                val expiry = sessionManager.getSessionExpiry()

                setUiState(
                    status = getString(R.string.status_active),
                    detail = String.format("Valid 24h key detected (%.1f hours remaining).", remHours),
                    progressVal = 0.60f,
                    etaSec = 1,
                    tag = getString(R.string.tag_authenticated)
                )
                delay(800L)
                injectCookiesAndLaunch(cookie, expiry)
            } else {
                val hasOldCookie = !sessionManager.getSessionCookie().isNullOrEmpty()
                if (hasOldCookie) {
                    setUiState(
                        status = getString(R.string.status_expired),
                        detail = "Existing session expired. Generating fresh 24h key...",
                        progressVal = 0.05f,
                        etaSec = 295,
                        tag = getString(R.string.tag_expired)
                    )
                } else {
                    setUiState(
                        status = "NO SESSION FOUND",
                        detail = "Initializing fresh 24-hour key generation pipeline (7 Steps)...",
                        progressVal = 0.05f,
                        etaSec = 295,
                        tag = "[NEW USER]"
                    )
                }
                delay(800L)
                startKeyGenerationFlow()
            }
        }
    }

    private fun startKeyGenerationFlow() {
        if (isGenerating) return
        isGenerating = true

        lifecycleScope.launch {
            binding.btnForce.isEnabled = false

            val result = keyGenerator.runKeyGenerator { status, detail, pct, etaSec ->
                lifecycleScope.launch(Dispatchers.Main) {
                    setUiState(
                        status = status,
                        detail = detail,
                        progressVal = pct,
                        etaSec = etaSec,
                        tag = getString(R.string.tag_generating)
                    )
                }
            }

            isGenerating = false

            if (result.success && result.sessionCookie != null) {
                sessionManager.saveSession(
                    cookie = result.sessionCookie,
                    expiryMs = result.sessionExpiry,
                    jwtKey = result.jwtKey
                )

                setUiState(
                    status = "SESSION ACTIVATED!",
                    detail = "24-Hour streaming key verified successfully. Launching app...",
                    progressVal = 1.0f,
                    etaSec = 0,
                    tag = "[SUCCESS]"
                )
                delay(1000L)
                injectCookiesAndLaunch(result.sessionCookie, result.sessionExpiry)
            } else {
                setUiState(
                    status = getString(R.string.status_error),
                    detail = result.errorMessage ?: "Key generation failed. Click 'Force New Key' to retry.",
                    progressVal = 0.0f,
                    etaSec = 0,
                    tag = "[ERROR]"
                )
                binding.btnForce.isEnabled = true
            }
        }
    }

    private fun setUiState(status: String, detail: String, progressVal: Float, etaSec: Int, tag: String) {
        binding.tvStatusTitle.text = status
        binding.tvDetail.text = detail
        binding.progressBar.progress = (progressVal * 100).toInt()
        binding.tvPct.text = "${(progressVal * 100).toInt()}%"
        binding.tvTag.text = tag
        binding.tvTicker.text = "● $detail"

        if (etaSec > 0) {
            val mins = etaSec / 60
            val secs = etaSec % 60
            binding.tvEta.text = String.format("EST. REMAINING: %02d:%02d", mins, secs)
        } else if (progressVal >= 1.0f) {
            binding.tvEta.text = "COMPLETED"
        } else {
            binding.tvEta.text = "CALCULATING..."
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings = binding.webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.mediaPlaybackRequiresUserGesture = false
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.userAgentString = USER_AGENT

        // Try setting allowlist for WebSettingsCompat if available on device
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webSettings, emptySet())
            }
        } catch (_: Exception) {}

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlNavigation(view, url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleUrlNavigation(view, url)
            }

            // Universal interceptor to completely strip X-Requested-With header on all stream.testuk.org requests
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val uri = request.url ?: return null
                val host = uri.host?.lowercase() ?: ""
                val method = request.method ?: "GET"

                // Strip X-Requested-With header on all GET requests to testuk.org
                if (method.equals("GET", ignoreCase = true) && (host.contains("testuk.org") || host.contains("vedstudy"))) {
                    try {
                        val okReqBuilder = Request.Builder()
                            .url(url)
                            .get()

                        // Forward original headers EXCEPT X-Requested-With
                        for ((name, value) in request.requestHeaders) {
                            if (!name.equals("X-Requested-With", ignoreCase = true) &&
                                !name.equals("x-requested-with", ignoreCase = true)) {
                                okReqBuilder.header(name, value)
                            }
                        }

                        // Explicit clean User-Agent without WebView markers
                        okReqBuilder.header("User-Agent", USER_AGENT)

                        // Attach verified session cookies
                        val sessionCookie = sessionManager.getSessionCookie()
                        val expiry = sessionManager.getSessionExpiry()
                        val cmCookie = CookieManager.getInstance().getCookie(url) ?: ""

                        val cookieList = mutableListOf<String>()
                        if (!sessionCookie.isNullOrEmpty()) {
                            cookieList.add("session=$sessionCookie")
                        }
                        if (expiry > 0) {
                            cookieList.add("session_expiry=$expiry")
                        }
                        if (cmCookie.isNotEmpty()) {
                            for (c in cmCookie.split(";")) {
                                val trimmed = c.trim()
                                if (trimmed.isNotEmpty() && !trimmed.startsWith("session=") && !trimmed.startsWith("session_expiry=")) {
                                    cookieList.add(trimmed)
                                }
                            }
                        }
                        if (cookieList.isNotEmpty()) {
                            okReqBuilder.header("Cookie", cookieList.joinToString("; "))
                        }

                        val okResponse = interceptClient.newCall(okReqBuilder.build()).execute()

                        val contentType = okResponse.header("Content-Type", "text/html; charset=utf-8") ?: "text/html; charset=utf-8"
                        val mimeType = contentType.substringBefore(";").trim()
                        val encoding = if (contentType.contains("charset=")) {
                            contentType.substringAfter("charset=").trim()
                        } else {
                            "UTF-8"
                        }

                        val respHeaders = mutableMapOf<String, String>()
                        for ((hName, hVal) in okResponse.headers) {
                            // Strip Content-Encoding & Transfer-Encoding as OkHttp already decodes the stream
                            if (!hName.equals("Content-Encoding", ignoreCase = true) &&
                                !hName.equals("Transfer-Encoding", ignoreCase = true)) {
                                respHeaders[hName] = hVal
                            }
                        }

                        return WebResourceResponse(
                            mimeType,
                            encoding,
                            okResponse.code,
                            if (okResponse.message.isNotEmpty()) okResponse.message else "OK",
                            respHeaders,
                            okResponse.body?.byteStream()
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to intercept request for $url: ${e.message}")
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.swipeRefresh.isRefreshing = true

                if (url != null) {
                    // Suppress any stray navigation to vedstudy.com landing page
                    if (url.contains("vedstudy.com")) {
                        view?.stopLoading()
                        view?.loadUrl(TARGET_URL)
                        return
                    }

                    // Detect server-side session expiry redirecting to /keygenerate
                    if (url.contains("/keygenerate") && !isGenerating && binding.webContainer.visibility == View.VISIBLE) {
                        Log.i(TAG, "Redirect to /keygenerate detected. Initiating key refresh...")
                        sessionManager.clearSession()
                        binding.webContainer.visibility = View.GONE
                        binding.splashLayout.visibility = View.VISIBLE
                        startKeyGenerationFlow()
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefresh.isRefreshing = false

                // Inject session_expiry into client-side JS document.cookie
                val expiry = sessionManager.getSessionExpiry()
                if (expiry > 0) {
                    view?.evaluateJavascript(
                        """
                        try {
                            document.cookie = "session_expiry=$expiry; path=/; max-age=86400";
                            if (window.countdownManager && typeof window.countdownManager.init === 'function') {
                                window.countdownManager.init();
                            }
                        } catch(e) {}
                        """.trimIndent(), null
                    )
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    binding.swipeRefresh.isRefreshing = false
                    val failingUrl = request.url?.toString() ?: ""
                    if (failingUrl.startsWith("intent:") || failingUrl.startsWith("android-app:")) {
                        handleUrlNavigation(view, failingUrl)
                    }
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customVideoView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                customVideoView = view
                customViewCallback = callback
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                binding.webContainer.visibility = View.GONE
                binding.customViewContainer.apply {
                    visibility = View.VISIBLE
                    addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }

            override fun onHideCustomView() {
                hideCustomVideoView()
            }
        }
    }

    private fun handleUrlNavigation(view: WebView?, url: String): Boolean {
        // Prevent loading the dummy vedstudy.com landing page with the Open Now button
        if (url.contains("vedstudy.com")) {
            Log.d(TAG, "Suppressed vedstudy.com navigation; redirecting directly to stream.testuk.org")
            view?.loadUrl(TARGET_URL)
            return true
        }

        // Handle intent:// and android-app:// schemes
        if (url.startsWith("intent://") || url.startsWith("android-app://")) {
            if (url.contains("testuk.org") || url.contains("chrome") || url.contains("vedstudy")) {
                Log.d(TAG, "Suppressed external intent: $url")
                return true
            }

            try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
                startActivity(intent)
            } catch (_: Exception) {}
            return true
        }

        // Standard HTTP / HTTPS URLs
        if (url.startsWith("http://") || url.startsWith("https://")) {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: ""

            // External social / chat links
            if (host.contains("t.me") || host.contains("whatsapp.com") || host.contains("telegram.me")) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (_: Exception) {}
                return true
            }

            return false
        }

        // Custom protocol schemes
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {}
        return true
    }

    private fun hideCustomVideoView() {
        if (customVideoView == null) return

        requestedOrientation = originalOrientation
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        binding.customViewContainer.apply {
            visibility = View.GONE
            removeView(customVideoView)
        }
        customViewCallback?.onCustomViewHidden()
        customVideoView = null
        customViewCallback = null
        binding.webContainer.visibility = View.VISIBLE
    }

    private suspend fun injectCookiesAndLaunch(sessionCookie: String, expiryMs: Long) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        val targetUrl = TARGET_URL
        val host = "stream.testuk.org"
        val domain = ".testuk.org"

        // 1. Inject host-only and domain cookies for session
        setCookieAsync(cookieManager, targetUrl, "session=$sessionCookie; Path=/; Secure; HttpOnly; SameSite=Lax")
        setCookieAsync(cookieManager, targetUrl, "session=$sessionCookie; Path=/; HttpOnly")
        setCookieAsync(cookieManager, targetUrl, "session=$sessionCookie; Domain=$host; Path=/; Secure; HttpOnly; SameSite=Lax")
        setCookieAsync(cookieManager, targetUrl, "session=$sessionCookie; Domain=$domain; Path=/; Secure; HttpOnly; SameSite=Lax")

        // 2. Inject session_expiry cookie (WITHOUT HttpOnly so document.cookie can read it)
        if (expiryMs > 0) {
            setCookieAsync(cookieManager, targetUrl, "session_expiry=$expiryMs; Path=/; Secure; SameSite=Lax")
            setCookieAsync(cookieManager, targetUrl, "session_expiry=$expiryMs; Path=/")
            setCookieAsync(cookieManager, targetUrl, "session_expiry=$expiryMs; Domain=$host; Path=/; Secure; SameSite=Lax")
            setCookieAsync(cookieManager, targetUrl, "session_expiry=$expiryMs; Domain=$domain; Path=/; Secure; SameSite=Lax")
        }

        // Commit cookies to storage
        cookieManager.flush()
        delay(300L)

        val verified = cookieManager.getCookie(targetUrl) ?: ""
        Log.i(TAG, "Verified cookies in CookieManager: $verified")

        withContext(Dispatchers.Main) {
            binding.splashLayout.visibility = View.GONE
            binding.webContainer.visibility = View.VISIBLE

            val headers = mutableMapOf(
                "Cookie" to "session=$sessionCookie; session_expiry=$expiryMs",
                "Referer" to "https://stream.testuk.org/"
            )
            binding.webView.loadUrl(targetUrl, headers)
        }
    }

    private suspend fun setCookieAsync(cookieManager: CookieManager, url: String, cookieValue: String): Boolean {
        return suspendCancellableCoroutine { continuation ->
            cookieManager.setCookie(url, cookieValue) { success ->
                if (continuation.isActive) {
                    continuation.resume(success)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
