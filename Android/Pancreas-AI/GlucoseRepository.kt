package com.pancreas.ai

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private fun baseUrl()  =
        if (CredentialsManager.useSandbox(ctx)) DEXCOM_BASE_SANDBOX else DEXCOM_BASE_PROD

    // ── Raw OkHttp client ─────────────────────────────────────────────────────

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

    private fun parseIso(s: String): Date? = try {
        val cleaned = s.replace(Regex("[+-]\\d{2}:\\d{2}$"), "").replace("Z", "").trim()
        isoFmt.parse(cleaned)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse ISO date: $s"); null
    }

    // ── Share API ─────────────────────────────────────────────────────────────

    private fun shareBaseUrl() =
        if (CredentialsManager.isOutsideUs(ctx)) SHARE_OUS_BASE else SHARE_US_BASE

    private suspend fun sharePost(path: String, jsonBody: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            try {
                val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val req = Request.Builder()
                    .url(shareBaseUrl() + path)
                    .post(body)
                    .header("Accept", "application/json")
                    .build()
                rawClient().newCall(req).execute().use { Pair(it.code, it.body?.string() ?: "") }
            } catch (e: Exception) {
                Pair(-1, "${e.javaClass.simpleName}: ${e.message}")
            }
        }

    private suspend fun shareGet(path: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(shareBaseUrl() + path)
                    .header("Accept", "application/json")
                    .build()
                rawClient().newCall(req).execute().use { Pair(it.code, it.body?.string() ?: "") }
            } catch (e: Exception) {
                Pair(-1, "${e.javaClass.simpleName}: ${e.message}")
            }
        }

    private val SHARE_APP_IDS = listOf(
        "d89443d2-327c-4a6f-89e5-496bbb0317db",  // G5/G6 era
        "d8665ade-9673-4e27-9ff6-92db4ce13d13"   // G7/Clarity era
    )
    private val NULL_UUID = "00000000-0000-0000-0000-000000000000"

    private fun isValidSession(sid: String) =
        sid.isNotBlank() && !sid.equals("null", ignoreCase = true) && sid != NULL_UUID


    private fun nameLoginJson(user: String, pass: String, appId: String): String =
        org.json.JSONObject().apply {
            put("accountName", user)
            put("password", pass)
            put("applicationId", appId)
        }.toString()

    private fun idLoginJson(accountId: String, pass: String, appId: String): String =
        org.json.JSONObject().apply {
            put("accountId", accountId)
            put("password", pass)
            put("applicationId", appId)
        }.toString()

    /** Flow 1: LoginPublisherAccountByName — legacy username accounts */
    private suspend fun tryLoginByName(user: String, pass: String, appId: String): String? {
        val (code, resp) = sharePost("General/LoginPublisherAccountByName", nameLoginJson(user, pass, appId))
        Log.d(TAG, "ByName(${appId.take(8)}) HTTP $code: ${resp.take(80)}")
        if (code != 200) return null
        val sid = resp.trim().removeSurrounding("\"")
        return if (isValidSession(sid)) sid else null
    }

    /**
     * Flow 2: two-step for modern email-based accounts.
     * Step A: AuthenticatePublisherAccount -> account GUID
     * Step B: LoginPublisherAccountById -> session ID
     *
     * Returns null on success=false, throws SharePasswordException if Dexcom
     * explicitly says the password is wrong (so we can give better guidance).
     */
    private suspend fun tryLoginById(user: String, pass: String, appId: String): String? {
        val (idCode, idResp) = sharePost("General/AuthenticatePublisherAccount", nameLoginJson(user, pass, appId))
        Log.d(TAG, "Authenticate(${appId.take(8)}) HTTP $idCode: ${idResp.take(80)}")

        // Dexcom explicitly rejected the password — surface a helpful error instead
        // of silently returning null and then showing a generic message.
        if (idCode != 200 && idResp.contains("AccountPasswordInvalid", ignoreCase = true)) {
            throw SharePasswordException(user)
        }

        if (idCode != 200) return null
        val accountId = idResp.trim().removeSurrounding("\"")
        if (!isValidSession(accountId)) return null

        val (loginCode, loginResp) = sharePost("General/LoginPublisherAccountById", idLoginJson(accountId, pass, appId))
        Log.d(TAG, "ById(${appId.take(8)}) HTTP $loginCode: ${loginResp.take(80)}")
        if (loginCode != 200) return null
        val sid = loginResp.trim().removeSurrounding("\"")
        return if (isValidSession(sid)) sid else null
    }

    /** Thrown when Dexcom explicitly rejects the Share API password. */
    private class SharePasswordException(email: String) : Exception(buildPasswordError(email))

    private companion object {
        fun buildPasswordError(email: String): String {
            val isGmail = email.endsWith("@gmail.com", ignoreCase = true)
                       || email.endsWith("@googlemail.com", ignoreCase = true)
            return buildString {
                appendLine("Dexcom rejected the Share password (AccountPasswordInvalid).")
                appendLine()
                if (isGmail) {
                    appendLine("⚠️ Gmail account detected — this is the most common cause.")
                    appendLine("If you signed up for Dexcom using \"Sign in with Google\", your")
                    appendLine("account has no native Dexcom password and the Share API won't work.")
                    appendLine()
                    appendLine("To fix this:")
                    appendLine("1. Open dexcom.com in a browser and sign out")
                    appendLine("2. Click \"Forgot password\" and enter your Gmail address")
                    appendLine("3. Dexcom will email you a link to SET a native password")
                    appendLine("4. Come back and enter that new password in Settings")
                    appendLine()
                    appendLine("Alternatively, use Developer API (OAuth) mode instead.")
                } else {
                    appendLine("The password stored in the app doesn't match what Dexcom has.")
                    appendLine()
                    appendLine("To fix this:")
                    appendLine("1. Verify you can log into share.dexcom.com with these credentials")
                    appendLine("2. If you recently changed your Dexcom password, update it in Settings")
                    appendLine("3. Make sure you're using the SHARER's credentials (not a follower account)")
                    appendLine("4. Check the Outside US toggle if you're not in the US")
                }
            }.trim()
        }
    }

    private suspend fun shareLogin(): String {
        val user = CredentialsManager.getShareUsername(ctx)
        val pass = CredentialsManager.getSharePassword(ctx)

        // SharePasswordException propagates immediately — it carries a detailed
        // explanation and retrying other app IDs won't help.
        for (appId in SHARE_APP_IDS) {
            tryLoginByName(user, pass, appId)?.let {
                CredentialsManager.saveShareSessionId(ctx, it)
                Log.d(TAG, "Share login OK via ByName(${appId.take(8)})")
                return it
            }
            tryLoginById(user, pass, appId)?.let {   // may throw SharePasswordException
                CredentialsManager.saveShareSessionId(ctx, it)
                Log.d(TAG, "Share login OK via ById(${appId.take(8)})")
                return it
            }
        }

        throw Exception(
            "Unable to log in to Dexcom Share.\n\n" +
            "• Make sure you entered the SHARER's credentials (the person wearing the sensor)\n" +
            "• Open the Dexcom app → Share and confirm sharing is turned ON\n" +
            "• If your username is an email address, try it exactly as-is\n" +
            "• Toggle the Outside US switch in Settings if you're not in the US"
        )
    }

    suspend fun fetchReadingsShare(hours: Int = 6): List<EgvReading> {
        if (!CredentialsManager.hasShareCredentials(ctx))
            throw Exception("Enter your Dexcom username and password in Settings.")

        // Reuse a cached session ID if we have one — only login when necessary.
        // Dexcom Share sessions are valid for several hours; forcing a re-login
        // on every refresh hits the auth endpoint unnecessarily and causes
        // intermittent AccountPasswordInvalid errors if Dexcom throttles or
        // returns a transient 500.
        val sessionId = CredentialsManager.getShareSessionId(ctx)?.takeIf { it.isNotBlank() }
            ?: run {
                Log.d(TAG, "No cached session — performing fresh login")
                shareLogin()
            }

        val minutes  = minOf(hours * 60, 1440)
        val maxCount = maxOf(minutes / 5 + 2, 288)

        val path = "Publisher/ReadPublisherLatestGlucoseValues" +
            "?sessionId=$sessionId&minutes=$minutes&maxCount=$maxCount"
        val (code, body) = shareGet(path)
        Log.d(TAG, "Share readings HTTP $code: ${body.take(300)}")

        // Session expired (Dexcom returns 500 with SessionNotValid / SessionIdNotFound)
        // — clear it and retry once with a fresh login.
        if (code == 500 && (body.contains("SessionNotValid", ignoreCase = true) ||
                             body.contains("SessionIdNotFound", ignoreCase = true))) {
            Log.d(TAG, "Session expired — clearing and re-logging in")
            CredentialsManager.clearShareSession(ctx)
            val freshSession = shareLogin()
            val retryPath = "Publisher/ReadPublisherLatestGlucoseValues" +
                "?sessionId=$freshSession&minutes=$minutes&maxCount=$maxCount"
            val (retryCode, retryBody) = shareGet(retryPath)
            Log.d(TAG, "Share retry HTTP $retryCode: ${retryBody.take(300)}")
            return parseShareReadings(retryCode, retryBody)
        }

        return parseShareReadings(code, body)
    }

    private fun parseShareReadings(code: Int, body: String): List<EgvReading> {
        if (code != 200) throw Exception("Share API error $code: ${body.take(300)}")

        val trimmed = body.trim()
        if (trimmed == "null" || trimmed.isEmpty()) {
            Log.d(TAG, "Share: no readings in window")
            return emptyList()
        }
        if (trimmed.startsWith("{")) {
            val errCode = trimmed.substringAfter("\"Code\":\"", "").substringBefore("\"")
            val errMsg  = trimmed.substringAfter("\"Message\":\"", "").substringBefore("\"")
            throw Exception("Share API error: ${errMsg.ifEmpty { errCode.ifEmpty { trimmed.take(200) } }}")
        }
        if (!trimmed.startsWith("[")) {
            throw Exception("Share API unexpected response: ${trimmed.take(200)}")
        }

        val type = object : com.google.gson.reflect.TypeToken<List<ShareReading>>() {}.type
        val readings: List<ShareReading> = try {
            com.google.gson.Gson().fromJson(trimmed, type) ?: emptyList()
        } catch (e: Exception) {
            throw Exception("Share parse failed: ${e.message} — Raw: ${trimmed.take(200)}")
        }

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
        val refreshToken = body.refreshToken ?: ""
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

        val endDate: Date
        val startDate: Date

        if (isSandbox) {
            val (drCode, drBody) = rawGet("${base}v3/users/self/dataRange", token)
            Log.d(TAG, "dataRange $drCode: ${drBody.take(300)}")

            if (drCode == 401) { CredentialsManager.clearTokens(ctx); throw Exception("Session expired. Reconnect in Settings.") }
            if (drCode != 200) throw Exception("dataRange error $drCode: ${drBody.take(200)}. Check Client ID / Secret and confirm Sandbox is ON.")

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
            val rangeStartMs = startStr?.let { parseIso(it)?.time } ?: (parsedEnd.time - hours * 3_600_000L)
            startDate = Date(maxOf(parsedEnd.time - hours * 3_600_000L, rangeStartMs))
        } else {
            endDate   = Date(System.currentTimeMillis() - 60_000L)
            startDate = Date(endDate.time - (hours + 1) * 3_600_000L)
        }

        val url = "${base}v3/users/self/egvs?startDate=${isoFmt.format(startDate)}&endDate=${isoFmt.format(endDate)}"
        Log.d(TAG, "EGV url: $url")

        val (code, body) = rawGet(url, token)
        Log.d(TAG, "OAuth EGV $code: ${body.take(300)}")

        if (code == 401) { CredentialsManager.clearTokens(ctx); throw Exception("Session expired. Reconnect in Settings.") }
        if (code != 200) throw Exception("API error $code: ${body.take(300)}")

        val egvsResp = try {
            com.google.gson.Gson().fromJson(body, EgvsResponse::class.java)
        } catch (e: Exception) {
            throw Exception("JSON parse failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        val allReadings = egvsResp?.egvs ?: emptyList()
        Log.d(TAG, "Raw EGV count: ${allReadings.size}")

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
                sb.appendLine("Base URL: ${shareBaseUrl()}")
                sb.appendLine()

                val user = CredentialsManager.getShareUsername(ctx)
                val pass = CredentialsManager.getSharePassword(ctx)

                // Show ALL raw HTTP responses so we can see exactly what Dexcom returns
                for (appId in SHARE_APP_IDS) {
                    val shortId = appId.take(8)
                    sb.appendLine("=== App ID: $shortId ===")

                    // Flow 1: ByName
                    val (c1, r1) = sharePost("General/LoginPublisherAccountByName", nameLoginJson(user, pass, appId))
                    val sid1 = r1.trim().removeSurrounding("\"")
                    sb.appendLine("LoginByName: HTTP $c1")
                    sb.appendLine("  -> ${r1.take(120)}")

                    // Flow 2a: AuthenticatePublisherAccount
                    val (c2a, r2a) = sharePost("General/AuthenticatePublisherAccount", nameLoginJson(user, pass, appId))
                    val accountId = r2a.trim().removeSurrounding("\"")
                    sb.appendLine("Authenticate: HTTP $c2a")
                    sb.appendLine("  -> ${r2a.take(120)}")

                    // Annotate the password-invalid case so it's unmistakable in the log
                    if (r2a.contains("AccountPasswordInvalid", ignoreCase = true)) {
                        sb.appendLine("  ⚠️  PASSWORD REJECTED by Dexcom Share API")
                        if (user.contains("@gmail.com", ignoreCase = true) ||
                            user.contains("@googlemail.com", ignoreCase = true)) {
                            sb.appendLine("  ⚠️  Gmail account — likely signed up via Google SSO.")
                            sb.appendLine("      The Share API requires a native Dexcom password.")
                            sb.appendLine("      Fix: go to dexcom.com → Forgot Password to set one.")
                        }
                    }

                    // Flow 2b: LoginById (only if we got an account ID)
                    if (c2a == 200 && isValidSession(accountId)) {
                        val (c2b, r2b) = sharePost("General/LoginPublisherAccountById", idLoginJson(accountId, pass, appId))
                        val sid2 = r2b.trim().removeSurrounding("\"")
                        sb.appendLine("LoginById: HTTP $c2b")
                        sb.appendLine("  -> ${r2b.take(120)}")
                    }

                    sb.appendLine()
                }

                // Now actually try to get readings using shareLogin()
                try {
                    CredentialsManager.clearShareSession(ctx)
                    val sessionId = shareLogin()
                    sb.appendLine("Login OK: ${sessionId.take(8)}...")

                    val path = "Publisher/ReadPublisherLatestGlucoseValues" +
                        "?sessionId=$sessionId&minutes=180&maxCount=288"
                    val (readCode, readBody) = shareGet(path)
                    sb.appendLine("Readings HTTP $readCode")
                    sb.appendLine(if (readBody.isEmpty()) "(empty)" else readBody.take(400))

                    val trimmedBody = readBody.trim()
                    if (readCode == 200 && trimmedBody.startsWith("[")) {
                        val type = object : com.google.gson.reflect.TypeToken<List<ShareReading>>() {}.type
                        val raw: List<ShareReading> = com.google.gson.Gson().fromJson(trimmedBody, type) ?: emptyList()
                        val filtered2 = raw.filter { it.toEgvReading().glucoseValue() > 0 }
                        sb.appendLine("Raw: ${raw.size}, After filter: ${filtered2.size}")
                        if (filtered2.isNotEmpty()) {
                            val last = filtered2.last().toEgvReading()
                            sb.appendLine("Latest: ${last.glucoseValue()} mg/dL ${last.trendArrow()}")
                        }
                    }
                } catch (e: Exception) {
                    sb.appendLine("Login/Readings error: ${e.message}")
                }

                sb.toString().trim()
            }
            AuthMode.OAUTH -> {
                try {
                    val token = getValidAccessToken()
                    val base  = baseUrl()
                    sb.appendLine("Sandbox: ${CredentialsManager.useSandbox(ctx)}")
                    sb.appendLine("Base URL: $base")
                    sb.appendLine()

                    val (drCode, drBody) = rawGet("${base}v3/users/self/dataRange", token)
                    sb.appendLine("dataRange HTTP $drCode")
                    sb.appendLine(drBody.take(300))
                    sb.appendLine()

                    val dr = com.google.gson.Gson().fromJson(drBody, DataRangeResponse::class.java)
                    val endStr   = dr?.egvs?.end?.systemTime   ?: "null"
                    val startStr = dr?.egvs?.start?.systemTime ?: "null"
                    sb.appendLine("Range start: $startStr")
                    sb.appendLine("Range end:   $endStr")

                    val parsedEnd    = parseIso(endStr) ?: Date()
                    val parsedStart  = parseIso(startStr)
                    val rangeStartMs = parsedStart?.time ?: (parsedEnd.time - 6 * 3_600_000L)
                    val qStart = Date(maxOf(parsedEnd.time - 6 * 3_600_000L, rangeStartMs))
                    val qEnd   = parsedEnd

                    val url = "${base}v3/users/self/egvs?startDate=${isoFmt.format(qStart)}&endDate=${isoFmt.format(qEnd)}"
                    sb.appendLine("EGV URL: $url")
                    sb.appendLine()

                    val (egvCode, egvBody) = rawGet(url, token)
                    sb.appendLine("EGV HTTP $egvCode")
                    sb.appendLine(egvBody.take(400))
                    sb.appendLine()

                    if (egvCode == 200) {
                        val resp = com.google.gson.Gson().fromJson(egvBody, EgvsResponse::class.java)
                        val all     = resp?.egvs ?: emptyList()
                        val nonZero = all.filter { it.glucoseValue() > 0 }
                        sb.appendLine("Raw EGV count:   ${all.size}")
                        sb.appendLine("After >0 filter: ${nonZero.size}")
                        if (all.isNotEmpty()) {
                            val first = all.first()
                            sb.appendLine("First: t=${first.systemTime} v=${first.value} rt=${first.realtimeValue} sm=${first.smoothedValue}")
                        }
                    }
                } catch (e: Exception) {
                    sb.appendLine("Error: ${e.javaClass.simpleName}: ${e.message}")
                }
                sb.toString().trim()
            }
        }
    }
}
