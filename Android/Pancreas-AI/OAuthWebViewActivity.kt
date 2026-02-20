package com.pancreas.ai

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class OAuthWebViewActivity : AppCompatActivity() {

    private val TAG = "OAuthWebView"
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_oauth_webview)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Connect to Dexcom"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progressBar = findViewById(R.id.progressBar)
        tvStatus    = findViewById(R.id.tvStatus)
        webView     = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/114 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE

                // Intercept the redirect BEFORE the WebView tries to load it
                if (url != null && url.startsWith(REDIRECT_URI)) {
                    progressBar.visibility = View.GONE
                    handleRedirect(url)
                    return
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith(REDIRECT_URI)) {
                    handleRedirect(url)
                    return true   // we're handling it — don't load
                }
                return false      // let WebView load normally
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }
        }

        // Load auth URL
        val authUrl = CredentialsManager.buildAuthUrl(this)
        Log.d(TAG, "Loading auth URL: $authUrl")
        tvStatus.text = "Loading Dexcom login…"
        webView.loadUrl(authUrl)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    private fun handleRedirect(url: String) {
        Log.d(TAG, "Redirect intercepted: $url")
        val uri   = android.net.Uri.parse(url)
        val code  = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        webView.visibility  = View.GONE
        progressBar.visibility = View.VISIBLE
        tvStatus.text       = "Authorizing…"
        tvStatus.visibility = View.VISIBLE

        when {
            code != null -> {
                lifecycleScope.launch {
                    try {
                        GlucoseRepository(applicationContext).exchangeAuthCode(code)
                        tvStatus.text = "✓ Connected!"
                        tvStatus.setTextColor(Color.parseColor("#00E676"))
                        progressBar.visibility = View.GONE
                        Toast.makeText(this@OAuthWebViewActivity,
                            "✓ Connected to Dexcom!", Toast.LENGTH_SHORT).show()
                        // Brief pause so user sees success, then return
                        kotlinx.coroutines.delay(800)
                        setResult(RESULT_OK)
                        finish()
                    } catch (e: Exception) {
                        Log.e(TAG, "Token exchange failed", e)
                        tvStatus.text = "✗ ${e.message}"
                        tvStatus.setTextColor(Color.parseColor("#FF4444"))
                        progressBar.visibility = View.GONE
                    }
                }
            }
            error != null -> {
                tvStatus.text = "✗ Authorization denied: $error"
                tvStatus.setTextColor(Color.parseColor("#FF4444"))
                progressBar.visibility = View.GONE
            }
            else -> {
                tvStatus.text = "✗ Invalid redirect response"
                tvStatus.setTextColor(Color.parseColor("#FF8800"))
                progressBar.visibility = View.GONE
            }
        }
    }
}
