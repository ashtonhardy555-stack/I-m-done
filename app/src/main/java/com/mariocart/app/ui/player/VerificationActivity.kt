package com.mariocart.app.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.mariocart.app.R

class VerificationActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "extra_url"
        
        fun newIntent(context: Context, url: String): Intent {
            return Intent(context, VerificationActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple layout programmatically to avoid needing a layout XML
        val root = android.widget.FrameLayout(this)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                    // Check if the user has returned to a "normal" URL or if the challenge is gone
                    if (url != null && !isChallengeUrl(url)) {
                        setResult(RESULT_OK)
                        finish()
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false // Let WebView handle redirects
                }
            }
        }
        
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }

        root.addView(webView)
        root.addView(progressBar, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        
        setContentView(root)

        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        webView.loadUrl(url)
    }

    private fun isChallengeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("verify") || lower.contains("captcha") || 
               lower.contains("checkpoint") || lower.contains("challenge")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
