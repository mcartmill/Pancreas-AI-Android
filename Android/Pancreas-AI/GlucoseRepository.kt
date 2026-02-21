package com.pancreas.ai

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GlucoseRepository(private val ctx: Context) {

    private val TAG = "GlucoseRepository"
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun oauthApi() = DexcomRetrofit.create(CredentialsManager.useSandbox(ctx))
    private fun shareApi() = ShareRetrofit.create(CredentialsManager.isOutsideUs(ctx))
    private fun baseUrl()  =
        if (CredentialsManager.useSandbox(ctx)) DEXCOM_BASE_SANDBOX else DEXCOM_BASE_PROD

    // ── Raw OkHttp (OAuth path) ───────────────────────────────────────────────

    private fun rawClient() = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private suspend fun rawGet(url: String, token: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
                rawClient().newCall(req).execute().use { Pair(it.code, it.body?.string() ?: "(empty)") }
            } catch (e: Exception) {
                Pair(-1, "${e.javaClass.simpleName}: ${e.message ?: e.toString()}")
            }
        }

    // Helper: strip timezone suffix and parse ISO datetime string to Date
    private fun parseIso(s: String): Date? = try {
        val cleaned = s
            .replace(Regex("[+-]\\d{2}:\\d{2}$"), "")
            .replace("Z", "")
            .trim()
        isoFmt.parse(cleaned)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse ISO date: $s")
        null
    }

    // ── Share API ─────────────────────────────────────────────────────────────

    private suspend fun shareLogin(): String {
        val api  = shareApi()
        val user = CredentialsManager.getShareUsername(ctx)
        val pass = CredentialsManager.getSharePassword(ctx)

        val resp = api.login(ShareLoginRequest(user, pass))
        if (!resp.isSuccessful) {
            val err = resp.errorBody()?.string() ?: "HTTP ${resp.code()}"
            throw Exception("Dexcom Share login failed: $err")
        }
        val raw = resp.body() ?: throw Exception("Empty login response")
        val sessionId = raw.trim().removeSurrounding("\"")
        if (sessionId.isBlank() || sessionId == "null") {
            throw Exception("Invalid session ID. Check your username and password.")
        }
        CredentialsManager.saveShareSessionId(ctx, sessionId)
        return sessionId
    }

    private suspend fun getShareSession(): String {
        val cached = CredentialsManager.getShareSessionId(ctx)
        if (cached != null) return cached
        return shareLogin()
    }

    suspend fun fetchReadingsShare(hours: Int = 6): List<EgvReading> {
        if (!CredentialsManager.hasShareCredentials(ctx))
            throw Exception("Enter your Dexcom username and password in Settings.")

        var sessionId = getShareSession()
        val api = shareApi()

        val minutes  = minOf(hours * 60, 1440)
        val maxCount = minutes / 5 + 2

        var resp = api.getReadings(sessionId, minutes, maxCount)

        if (!resp.isSuccessful && resp.code() in 400..599) {
            Log.d(TAG, "Share session expired, re-logging in (HTTP ${resp.code()})")
            CredentialsManager.clearShareSession(ctx)
            sessionId = shareLogin()
            resp = api.getReadings(sessionId, minutes, maxCount)
        }

        if (!resp.isSuccessful) {
            throw Exception("Share API error ${resp.code()}: ${resp.errorBody()?.string()}")
        }

        val readings = resp.body() ?: emptyList()
        Log.d(TAG, "Share: received ${readings.size} readings")

        return readings
            .map { it.toEgvReading() }
            .filter { it.glucoseValue() > 0 }
            .sortedBy { it.epochMillis() }
    }

    // ── OAuth API ─────────────────────────────────────────────────────────────

    suspend fun exchangeAuthCode(code: String) {
        val resp = oauthApi().getToken(
            clientId     = CredentialsManager.getClientId(ctx),
            clientSecret = CredentialsManager.getClientSecret(ctx),
            code         = code
        )
        if (!resp.isSuccessful) {
            throw Exception("Token exchange failed: ${resp.errorBody()?.string() ?: "HTTP ${resp.code()}"}")
        }
        val body         = resp.body() ?: throw Exception("Empty token response")
        val accessToken  = body.accessToken  ?: throw Exception("Token response missing access_token")
        val refreshToken = body.refreshToken ?: ""   // sandbox may omit refresh_token
        val expiresIn    = body.expiresIn    ?: 3600
        CredentialsManager.saveTokens(ctx, accessToken, refreshToken, expiresIn)
    }

    suspend fun getValidAccessToken(): String {
        if (CredentialsManager.isAccessTokenValid(ctx))
            return CredentialsManager.getAccessToken(ctx)!!

        val rt = CredentialsManager.getRefreshToken(ctx)
            ?: throw Exception("Not connected. Tap 'Connect with Dexcom' in Settings.")

        val resp = oauthApi().refreshToken(
            clientId     = CredentialsManager.getClientId(ctx),
            clientSecret = CredentialsManager.getClientSecret(ctx),
            refreshToken = rt
        )
        if (!resp.isSuccessful) {
            CredentialsManager.clearTokens(ctx)
            throw Exception("Session expired. Please reconnect in Settings.")
        }
        val body         = resp.body() ?: throw Exception("Empty refresh response")
        val accessToken  = body.accessToken  ?: throw Exception("Refresh response missing access_token")
        val refreshToken = body.refreshToken ?: ""
        val expiresIn    = body.expiresIn    ?: 3600
        CredentialsManager.saveTokens(ctx, accessToken, refreshToken, expiresIn)
        return accessToken
    }

    suspend fun fetchReadingsOAuth(hours: Int = 6): List<EgvReading> {
        if (!CredentialsManager.hasClientCredentials(ctx))
            throw Exception("No client credentials. Enter Client ID and Secret in Settings.")

        val token     = getValidAccessToken()
        val base      = baseUrl()
        val isSandbox = CredentialsManager.useSandbox(ctx)

        // The Dexcom sandbox has fixed historical data from a past date range.
        // Querying "now minus N hours" always returns nothing because "now" is
        // long after the sandbox data ends. We call dataRange first to find the
        // actual available window, then query the tail end of that range.
        // Production mode queries from the current time as normal.
        val endDate: Date
        val startDate: Date

        if (isSandbox) {
            val (drCode, drBody) = rawGet("${base}v3/users/self/dataRange", token)
            Log.d(TAG, "dataRange $drCode: ${drBody.take(300)}")

            if (drCode == 401) {
                CredentialsManager.clearTokens(ctx)
                throw Exception("Session expired. Reconnect in Settings.")
            }
            if (drCode != 200) {
                throw Exception("dataRange error $drCode: ${drBody.take(200)}. Check Client ID / Secret and confirm Sandbox is ON.")
            }

            val dr = try {
                com.google.gson.Gson().fromJson(drBody, DataRangeResponse::class.java)
            } catch (e: Exception) {
                throw Exception("dataRange parse failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            val endStr = dr?.egvs?.end?.systemTime
                ?: throw Exception("No data found in sandbox. Try SandboxUser6 (G6) or SandboxUser7 (G7) with no password.")
            val startStr = dr.egvs?.start?.systemTime

            val parsedEnd = parseIso(endStr) ?: Date()
            endDate = parsedEnd

            val rangeStartMs = startStr?.let { parseIso(it)?.time }
                ?: (parsedEnd.time - hours * 3_600_000L)
            startDate = Date(maxOf(parsedEnd.time - hours * 3_600_000L, rangeStartMs))

        } else {
            endDate   = Date(System.currentTimeMillis() - 60_000L)
            startDate = Date(endDate.time - (hours + 1) * 3_600_000L)
        }

        val url = "${base}v3/users/self/egvs?startDate=${isoFmt.format(startDate)}&endDate=${isoFmt.format(endDate)}"
        Log.d(TAG, "EGV url: $url")

        val (code, body) = rawGet(url, token)
        Log.d(TAG, "OAuth EGV $code: ${body.take(300)}")

        if (code == 401) {
            CredentialsManager.clearTokens(ctx)
            throw Exception("Session expired. Reconnect in Settings.")
        }
        if (code != 200) throw Exception("API error $code: ${body.take(300)}")

        val egvsResp = try {
            com.google.gson.Gson().fromJson(body, EgvsResponse::class.java)
        } catch (e: Exception) {
            throw Exception("JSON parse failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        val allReadings = egvsResp?.egvs ?: emptyList()
        Log.d(TAG, "Raw EGV count: ${allReadings.size}")

        // Keep readings that have at least one glucose field populated and a valid timestamp.
        // Dexcom can return status-only rows (value/realtimeValue/smoothedValue all null) —
        // those are safe to drop. Readings where glucoseValue() == 0 might be sensor-gap
        // placeholders; we keep them so the chart shows the real shape of the session.
        val filtered = allReadings
            .filter { r ->
                val hasGlucose = (r.value ?: r.realtimeValue ?: r.smoothedValue) != null
                val hasTime    = r.epochMillis() > 0
                hasGlucose && hasTime
            }
            .sortedBy { it.epochMillis() }

        Log.d(TAG, "After filter: ${filtered.size} readings")
        return filtered
    }

    // ── Unified entry point ───────────────────────────────────────────────────

    suspend fun fetchReadings(hours: Int = 6): List<EgvReading> =
        when (CredentialsManager.getAuthMode(ctx)) {
            AuthMode.SHARE -> fetchReadingsShare(hours)
            AuthMode.OAUTH -> fetchReadingsOAuth(hours)
        }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    suspend fun getDiagnostics(): String {
        val sb   = StringBuilder()
        val mode = CredentialsManager.getAuthMode(ctx)
        sb.appendLine("Auth Mode: ${mode.name}")

        return when (mode) {
            AuthMode.SHARE -> {
                sb.appendLine("User: ${CredentialsManager.getShareUsername(ctx)}")
                sb.appendLine("Region: ${if (CredentialsManager.isOutsideUs(ctx)) "Outside US" else "US"}")
                sb.appendLine()
                try {
                    val readings = fetchReadingsShare(hours = 1)
                    if (readings.isNotEmpty()) {
                        val last = readings.last()
                        sb.appendLine("✓ Share API working")
                        sb.appendLine("Latest: ${last.glucoseValue()} mg/dL ${last.trendArrow()}")
                        sb.appendLine("Readings in last hour: ${readings.size}")
                    } else {
                        sb.appendLine("✓ Connected but no readings in last hour")
                    }
                } catch (e: Exception) {
                    sb.appendLine("✗ Error: ${e.message}")
                }
                sb.toString().trim()
            }
            AuthMode.OAUTH -> {
                val token = getValidAccessToken()
                val base  = baseUrl()
                sb.appendLine("Sandbox: ${CredentialsManager.useSandbox(ctx)}")
                sb.appendLine("Base URL: $base")
                sb.appendLine()

                // ── dataRange ──
                val (drCode, drBody) = rawGet("${base}v3/users/self/dataRange", token)
                sb.appendLine("dataRange HTTP $drCode")
                sb.appendLine(drBody.take(300))
                sb.appendLine()

                // ── Parse range and build EGV URL ──
                try {
                    val dr = com.google.gson.Gson().fromJson(drBody, DataRangeResponse::class.java)
                    val endStr   = dr?.egvs?.end?.systemTime   ?: "null"
                    val startStr = dr?.egvs?.start?.systemTime ?: "null"
                    sb.appendLine("Range start: $startStr")
                    sb.appendLine("Range end:   $endStr")

                    val parsedEnd   = parseIso(endStr) ?: Date()
                    val parsedStart = parseIso(startStr)
                    val rangeStartMs = parsedStart?.time ?: (parsedEnd.time - 6 * 3_600_000L)
                    val qStart = Date(maxOf(parsedEnd.time - 6 * 3_600_000L, rangeStartMs))
                    val qEnd   = parsedEnd

                    val url = "${base}v3/users/self/egvs?startDate=${isoFmt.format(qStart)}&endDate=${isoFmt.format(qEnd)}"
                    sb.appendLine("EGV URL: $url")
                    sb.appendLine()

                    // ── EGV call ──
                    val (egvCode, egvBody) = rawGet(url, token)
                    sb.appendLine("EGV HTTP $egvCode")
                    sb.appendLine(egvBody.take(400))
                    sb.appendLine()

                    // ── Parse and count ──
                    if (egvCode == 200) {
                        val resp = com.google.gson.Gson().fromJson(egvBody, EgvsResponse::class.java)
                        val all      = resp?.egvs ?: emptyList()
                        val nonZero  = all.filter { it.glucoseValue() > 0 }
                        val hasEpoch = all.filter { it.epochMillis() > 0 }
                        sb.appendLine("Raw EGV count:      ${all.size}")
                        sb.appendLine("After >0 filter:    ${nonZero.size}")
                        sb.appendLine("Valid timestamps:   ${hasEpoch.size}")
                        if (all.isNotEmpty()) {
                            val first = all.first()
                            sb.appendLine("First: t=${first.systemTime} v=${first.value} rt=${first.realtimeValue} sm=${first.smoothedValue} trend=${first.trend}")
                        }
                    }
                } catch (e: Exception) {
                    sb.appendLine("Parse/EGV error: ${e.javaClass.simpleName}: ${e.message}")
                }

                sb.toString().trim()
            }
        }
    }
}
