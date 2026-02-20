package com.pancreas.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object CredentialsManager {

    private const val PREFS_FILE      = "dexcom_secure_prefs_v2"
    private const val KEY_CLIENT_ID   = "client_id"
    private const val KEY_CLIENT_SEC  = "client_secret"
    private const val KEY_SANDBOX     = "use_sandbox"
    private const val KEY_ACCESS_TOK  = "access_token"
    private const val KEY_REFRESH_TOK = "refresh_token"
    private const val KEY_TOKEN_EXP   = "token_expires_at"   // epoch ms
    private const val KEY_INTERVAL    = "refresh_interval"   // minutes

    private fun prefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx, PREFS_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ─── Client Credentials ───────────────────────────────────────────────────

    fun saveClientCredentials(ctx: Context, clientId: String, clientSecret: String, sandbox: Boolean) {
        prefs(ctx).edit()
            .putString(KEY_CLIENT_ID,  clientId)
            .putString(KEY_CLIENT_SEC, clientSecret)
            .putBoolean(KEY_SANDBOX,   sandbox)
            .remove(KEY_ACCESS_TOK)
            .remove(KEY_REFRESH_TOK)
            .remove(KEY_TOKEN_EXP)
            .apply()
    }

    fun getClientId(ctx: Context)     = prefs(ctx).getString(KEY_CLIENT_ID,  "") ?: ""
    fun getClientSecret(ctx: Context) = prefs(ctx).getString(KEY_CLIENT_SEC, "") ?: ""
    fun useSandbox(ctx: Context)      = prefs(ctx).getBoolean(KEY_SANDBOX, false)

    fun hasClientCredentials(ctx: Context) =
        getClientId(ctx).isNotBlank() && getClientSecret(ctx).isNotBlank()

    // ─── OAuth Tokens ─────────────────────────────────────────────────────────

    fun saveTokens(ctx: Context, accessToken: String, refreshToken: String, expiresIn: Int) {
        val expiresAt = System.currentTimeMillis() + (expiresIn - 60) * 1000L // 60s buffer
        prefs(ctx).edit()
            .putString(KEY_ACCESS_TOK,  accessToken)
            .putString(KEY_REFRESH_TOK, refreshToken)
            .putLong(KEY_TOKEN_EXP,     expiresAt)
            .apply()
    }

    fun getAccessToken(ctx: Context)  = prefs(ctx).getString(KEY_ACCESS_TOK,  null)
    fun getRefreshToken(ctx: Context) = prefs(ctx).getString(KEY_REFRESH_TOK, null)
    fun getTokenExpiresAt(ctx: Context) = prefs(ctx).getLong(KEY_TOKEN_EXP, 0L)

    fun isAccessTokenValid(ctx: Context): Boolean {
        val token = getAccessToken(ctx) ?: return false
        return token.isNotBlank() && System.currentTimeMillis() < getTokenExpiresAt(ctx)
    }

    fun isConnected(ctx: Context) = getRefreshToken(ctx) != null

    fun clearTokens(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_ACCESS_TOK)
            .remove(KEY_REFRESH_TOK)
            .remove(KEY_TOKEN_EXP)
            .apply()
    }

    // ─── App Settings ─────────────────────────────────────────────────────────

    fun getRefreshInterval(ctx: Context) = prefs(ctx).getInt(KEY_INTERVAL, 5)
    fun setRefreshInterval(ctx: Context, minutes: Int) =
        prefs(ctx).edit().putInt(KEY_INTERVAL, minutes).apply()

    // ─── OAuth URL Builder ────────────────────────────────────────────────────

    fun buildAuthUrl(ctx: Context): String {
        val base = if (useSandbox(ctx)) DEXCOM_BASE_SANDBOX else DEXCOM_BASE_PROD
        return "${base}v3/oauth2/login" +
            "?client_id=${getClientId(ctx)}" +
            "&redirect_uri=${android.net.Uri.encode(REDIRECT_URI)}" +
            "&response_type=code" +
            "&scope=$OAUTH_SCOPE"
    }
}
