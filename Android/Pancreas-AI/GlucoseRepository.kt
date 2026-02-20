package com.pancreas.ai

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class GlucoseRepository(private val ctx: Context) {

    private val TAG = "GlucoseRepository"
    // No Z suffix — Dexcom docs show plain ISO 8601 local datetime
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun api() = DexcomRetrofit.create(CredentialsManager.useSandbox(ctx))

    suspend fun exchangeAuthCode(code: String) {
        val resp = api().getToken(
            clientId     = CredentialsManager.getClientId(ctx),
            clientSecret = CredentialsManager.getClientSecret(ctx),
            code         = code
        )
        if (!resp.isSuccessful) {
            val err = resp.errorBody()?.string() ?: "HTTP ${resp.code()}"
            throw Exception("Token exchange failed: $err")
        }
        val body = resp.body() ?: throw Exception("Empty token response")
        CredentialsManager.saveTokens(ctx, body.accessToken, body.refreshToken, body.expiresIn)
    }

    suspend fun getValidAccessToken(): String {
        if (CredentialsManager.isAccessTokenValid(ctx)) {
            return CredentialsManager.getAccessToken(ctx)!!
        }
        val refreshToken = CredentialsManager.getRefreshToken(ctx)
            ?: throw Exception("Not connected. Tap 'Connect with Dexcom' in Settings.")

        val resp = api().refreshToken(
            clientId     = CredentialsManager.getClientId(ctx),
            clientSecret = CredentialsManager.getClientSecret(ctx),
            refreshToken = refreshToken
        )
        if (!resp.isSuccessful) {
            CredentialsManager.clearTokens(ctx)
            throw Exception("Session expired. Please reconnect in Settings.")
        }
        val body = resp.body() ?: throw Exception("Empty refresh response")
        CredentialsManager.saveTokens(ctx, body.accessToken, body.refreshToken, body.expiresIn)
        return body.accessToken
    }

    /**
     * Returns a human-readable diagnostic string describing what data the API
     * says is available for this account. Useful for troubleshooting empty results.
     */
    suspend fun getDiagnostics(): String {
        val token = getValidAccessToken()
        val resp = api().getDataRange(bearerToken = "Bearer $token")
        if (!resp.isSuccessful) {
            val err = resp.errorBody()?.string() ?: "HTTP ${resp.code()}"
            return "dataRange error: $err"
        }
        val egvs = resp.body()?.egvs
        return if (egvs?.start != null && egvs.end != null) {
            "Data available from ${egvs.start.systemTime} to ${egvs.end.systemTime}"
        } else {
            "dataRange returned no EGV records (egvs is null or empty)"
        }
    }

    suspend fun fetchReadings(hours: Int = 24): List<EgvReading> {
        if (!CredentialsManager.hasClientCredentials(ctx))
            throw Exception("No client credentials. Enter Client ID and Secret in Settings.")

        val token = getValidAccessToken()

        // First check dataRange so we can log what's actually available
        try {
            val rangeResp = api().getDataRange(bearerToken = "Bearer $token")
            if (rangeResp.isSuccessful) {
                val egvRange = rangeResp.body()?.egvs
                Log.d(TAG, "dataRange → EGVs: ${egvRange?.start?.systemTime} – ${egvRange?.end?.systemTime}")
            } else {
                Log.w(TAG, "dataRange failed: ${rangeResp.code()} ${rangeResp.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "dataRange exception: ${e.message}")
        }

        val endDate   = Date()
        val startDate = Date(endDate.time - hours * 3_600_000L)
        val start = isoFmt.format(startDate)
        val end   = isoFmt.format(endDate)
        Log.d(TAG, "Fetching EGVs: $start → $end (sandbox=${CredentialsManager.useSandbox(ctx)})")

        val resp = api().getEgvs(
            bearerToken = "Bearer $token",
            startDate   = start,
            endDate     = end
        )

        if (!resp.isSuccessful) {
            val errBody = resp.errorBody()?.string() ?: "no body"
            Log.e(TAG, "EGV error ${resp.code()}: $errBody")
            if (resp.code() == 401) {
                CredentialsManager.clearTokens(ctx)
                throw Exception("Authorization failed (401). Please reconnect in Settings.")
            }
            throw Exception("API error ${resp.code()}: $errBody")
        }

        val readings = resp.body()?.egvs ?: emptyList()
        Log.d(TAG, "Received ${readings.size} EGV readings")
        return readings.filter { it.glucoseValue() > 0 }.sortedBy { it.epochMillis() }
    }
}
