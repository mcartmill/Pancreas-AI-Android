package com.pancreas.ai

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

const val DEXCOM_BASE_PROD    = "https://api.dexcom.com/"
const val DEXCOM_BASE_SANDBOX = "https://sandbox-api.dexcom.com/"
const val REDIRECT_URI        = "https://localhost/callback"
const val OAUTH_SCOPE         = "offline_access"

data class TokenResponse(
    @SerializedName("access_token")  val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,   // null on some sandbox responses
    @SerializedName("expires_in")    val expiresIn: Int?,
    @SerializedName("token_type")    val tokenType: String?
)

data class EgvsResponse(
    @SerializedName("records")  val egvs: List<EgvReading>?,
    @SerializedName("unit")     val unit: String?,
    @SerializedName("rateUnit") val rateUnit: String?
)

data class DataRangeResponse(
    @SerializedName("egvs") val egvs: DataRangeItem?
)

data class DataRangeItem(
    @SerializedName("start") val start: TimeRecord?,
    @SerializedName("end")   val end: TimeRecord?
)

data class TimeRecord(
    @SerializedName("systemTime")  val systemTime: String,
    @SerializedName("displayTime") val displayTime: String
)

data class EgvReading(
    @SerializedName("systemTime")    val systemTime: String?,
    @SerializedName("displayTime")   val displayTime: String?,
    // G7 / standard: `value` is the primary reading
    @SerializedName("value")         val value: Int?,
    // G6 smoothing: smoothedValue may be null at session edges
    @SerializedName("smoothedValue") val smoothedValue: Int?,
    // realtimeValue is always populated when a sensor reading exists
    @SerializedName("realtimeValue") val realtimeValue: Int?,
    @SerializedName("status")        val status: String?,
    @SerializedName("trend")         val trend: String?,
    @SerializedName("trendRate")     val trendRate: Double?
) {
    /**
     * Returns the best available glucose reading.
     * Priority: value (= smoothedValue for G6, direct for G7) → realtimeValue → smoothedValue
     * All three are checked so neither device type silently returns 0.
     */
    fun glucoseValue(): Int = value ?: realtimeValue ?: smoothedValue ?: 0

    fun epochMillis(): Long {
        if (systemTime.isNullOrBlank()) return 0L
        return try {
            // Strip UTC offset (+00:00 / -05:00 / Z) leaving plain datetime
            val cleaned = systemTime
                .replace(Regex("[+-]\\d{2}:\\d{2}$"), "")
                .replace("Z", "")
                .trim()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(cleaned)?.time ?: 0L
        } catch (e: Exception) {
            android.util.Log.e("EgvReading", "Failed to parse time: $systemTime", e)
            0L
        }
    }

    fun trendArrow(): String = when (trend?.lowercase()) {
        "doubleup"       -> "↑↑"
        "singleup"       -> "↑"
        "fortyfiveup"    -> "↗"
        "flat"           -> "→"
        "fortyfivedown"  -> "↘"
        "singledown"     -> "↓"
        "doubledown"     -> "↓↓"
        "notcomputable"  -> "?"
        "rateoutofrange" -> "⚠"
        else             -> "–"
    }

    fun glucoseColor(): Int = when (val v = glucoseValue()) {
        in 1..69   -> android.graphics.Color.parseColor("#FF4444")
        in 70..79  -> android.graphics.Color.parseColor("#FF8800")
        in 80..180 -> android.graphics.Color.parseColor("#00E676")
        in 181..250 -> android.graphics.Color.parseColor("#FF8800")
        else       -> if (v > 250) android.graphics.Color.parseColor("#FF4444")
                      else android.graphics.Color.parseColor("#546E7A")
    }

    fun glucoseColor(low: Int, high: Int): Int {
        val v = glucoseValue()
        return when {
            v < low         -> android.graphics.Color.parseColor("#FF4444")
            v <= low + 10   -> android.graphics.Color.parseColor("#FF8800")
            v <= high       -> android.graphics.Color.parseColor("#00E676")
            v <= high + 40  -> android.graphics.Color.parseColor("#FF8800")
            else            -> android.graphics.Color.parseColor("#FF4444")
        }
    }
}

interface DexcomApiService {

    @FormUrlEncoded
    @POST("v2/oauth2/token")
    suspend fun getToken(
        @Field("client_id")     clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code")          code: String,
        @Field("grant_type")    grantType: String = "authorization_code",
        @Field("redirect_uri")  redirectUri: String = REDIRECT_URI
    ): Response<TokenResponse>

    @FormUrlEncoded
    @POST("v2/oauth2/token")
    suspend fun refreshToken(
        @Field("client_id")     clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("refresh_token") refreshToken: String,
        @Field("grant_type")    grantType: String = "refresh_token",
        @Field("redirect_uri")  redirectUri: String = REDIRECT_URI
    ): Response<TokenResponse>

    @GET("v3/users/self/egvs")
    suspend fun getEgvs(
        @Header("Authorization") bearerToken: String,
        @Query(value = "startDate", encoded = true) startDate: String,
        @Query(value = "endDate",   encoded = true) endDate: String
    ): Response<EgvsResponse>

    @GET("v3/users/self/dataRange")
    suspend fun getDataRange(
        @Header("Authorization") bearerToken: String
    ): Response<DataRangeResponse>
}

object DexcomRetrofit {
    fun create(useSandbox: Boolean = false): DexcomApiService {
        val baseUrl = if (useSandbox) DEXCOM_BASE_SANDBOX else DEXCOM_BASE_PROD
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DexcomApiService::class.java)
    }
}
