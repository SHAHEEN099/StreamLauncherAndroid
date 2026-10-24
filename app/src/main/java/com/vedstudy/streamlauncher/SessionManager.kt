package com.vedstudy.streamlauncher

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "stream_launcher_prefs"
        private const val KEY_SESSION_COOKIE = "session_cookie"
        private const val KEY_SESSION_EXPIRY = "session_expiry"
        private const val KEY_JWT_KEY = "jwt_key"
        private const val KEY_VERIFIED_AT = "verified_at"
    }

    fun getSessionCookie(): String? {
        return prefs.getString(KEY_SESSION_COOKIE, null)
    }

    fun getSessionExpiry(): Long {
        return prefs.getLong(KEY_SESSION_EXPIRY, 0L)
    }

    fun getJwtKey(): String? {
        return prefs.getString(KEY_JWT_KEY, null)
    }

    fun saveSession(cookie: String, expiryMs: Long, jwtKey: String?) {
        prefs.edit()
            .putString(KEY_SESSION_COOKIE, cookie)
            .putLong(KEY_SESSION_EXPIRY, expiryMs)
            .putString(KEY_JWT_KEY, jwtKey)
            .putString(KEY_VERIFIED_AT, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
            .apply()
    }

    fun isSessionValid(): Boolean {
        val cookie = getSessionCookie()
        val expiry = getSessionExpiry()
        if (cookie.isNullOrEmpty() || expiry == 0L) return false

        val remainingMs = expiry - System.currentTimeMillis()
        // Valid if remaining time > 5 minutes
        return remainingMs > 300_000L
    }

    fun getRemainingHours(): Double {
        val expiry = getSessionExpiry()
        val remainingMs = expiry - System.currentTimeMillis()
        return if (remainingMs > 0) remainingMs / (1000.0 * 3600.0) else 0.0
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
