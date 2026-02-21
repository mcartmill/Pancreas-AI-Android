package com.pancreas.ai

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

const val SHARE_US_BASE  = "https://share2.dexcom.com/ShareWebServices/Services/"
const val SHARE_OUS_BASE = "https://shareous1.dexcom.com/ShareWebServices/Services/"

// The Dexcom Share application ID – required by the API
private const val SHARE_APP_ID = "d89443d2-327c-4a6f-89e5-496bbb0317db"

data class ShareLoginRequest(
    @SerializedName("accountName")   val accountName: String,
    @SerializedName("password")      val password: String,
    @SerializedName("applicationId") val applicationId: String = SHARE_APP_ID
)

/** Raw reading from the Share API */
data class ShareReading(
    @SerializedName("WT")    val wt: String,     // "Date(1234567890000)"
    @SerializedName("ST")    val st: String,
    @SerializedName("DT")    val dt: String,
    @SerializedName("Value") val value: Int,
    @SerializedName("Trend") val trend: String   // "Flat", "SingleUp", etc.
) {
    /** Extract epoch ms from "Date(1234567890000)" */
    fun epochMillis(): Long = Regex("\\d+").find(wt)?.value?.toLongOrNull() ?: 0L

    fun toEgvReading(): EgvReading {
        val ts = epochMillis()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val systemTime = fmt.format(java.util.Date(ts))

        // Map Share trend strings to the OAuth trend strings that EgvReading understands
        val mappedTrend = when (trend) {
            "DoubleUp"       -> "doubleup"
            "SingleUp"       -> "singleup"
            "FortyFiveUp"    -> "fortyfiveup"
            "Flat"           -> "flat"
            "FortyFiveDown"  -> "fortyfivedown"
            "SingleDown"     -> "singledown"
            "DoubleDown"     -> "doubledown"
            "NotComputable"  -> "notcomputable"
            "RateOutOfRange" -> "rateoutofrange"
            else             -> "flat"
        }

        return EgvReading(
            systemTime    = systemTime,
            displayTime   = systemTime,
            value         = value,
            smoothedValue = value,
            realtimeValue = value,
            status        = null,
            trend         = mappedTrend,
            trendRate     = null
        )
    }
}

interface DexcomShareService {
    @POST("General/LoginPublisherAccountByName")
    suspend fun login(@Body request: ShareLoginRequest): Response<String>

    @GET("Publisher/ReadPublisherLatestGlucoseValues")
    suspend fun getReadings(
        @Query("sessionId") sessionId: String,
        @Query("minutes")   minutes: Int,
        @Query("maxCount")  maxCount: Int = 288
    ): Response<List<ShareReading>>
}

object ShareRetrofit {
    fun create(outsideUs: Boolean): DexcomShareService {
        val base = if (outsideUs) SHARE_OUS_BASE else SHARE_US_BASE
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val gson = com.google.gson.GsonBuilder().setLenient().create()
        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(DexcomShareService::class.java)
    }
}
