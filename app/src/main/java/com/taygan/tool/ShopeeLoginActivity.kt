package com.taygan.tool

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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

    private val tag = "ShopeeLoginActivity"
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
        settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Đăng ký NativeBridge nối giữa JavaScript trang Shopee và App Android (Chuẩn như shopee-voucher-bot.apk)
        webView.addJavascriptInterface(ShopeeNativeBridge(), "NativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectHookScript()
                extractCookies()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectHookScript()
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
                Toast.makeText(this, "Đã lưu Cookie & Token Shopee thành công!", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(
                    this,
                    "Chưa tìm thấy phiên đăng nhập Shopee, vui lòng đăng nhập trước!",
                    Toast.LENGTH_LONG
                ).show()
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

    /**
     * Tiêm mã Hook JavaScript chuẩn 100% từ APK gốc vn.taygan.shopeevoucherbot
     * Tự động bắt Token bí mật af-ac-enc-sz-token và security_device_fingerprint
     */
    private fun injectHookScript() {
        val jsHook = """
            (function() {
                if (window.__svbHooked) return;
                window.__svbHooked = true;
                window.__svbFingerprint = '';

                function setFp(fp, source) {
                    if (!fp || typeof fp !== 'string' || fp.length < 10) return false;
                    window.__svbFingerprint = fp;
                    if (window.NativeBridge && window.NativeBridge.onFingerprintWithSource) {
                        window.NativeBridge.onFingerprintWithSource(fp, source);
                    }
                    return true;
                }

                function inspectHeaders(url, headers) {
                    if (!headers) return;
                    try {
                        var token = null;
                        if (headers instanceof Headers) {
                            token = headers.get('af-ac-enc-sz-token');
                        } else if (typeof headers === 'object') {
                            for (var k in headers) {
                                if (k.toLowerCase() === 'af-ac-enc-sz-token') { token = headers[k]; break; }
                            }
                        }
                        if (token && token.length > 10) {
                            setFp(token, 'header af-ac-enc-sz-token');
                        }
                    } catch(e) {}
                }

                function inspectBody(url, body) {
                    try {
                        var p = typeof body === 'string' ? JSON.parse(body) : body;
                        var fp = p && p.security_device_fingerprint;
                        if (fp && fp.length > 10) {
                            setFp(fp, 'body save_voucher');
                        }
                    } catch(e) {}
                }

                var origFetch = window.fetch;
                window.fetch = function(input, init) {
                    var url = typeof input === 'string' ? input : (input && input.url);
                    if (String(url || '').includes('/api/')) {
                        inspectHeaders(url, init && init.headers);
                        inspectBody(url, init && init.body);
                    }
                    return origFetch.apply(this, arguments);
                };

                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                XMLHttpRequest.prototype.open = function(m, url) {
                    this.__svbUrl = url;
                    this.__svbHeaders = {};
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                    if (this.__svbHeaders) this.__svbHeaders[name] = value;
                    return origSetHeader.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function(body) {
                    if (String(this.__svbUrl || '').includes('/api/')) {
                        inspectHeaders(this.__svbUrl, this.__svbHeaders);
                        inspectBody(this.__svbUrl, body);
                    }
                    return origSend.apply(this, arguments);
                };

                // Trích xuất từ cookie shopee_webUnique_ccd
                try {
                    var row = document.cookie.split('; ').find(function(c) { return c.startsWith('shopee_webUnique_ccd='); });
                    if (row) {
                        var val = row.split('=').slice(1).join('=');
                        try { val = decodeURIComponent(val); } catch(e) {}
                        if (val && val.includes('|') && val.length > 20) {
                            setFp(val, 'cookie shopee_webUnique_ccd');
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsHook, null)
    }

    /**
     * Cầu nối NativeBridge tiếp nhận dữ liệu từ Javascript
     */
    inner class ShopeeNativeBridge {
        @JavascriptInterface
        fun onLog(msg: String) {
            Log.d("ShopeeNativeBridge", msg)
        }

        @JavascriptInterface
        fun onFingerprintWithSource(fp: String, source: String) {
            Log.d("ShopeeNativeBridge", "★ Bắt được Fingerprint ($source): $fp")
            sessionManager.fingerprint = fp
            runOnUiThread {
                tvStatus.text = "✓ Đã bắt Fingerprint Shopee ($source)"
                tvStatus.setTextColor(getColor(R.color.status_connected))
            }
        }

        @JavascriptInterface
        fun onVouchersFound(vouchersJson: String) {
            Log.d("ShopeeNativeBridge", "Đã tìm thấy vouchers: $vouchersJson")
        }
    }
}
