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
    @SerializedName("access_token")  val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("expires_in")    val expiresIn: Int,
    @SerializedName("token_type")    val tokenType: String
)

data class EgvsResponse(
    @SerializedName("egvs") val egvs: List<EgvReading>?,
    @SerializedName("unit") val unit: String?
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
    @SerializedName("systemTime")    val systemTime: String,
    @SerializedName("displayTime")   val displayTime: String,
    @SerializedName("value")         val value: Int?,
    @SerializedName("realtimeValue") val realtimeValue: Int?,
    @SerializedName("status")        val status: String?,
    @SerializedName("trend")         val trend: String,
    @SerializedName("trendRate")     val trendRate: Double?
) {
    fun epochMillis(): Long = try {
        // Handle both "2024-01-01T12:00:00" and "2024-01-01T12:00:00+00:00"
        val s = systemTime.replace(Regex("[+-]\\d{2}:\\d{2}$"), "").replace("Z", "")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        fmt.parse(s)?.time ?: 0L
    } catch (e: Exception) { 0L }

    fun glucoseValue(): Int = value ?: realtimeValue ?: 0

    fun trendArrow(): String = when (trend.lowercase()) {
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

    fun glucoseColor(): Int {
        val v = glucoseValue()
        return when {
            v < 70   -> android.graphics.Color.parseColor("#FF4444")
            v < 80   -> android.graphics.Color.parseColor("#FF8800")
            v <= 180 -> android.graphics.Color.parseColor("#00E676")
            v <= 250 -> android.graphics.Color.parseColor("#FF8800")
            else     -> android.graphics.Color.parseColor("#FF4444")
        }
    }
}

interface DexcomApiService {

    // Token exchange — v2 endpoint (correct per Dexcom docs)
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

    // EGV readings — v3 endpoint
    @GET("v3/users/self/egvs")
    suspend fun getEgvs(
        @Header("Authorization") bearerToken: String,
        @Query("startDate")      startDate: String,
        @Query("endDate")        endDate: String
    ): Response<EgvsResponse>

    // DataRange — tells us the earliest/latest data available for this user
    @GET("v3/users/self/dataRange")
    suspend fun getDataRange(
        @Header("Authorization") bearerToken: String
    ): Response<DataRangeResponse>
}

object DexcomRetrofit {
    fun create(useSandbox: Boolean = false): DexcomApiService {
        val baseUrl = if (useSandbox) DEXCOM_BASE_SANDBOX else DEXCOM_BASE_PROD
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
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
