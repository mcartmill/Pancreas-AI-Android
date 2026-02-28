package com.pancreas.ai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object GlucoseAlertManager {

    private const val TAG           = "GlucoseAlertManager"
    private const val CHANNEL_ID    = "glucose_alerts"
    private const val CHANNEL_NAME  = "Glucose Alerts"
    private const val NOTIF_ID_HIGH = 1001
    private const val NOTIF_ID_LOW  = 1002

    // Minimum gap between repeated alerts of the same type (30 minutes)
    private const val ALERT_COOLDOWN_MS = 30 * 60 * 1000L

    fun createNotificationChannel(ctx: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Projected high and low glucose warnings"
            enableVibration(true)
        }
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * Evaluate recent readings and fire a notification if glucose is projected
     * to cross the high or low threshold within the configured window.
     *
     * Algorithm:
     *  1. Take the last N readings sorted by time (needs ≥ 2)
     *  2. Compute a simple linear rate of change (mg/dL per minute) from the
     *     most recent pair, weighted with the prior pair if available
     *  3. Project forward [projectionMinutes] and check thresholds
     *  4. Respect a 30-minute cooldown so we don't spam
     */
    fun evaluate(ctx: Context, readings: List<EgvReading>) {
        if (!CredentialsManager.isNotificationsEnabled(ctx)) return
        if (readings.size < 2) return

        // Work with the 4 most recent readings
        val recent = readings.sortedBy { it.epochMillis() }.takeLast(4)

        // Rate of change: weighted average of last two intervals
        val rateMinute = weightedRate(recent)   // mg/dL per minute
        if (rateMinute.isNaN()) return

        val currentGlucose = recent.last().glucoseValue()
        val projMinutes    = CredentialsManager.getProjectionMinutes(ctx).toFloat()
        val projected      = currentGlucose + (rateMinute * projMinutes)

        Log.d(TAG, "Current=$currentGlucose  rate=%.2f mg/min  projected(${projMinutes}m)=%.1f"
            .format(rateMinute, projected))

        val high = CredentialsManager.getGlucoseHigh(ctx)
        val low  = CredentialsManager.getGlucoseLow(ctx)
        val now  = System.currentTimeMillis()

        // ── High projection ───────────────────────────────────────────────────
        if (CredentialsManager.isPredictHighEnabled(ctx) && projected >= high) {
            val lastSent = CredentialsManager.getLastHighNotifMs(ctx)
            if (now - lastSent > ALERT_COOLDOWN_MS) {
                sendNotification(
                    ctx        = ctx,
                    id         = NOTIF_ID_HIGH,
                    title      = "⚠️ High Glucose Projected",
                    body       = "Glucose is $currentGlucose and rising. " +
                                 "Projected to reach ${projected.toInt()} mg/dL in ${projMinutes.toInt()} min " +
                                 "(target <$high)",
                    isUrgent   = projected >= high + 40
                )
                CredentialsManager.markHighNotifSent(ctx)
            }
        }

        // ── Low projection ────────────────────────────────────────────────────
        if (CredentialsManager.isPredictLowEnabled(ctx) && projected <= low) {
            val lastSent = CredentialsManager.getLastLowNotifMs(ctx)
            if (now - lastSent > ALERT_COOLDOWN_MS) {
                sendNotification(
                    ctx        = ctx,
                    id         = NOTIF_ID_LOW,
                    title      = "🚨 Low Glucose Projected",
                    body       = "Glucose is $currentGlucose and dropping. " +
                                 "Projected to reach ${projected.toInt()} mg/dL in ${projMinutes.toInt()} min " +
                                 "(target >$low)",
                    isUrgent   = true
                )
                CredentialsManager.markLowNotifSent(ctx)
            }
        }
    }

    private fun weightedRate(readings: List<EgvReading>): Double {
        // readings are sorted oldest→newest
        val n = readings.size
        if (n < 2) return Double.NaN

        fun rate(a: EgvReading, b: EgvReading): Double {
            val dt = (b.epochMillis() - a.epochMillis()) / 60_000.0   // minutes
            if (dt <= 0) return Double.NaN
            return (b.glucoseValue() - a.glucoseValue()) / dt
        }

        val r1 = rate(readings[n - 2], readings[n - 1])
        if (n < 3) return r1

        val r2 = rate(readings[n - 3], readings[n - 2])
        if (r2.isNaN()) return r1
        // Weight most recent interval 2:1
        return (r1 * 2 + r2) / 3.0
    }

    private fun sendNotification(
        ctx: Context, id: Int, title: String, body: String, isUrgent: Boolean
    ) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted — skipping notification")
            return
        }

        val tapIntent = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (isUrgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(ctx).notify(id, notification)
        Log.d(TAG, "Notification sent: $title")
    }
}
