package com.vedstudy.streamlauncher

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vedstudy.streamlauncher.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    private val keyGenerator = KeyGenerator()

    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val originalOrientation by lazy { requestedOrientation }

    companion object {
        private const val TARGET_URL = "https://stream.testuk.org"
        private const val DOMAIN = "stream.testuk.org"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        setupBackPressHandler()
        setupListeners()
        setupWebView()

        // Begin bootstrap check
        checkAndStartWorkflow()
    }

    private fun setupListeners() {
        binding.btnForce.setOnClickListener {
            startKeyGenerationFlow()
        }

        binding.swipeRefresh.setOnRefreshListener {
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
            delay(800L)

            if (sessionManager.isSessionValid()) {
                val remHours = sessionManager.getRemainingHours()
                val cookie = sessionManager.getSessionCookie()!!
                val expiry = sessionManager.getSessionExpiry()

                setUiState(
                    status = getString(R.string.status_active),
                    detail = String.format("Valid 24h key detected (%.1f hours remaining).", remHours),
                    progressVal = 0.60f,
                    etaSec = 2,
                    tag = getString(R.string.tag_authenticated)
                )
                delay(1000L)
                launchStreamWebView(cookie, expiry)
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
                delay(1000L)
                startKeyGenerationFlow()
            }
        }
    }

    private fun startKeyGenerationFlow() {
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
                delay(1200L)
                launchStreamWebView(result.sessionCookie, result.sessionExpiry)
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

        val chromeMobileUa = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        webSettings.userAgentString = chromeMobileUa

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.swipeRefresh.isRefreshing = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    binding.swipeRefresh.isRefreshing = false
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
                binding.swipeRefresh.visibility = View.GONE
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
        binding.swipeRefresh.visibility = View.VISIBLE
    }

    private fun launchStreamWebView(sessionCookie: String, expiryMs: Long) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        // Inject 24h authenticated cookies into WebView
        cookieManager.setCookie(TARGET_URL, "session=$sessionCookie; Domain=$DOMAIN; Path=/; Secure; HttpOnly")
        if (expiryMs > 0) {
            cookieManager.setCookie(TARGET_URL, "session_expiry=$expiryMs; Domain=$DOMAIN; Path=/; Secure")
        }
        cookieManager.flush()

        // Transition: Hide Splash and Show WebView
        binding.splashLayout.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE

        binding.webView.loadUrl(TARGET_URL)
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
