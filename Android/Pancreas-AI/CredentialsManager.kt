package com.pancreas.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class AuthMode { SHARE, OAUTH }

enum class DeviceType(val label: String, val sandboxUser: String) {
    G6("Dexcom G6", "SandboxUser6"),
    G7("Dexcom G7", "SandboxUser7")
}

object CredentialsManager {

    private const val PREFS_FILE       = "dexcom_secure_prefs_v3"
    // Share credentials
    private const val KEY_SHARE_USER   = "share_username"
    private const val KEY_SHARE_PASS   = "share_password"
    private const val KEY_SHARE_OUS    = "share_outside_us"
    private const val KEY_SHARE_SID    = "share_session_id"
    // OAuth credentials
    private const val KEY_CLIENT_ID    = "client_id"
    private const val KEY_CLIENT_SEC   = "client_secret"
    private const val KEY_SANDBOX      = "use_sandbox"
    private const val KEY_ACCESS_TOK   = "access_token"
    private const val KEY_REFRESH_TOK  = "refresh_token"
    private const val KEY_TOKEN_EXP    = "token_expires_at"
    // Common
    private const val KEY_AUTH_MODE    = "auth_mode"
    private const val KEY_DEVICE_TYPE  = "device_type"
    private const val KEY_INTERVAL     = "refresh_interval"
    private const val KEY_HOURS        = "chart_hours"

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

    // ─── Auth mode ────────────────────────────────────────────────────────────

    fun getAuthMode(ctx: Context): AuthMode {
        val name = prefs(ctx).getString(KEY_AUTH_MODE, AuthMode.SHARE.name) ?: AuthMode.SHARE.name
        return try { AuthMode.valueOf(name) } catch (e: Exception) { AuthMode.SHARE }
    }

    fun setAuthMode(ctx: Context, mode: AuthMode) =
        prefs(ctx).edit().putString(KEY_AUTH_MODE, mode.name).apply()

    // ─── Share credentials ────────────────────────────────────────────────────

    fun saveShareCredentials(ctx: Context, username: String, password: String, outsideUs: Boolean) {
        prefs(ctx).edit()
            .putString(KEY_SHARE_USER, username)
            .putString(KEY_SHARE_PASS, password)
            .putBoolean(KEY_SHARE_OUS, outsideUs)
            .remove(KEY_SHARE_SID)   // invalidate cached session
            .apply()
    }

    fun getShareUsername(ctx: Context) = prefs(ctx).getString(KEY_SHARE_USER, "") ?: ""
    fun getSharePassword(ctx: Context) = prefs(ctx).getString(KEY_SHARE_PASS, "") ?: ""
    fun isOutsideUs(ctx: Context)      = prefs(ctx).getBoolean(KEY_SHARE_OUS, false)
    fun hasShareCredentials(ctx: Context) =
        getShareUsername(ctx).isNotBlank() && getSharePassword(ctx).isNotBlank()

    fun getShareSessionId(ctx: Context)  = prefs(ctx).getString(KEY_SHARE_SID, null)
    fun saveShareSessionId(ctx: Context, sessionId: String) =
        prefs(ctx).edit().putString(KEY_SHARE_SID, sessionId).apply()
    fun clearShareSession(ctx: Context) =
        prefs(ctx).edit().remove(KEY_SHARE_SID).apply()

    // ─── OAuth credentials ────────────────────────────────────────────────────

    fun saveClientCredentials(ctx: Context, clientId: String, clientSecret: String, sandbox: Boolean) {
        prefs(ctx).edit()
            .putString(KEY_CLIENT_ID,  clientId)
            .putString(KEY_CLIENT_SEC, clientSecret)
            .putBoolean(KEY_SANDBOX,   sandbox)
            .remove(KEY_ACCESS_TOK).remove(KEY_REFRESH_TOK).remove(KEY_TOKEN_EXP)
            .apply()
    }

    fun getClientId(ctx: Context)     = prefs(ctx).getString(KEY_CLIENT_ID,  "") ?: ""
    fun getClientSecret(ctx: Context) = prefs(ctx).getString(KEY_CLIENT_SEC, "") ?: ""
    fun useSandbox(ctx: Context)      = prefs(ctx).getBoolean(KEY_SANDBOX, false)
    fun hasClientCredentials(ctx: Context) =
        getClientId(ctx).isNotBlank() && getClientSecret(ctx).isNotBlank()

    fun saveTokens(ctx: Context, accessToken: String, refreshToken: String, expiresIn: Int) {
        val expiresAt = System.currentTimeMillis() + (expiresIn - 60) * 1000L
        prefs(ctx).edit()
            .putString(KEY_ACCESS_TOK,  accessToken)
            .putString(KEY_REFRESH_TOK, refreshToken)
            .putLong(KEY_TOKEN_EXP,     expiresAt)
            .apply()
    }

    fun getAccessToken(ctx: Context)    = prefs(ctx).getString(KEY_ACCESS_TOK,  null)
    fun getRefreshToken(ctx: Context)   = prefs(ctx).getString(KEY_REFRESH_TOK, null)
    fun getTokenExpiresAt(ctx: Context) = prefs(ctx).getLong(KEY_TOKEN_EXP, 0L)

    fun isAccessTokenValid(ctx: Context): Boolean {
        val token = getAccessToken(ctx) ?: return false
        return token.isNotBlank() && System.currentTimeMillis() < getTokenExpiresAt(ctx)
    }

    fun isOAuthConnected(ctx: Context) = getRefreshToken(ctx) != null

    fun clearTokens(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_ACCESS_TOK).remove(KEY_REFRESH_TOK).remove(KEY_TOKEN_EXP)
            .apply()
    }

    // ─── Generic connection check ─────────────────────────────────────────────

    fun isConnected(ctx: Context) = when (getAuthMode(ctx)) {
        AuthMode.SHARE -> hasShareCredentials(ctx)
        AuthMode.OAUTH -> isOAuthConnected(ctx)
    }

    // ─── Device type ──────────────────────────────────────────────────────────

    fun setDeviceType(ctx: Context, type: DeviceType) =
        prefs(ctx).edit().putString(KEY_DEVICE_TYPE, type.name).apply()

    fun getDeviceType(ctx: Context): DeviceType {
        val name = prefs(ctx).getString(KEY_DEVICE_TYPE, DeviceType.G7.name) ?: DeviceType.G7.name
        return try { DeviceType.valueOf(name) } catch (e: Exception) { DeviceType.G7 }
    }

    // ─── Settings ─────────────────────────────────────────────────────────────

    fun getRefreshInterval(ctx: Context) = prefs(ctx).getInt(KEY_INTERVAL, 5)
    fun setRefreshInterval(ctx: Context, minutes: Int) =
        prefs(ctx).edit().putInt(KEY_INTERVAL, minutes).apply()

    fun getChartHours(ctx: Context) = prefs(ctx).getInt(KEY_HOURS, 6)
    fun setChartHours(ctx: Context, hours: Int) =
        prefs(ctx).edit().putInt(KEY_HOURS, hours).apply()

    // ─── OAuth Auth URL ───────────────────────────────────────────────────────

    fun buildAuthUrl(ctx: Context): String {
        val base = if (useSandbox(ctx)) DEXCOM_BASE_SANDBOX else DEXCOM_BASE_PROD
        // Dexcom uses v2/oauth2/login for the authorization page (both sandbox and prod)
        // v3/oauth2/login returns 404 and breaks the OAuth flow silently
        return "${base}v2/oauth2/login" +
            "?client_id=${getClientId(ctx)}" +
            "&redirect_uri=${android.net.Uri.encode(REDIRECT_URI)}" +
            "&response_type=code" +
            "&scope=$OAUTH_SCOPE"
    }
}
