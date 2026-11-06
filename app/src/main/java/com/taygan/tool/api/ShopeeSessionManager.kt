package com.taygan.tool.api

import android.content.Context
import android.content.SharedPreferences

class ShopeeSessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shopee_session_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_COOKIE = "shopee_cookie"
        private const val KEY_CSRF_TOKEN = "shopee_csrf_token"
        private const val KEY_FINGERPRINT = "shopee_fingerprint"
        private const val KEY_AUTO_API_CLAIM = "shopee_auto_api_claim"

        @Volatile
        private var INSTANCE: ShopeeSessionManager? = null

        fun getInstance(context: Context): ShopeeSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ShopeeSessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    var cookie: String
        get() = prefs.getString(KEY_COOKIE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_COOKIE, value).apply()
            // Auto-extract csrftoken if present in cookie
            extractCsrfToken(value)?.let { csrfToken = it }
        }

    var csrfToken: String
        get() = prefs.getString(KEY_CSRF_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CSRF_TOKEN, value).apply()

    var fingerprint: String
        get() {
            val fp = prefs.getString(KEY_FINGERPRINT, "") ?: ""
            return if (fp.isNotBlank()) fp else generateFallbackFingerprint()
        }
        set(value) = prefs.edit().putString(KEY_FINGERPRINT, value).apply()

    var isAutoApiClaimEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_API_CLAIM, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_API_CLAIM, value).apply()

    val isLoggedIn: Boolean
        get() = cookie.isNotBlank() && csrfToken.isNotBlank()

    private fun extractCsrfToken(cookieString: String): String? {
        val regex = Regex("csrftoken=([a-zA-Z0-9_-]+)")
        val match = regex.find(cookieString)
        return match?.groupValues?.get(1)
    }

    private fun generateFallbackFingerprint(): String {
        // Fallback synthetic fingerprint if not captured from web yet
        val ts = System.currentTimeMillis()
        val randomPart = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val fp = "01|$ts|$randomPart|v4"
        fingerprint = fp
        return fp
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
