package com.taygan.tool

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.taygan.tool.api.ShopeeSessionManager

class ShopeeLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var btnDone: Button
    private lateinit var sessionManager: ShopeeSessionManager

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shopee_login)

        sessionManager = ShopeeSessionManager.getInstance(this)
        tvStatus = findViewById(R.id.tvCookieStatus)
        btnDone = findViewById(R.id.btnDoneExtract)
        webView = findViewById(R.id.webViewShopee)

        setupWebView()
        setupListeners()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                extractCookies()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                extractCookies()
            }
        }

        webView.webChromeClient = WebChromeClient()
        webView.loadUrl("https://shopee.vn/buyer/login")
    }

    private fun setupListeners() {
        btnDone.setOnClickListener {
            val extracted = extractCookies()
            if (extracted) {
                Toast.makeText(this, "Đã lưu Cookie & CSRF Token thành công!", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "Chưa tìm thấy phiên đăng nhập Shopee, vui lòng đăng nhập trước!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun extractCookies(): Boolean {
        val cookieString = CookieManager.getInstance().getCookie("https://shopee.vn") ?: return false
        if (cookieString.contains("csrftoken=") || cookieString.contains("SPC_EC=")) {
            sessionManager.cookie = cookieString
            tvStatus.text = "✓ Đã bắt được Cookie & Token Shopee!"
            tvStatus.setTextColor(getColor(R.color.status_connected))
            return true
        }
        return false
    }
}
