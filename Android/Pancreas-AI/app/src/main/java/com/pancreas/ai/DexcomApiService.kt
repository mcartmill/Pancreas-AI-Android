package com.pancreas.ai

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

// ─── Data Models ─────────────────────────────────────────────────────────────

data class LoginRequest(
    @SerializedName("accountName") val accountName: String,
    @SerializedName("password") val password: String,
    @SerializedName("applicationId") val applicationId: String = DEXCOM_APP_ID
)

data class GlucoseReading(
    @SerializedName("WT") val wt: String,         // Wall time e.g. "Date(1700000000000)"
    @SerializedName("ST") val st: String,          // System time
    @SerializedName("DT") val dt: String,          // Display time
    @SerializedName("Value") val value: Int,        // mg/dL
    @SerializedName("Trend") val trend: String      // e.g. "Flat", "FortyFiveUp", "SingleUp"
) {
    /** Extract epoch millis from the Dexcom /Date(...)/ format */
    fun epochMillis(): Long {
        val raw = wt.removePrefix("Date(").removeSuffix(")")
        return raw.toLongOrNull() ?: 0L
    }

    fun trendArrow(): String = when (trend) {
        "DoubleUp"         -> "↑↑"
        "SingleUp"         -> "↑"
        "FortyFiveUp"      -> "↗"
        "Flat"             -> "→"
        "FortyFiveDown"    -> "↘"
        "SingleDown"       -> "↓"
        "DoubleDown"       -> "↓↓"
        "NotComputable"    -> "?"
        "RateOutOfRange"   -> "⚠"
        else               -> "–"
    }

    fun glucoseColor(): Int {
        return when {
            value < 70  -> android.graphics.Color.parseColor("#FF4444")   // Low  – red
            value < 80  -> android.graphics.Color.parseColor("#FF8800")   // Near-low – orange
            value <= 180 -> android.graphics.Color.parseColor("#00E676")  // In range – green
            value <= 250 -> android.graphics.Color.parseColor("#FF8800")  // High – orange
            else         -> android.graphics.Color.parseColor("#FF4444")  // Very high – red
        }
    }
}

// ─── Retrofit Interface ───────────────────────────────────────────────────────

interface DexcomApiService {

    /** Returns a session ID (quoted string) */
    @Headers("Content-Type: application/json", "Accept: application/json")
    @POST("ShareWebServices/Services/General/LoginPublisherAccountByName")
    suspend fun login(@Body request: LoginRequest): Response<String>

    /**
     * Fetch latest glucose readings.
     * @param minutes  how many minutes of history (max 1440 = 24 h)
     * @param maxCount max number of readings (288 ≈ 24 h at 5-min intervals)
     */
    @GET("ShareWebServices/Services/Publisher/ReadPublisherLatestGlucoseValues")
    suspend fun getGlucoseReadings(
        @Query("sessionId") sessionId: String,
        @Query("minutes")   minutes: Int = 1440,
        @Query("maxCount")  maxCount: Int = 288
    ): Response<List<GlucoseReading>>
}

// ─── Constants ────────────────────────────────────────────────────────────────

const val DEXCOM_APP_ID       = "d89443d2-327c-4a6f-89e5-496bbb0317db"
const val DEXCOM_BASE_US      = "https://share2.dexcom.com/"
const val DEXCOM_BASE_OUTSIDE = "https://shareous1.dexcom.com/"

// ─── Factory ─────────────────────────────────────────────────────────────────

object DexcomRetrofit {
    fun create(baseUrl: String): DexcomApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DexcomApiService::class.java)
    }
}
