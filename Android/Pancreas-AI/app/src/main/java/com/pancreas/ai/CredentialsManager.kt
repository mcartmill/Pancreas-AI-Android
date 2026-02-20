package com.pancreas.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object CredentialsManager {

    private const val PREFS_FILE     = "dexcom_secure_prefs"
    private const val KEY_USERNAME   = "dexcom_username"
    private const val KEY_PASSWORD   = "dexcom_password"
    private const val KEY_REGION     = "dexcom_region"      // "US" or "OUTSIDE"
    private const val KEY_SESSION_ID = "dexcom_session_id"
    private const val KEY_INTERVAL   = "refresh_interval"   // minutes

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

    fun saveCredentials(ctx: Context, username: String, password: String, region: String) {
        prefs(ctx).edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .putString(KEY_REGION, region)
            .remove(KEY_SESSION_ID)   // invalidate old session on credential change
            .apply()
    }

    fun getUsername(ctx: Context)  = prefs(ctx).getString(KEY_USERNAME, "") ?: ""
    fun getPassword(ctx: Context)  = prefs(ctx).getString(KEY_PASSWORD, "") ?: ""
    fun getRegion(ctx: Context)    = prefs(ctx).getString(KEY_REGION, "US") ?: "US"

    fun saveSessionId(ctx: Context, id: String) =
        prefs(ctx).edit().putString(KEY_SESSION_ID, id).apply()

    fun getSessionId(ctx: Context) = prefs(ctx).getString(KEY_SESSION_ID, null)

    fun clearSession(ctx: Context) =
        prefs(ctx).edit().remove(KEY_SESSION_ID).apply()

    fun hasCredentials(ctx: Context): Boolean =
        getUsername(ctx).isNotBlank() && getPassword(ctx).isNotBlank()

    fun getRefreshInterval(ctx: Context): Int =
        prefs(ctx).getInt(KEY_INTERVAL, 5)

    fun setRefreshInterval(ctx: Context, minutes: Int) =
        prefs(ctx).edit().putInt(KEY_INTERVAL, minutes).apply()

    fun getBaseUrl(ctx: Context): String =
        if (getRegion(ctx) == "US") DEXCOM_BASE_US else DEXCOM_BASE_OUTSIDE
}
