package com.pancreas.ai

import android.content.Context
import android.util.Log

class GlucoseRepository(private val ctx: Context) {

    private val TAG = "GlucoseRepository"

    private fun api(): DexcomApiService =
        DexcomRetrofit.create(CredentialsManager.getBaseUrl(ctx))

    /** Returns a valid session ID, re-authenticating if needed. */
    private suspend fun getValidSession(): String {
        val cached = CredentialsManager.getSessionId(ctx)
        if (cached != null) return cached
        return login()
    }

    /** Authenticate and store session. Throws on failure. */
    suspend fun login(): String {
        val username = CredentialsManager.getUsername(ctx)
        val password = CredentialsManager.getPassword(ctx)

        if (username.isBlank() || password.isBlank()) {
            throw IllegalStateException("Credentials not configured. Please open Settings.")
        }

        val response = api().login(LoginRequest(username, password))

        if (!response.isSuccessful) {
            val error = response.errorBody()?.string() ?: "Unknown error"
            Log.e(TAG, "Login failed: ${response.code()} $error")
            throw Exception("Login failed (${response.code()}): Check your username and password.")
        }

        // Dexcom returns the session ID as a quoted JSON string, strip quotes
        val sessionId = response.body()?.trim('"')
            ?: throw Exception("Empty session ID returned by Dexcom.")

        if (sessionId == "00000000-0000-0000-0000-000000000000") {
            throw Exception("Invalid credentials — Dexcom rejected your username or password.")
        }

        CredentialsManager.saveSessionId(ctx, sessionId)
        Log.d(TAG, "Login successful, session: $sessionId")
        return sessionId
    }

    /**
     * Fetch glucose readings, automatically retrying once if the session is stale.
     */
    suspend fun fetchReadings(
        minutes: Int = 1440,
        maxCount: Int = 288
    ): List<GlucoseReading> {
        return try {
            fetchWithSession(getValidSession(), minutes, maxCount)
        } catch (e: SessionExpiredException) {
            Log.w(TAG, "Session expired, re-authenticating…")
            CredentialsManager.clearSession(ctx)
            fetchWithSession(login(), minutes, maxCount)
        }
    }

    private suspend fun fetchWithSession(
        sessionId: String,
        minutes: Int,
        maxCount: Int
    ): List<GlucoseReading> {
        val response = api().getGlucoseReadings(sessionId, minutes, maxCount)

        if (response.code() == 500) {
            // Dexcom uses 500 for expired / invalid sessions
            throw SessionExpiredException()
        }

        if (!response.isSuccessful) {
            throw Exception("Failed to fetch readings: HTTP ${response.code()}")
        }

        val readings = response.body() ?: emptyList()
        Log.d(TAG, "Fetched ${readings.size} readings")
        return readings.sortedBy { it.epochMillis() }
    }

    private class SessionExpiredException : Exception("Dexcom session expired")
}
