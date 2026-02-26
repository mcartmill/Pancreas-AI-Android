package com.pancreas.ai

import android.content.Context
import java.util.Calendar

// ── Data classes ──────────────────────────────────────────────────────────────

data class PostMealCurve(
    val foodEntry: FoodEntry,
    val baselineGlucose: Int,       // glucose ~0-15 min before meal
    val peak: Int,                  // highest glucose in 3h window
    val peakMinutes: Int,           // minutes to peak
    val glucoseAt60: Int,
    val glucoseAt120: Int,
    val glucoseAt180: Int,
    val deltaAt60: Int,             // rise from baseline
    val deltaAt120: Int,
    val deltaAt180: Int
)

data class PostInsulinCurve(
    val insulinEntry: InsulinEntry,
    val baselineGlucose: Int,
    val nadir: Int,                 // lowest glucose in 4h window
    val nadirMinutes: Int,
    val glucoseAt60: Int,
    val glucoseAt120: Int,
    val glucoseAt180: Int,
    val dropAt60: Int,
    val dropAt120: Int,
    val dropAt180: Int
)

data class TimeOfDayStats(
    val slotLabel: String,
    val avgGlucose: Double,
    val pctInRange: Int,
    val pctLow: Int,
    val pctHigh: Int,
    val count: Int
)

data class InsightResult(
    val postMealCurves: List<PostMealCurve>,
    val postInsulinCurves: List<PostInsulinCurve>,
    val timeOfDayStats: List<TimeOfDayStats>,
    val estimatedIsf: Double?,          // mg/dL drop per 1 unit insulin
    val estimatedIcr: Double?,          // grams of carbs per 1 unit insulin
    val avgPostMealRise: Double?,       // avg glucose rise at 60 min post-meal
    val hypoglycemiaHours: List<Int>,   // hours of day where lows are most common
    val highGlucoseHours: List<Int>,    // hours where highs are most common
    val totalReadings: Int,
    val dataSpanDays: Int,
    val warningsAndTips: List<String>
)

// ── Analyzer ──────────────────────────────────────────────────────────────────

object InsightsAnalyzer {

    private val TIME_SLOTS = listOf(
        "12–4 AM" to (0..3),
        "4–8 AM"  to (4..7),
        "8 AM–12 PM" to (8..11),
        "12–4 PM" to (12..15),
        "4–8 PM"  to (16..19),
        "8 PM–12 AM" to (20..23)
    )

    fun analyze(ctx: Context): InsightResult {
        val allGlucose = GlucoseLog.load(ctx)
        val allFood    = FoodManager.load(ctx)
        val allInsulin = InsulinManager.load(ctx)

        val postMeal    = computePostMealCurves(allGlucose, allFood)
        val postInsulin = computePostInsulinCurves(allGlucose, allInsulin)
        val todStats    = computeTimeOfDay(allGlucose)

        val estimatedIsf = estimateIsf(postInsulin)
        val estimatedIcr = estimateIcr(postMeal, postInsulin)
        val avgRise      = postMeal.map { it.deltaAt60 }.filter { it > 0 }
            .takeIf { it.isNotEmpty() }?.average()

        val hypoHours = allGlucose.filter { it.mg < 70 }
            .map { hourOf(it.epochMs) }
            .groupBy { it }.entries.sortedByDescending { it.value.size }
            .take(3).map { it.key }

        val highHours = allGlucose.filter { it.mg > 180 }
            .map { hourOf(it.epochMs) }
            .groupBy { it }.entries.sortedByDescending { it.value.size }
            .take(3).map { it.key }

        val dataSpan = if (allGlucose.size < 2) 0
        else ((allGlucose.maxOf { it.epochMs } - allGlucose.minOf { it.epochMs }) / 86_400_000L).toInt()

        val warnings = buildWarnings(todStats, estimatedIsf, avgRise, allGlucose, postMeal)

        return InsightResult(
            postMealCurves      = postMeal,
            postInsulinCurves   = postInsulin,
            timeOfDayStats      = todStats,
            estimatedIsf        = estimatedIsf,
            estimatedIcr        = estimatedIcr,
            avgPostMealRise     = avgRise,
            hypoglycemiaHours   = hypoHours,
            highGlucoseHours    = highHours,
            totalReadings       = allGlucose.size,
            dataSpanDays        = dataSpan,
            warningsAndTips     = warnings
        )
    }

    // ── Post-meal curves ──────────────────────────────────────────────────────

    private fun computePostMealCurves(
        glucose: List<StoredReading>,
        food: List<FoodEntry>
    ): List<PostMealCurve> {
        if (glucose.isEmpty()) return emptyList()
        val sorted = glucose.sortedBy { it.epochMs }

        return food.mapNotNull { meal ->
            val t0 = meal.timestampMs
            val baseline = closestGlucose(sorted, t0 - 10 * 60_000L, 20 * 60_000L) ?: return@mapNotNull null
            val at60  = closestGlucose(sorted, t0 + 55 * 60_000L, 15 * 60_000L)
            val at120 = closestGlucose(sorted, t0 + 115 * 60_000L, 15 * 60_000L)
            val at180 = closestGlucose(sorted, t0 + 175 * 60_000L, 15 * 60_000L)

            // Find peak in 3h window
            val window = sorted.filter { it.epochMs in t0..(t0 + 3 * 3_600_000L) }
            val peakReading = window.maxByOrNull { it.mg } ?: return@mapNotNull null

            PostMealCurve(
                foodEntry       = meal,
                baselineGlucose = baseline.mg,
                peak            = peakReading.mg,
                peakMinutes     = ((peakReading.epochMs - t0) / 60_000L).toInt(),
                glucoseAt60     = at60?.mg ?: -1,
                glucoseAt120    = at120?.mg ?: -1,
                glucoseAt180    = at180?.mg ?: -1,
                deltaAt60       = if (at60 != null) at60.mg - baseline.mg else -999,
                deltaAt120      = if (at120 != null) at120.mg - baseline.mg else -999,
                deltaAt180      = if (at180 != null) at180.mg - baseline.mg else -999
            )
        }
    }

    // ── Post-insulin curves ───────────────────────────────────────────────────

    private fun computePostInsulinCurves(
        glucose: List<StoredReading>,
        insulin: List<InsulinEntry>
    ): List<PostInsulinCurve> {
        if (glucose.isEmpty()) return emptyList()
        val sorted = glucose.sortedBy { it.epochMs }

        // Only use rapid-acting insulin for ISF calculations
        return insulin.filter { it.type == InsulinType.RAPID || it.type == InsulinType.OTHER }
            .mapNotNull { dose ->
                val t0 = dose.timestampMs
                val baseline = closestGlucose(sorted, t0, 20 * 60_000L) ?: return@mapNotNull null
                if (baseline.mg < 100) return@mapNotNull null  // skip if already low — skews ISF

                val at60  = closestGlucose(sorted, t0 + 55 * 60_000L, 15 * 60_000L)
                val at120 = closestGlucose(sorted, t0 + 115 * 60_000L, 15 * 60_000L)
                val at180 = closestGlucose(sorted, t0 + 175 * 60_000L, 15 * 60_000L)

                val window = sorted.filter { it.epochMs in t0..(t0 + 4 * 3_600_000L) }
                val nadir  = window.minByOrNull { it.mg } ?: return@mapNotNull null

                PostInsulinCurve(
                    insulinEntry    = dose,
                    baselineGlucose = baseline.mg,
                    nadir           = nadir.mg,
                    nadirMinutes    = ((nadir.epochMs - t0) / 60_000L).toInt(),
                    glucoseAt60     = at60?.mg ?: -1,
                    glucoseAt120    = at120?.mg ?: -1,
                    glucoseAt180    = at180?.mg ?: -1,
                    dropAt60        = if (at60 != null) baseline.mg - at60.mg else -999,
                    dropAt120       = if (at120 != null) baseline.mg - at120.mg else -999,
                    dropAt180       = if (at180 != null) baseline.mg - at180.mg else -999
                )
            }
    }

    // ── Time-of-day stats ─────────────────────────────────────────────────────

    private fun computeTimeOfDay(glucose: List<StoredReading>): List<TimeOfDayStats> {
        return TIME_SLOTS.map { (label, hours) ->
            val readings = glucose.filter { hourOf(it.epochMs) in hours }
            if (readings.isEmpty()) {
                TimeOfDayStats(label, 0.0, 0, 0, 0, 0)
            } else {
                val mg    = readings.map { it.mg }
                val total = mg.size.toDouble()
                TimeOfDayStats(
                    slotLabel  = label,
                    avgGlucose = mg.average(),
                    pctInRange = (mg.count { it in 70..180 } / total * 100).toInt(),
                    pctLow     = (mg.count { it < 70 }       / total * 100).toInt(),
                    pctHigh    = (mg.count { it > 180 }       / total * 100).toInt(),
                    count      = mg.size
                )
            }
        }
    }

    // ── ISF / ICR estimation ──────────────────────────────────────────────────

    private fun estimateIsf(curves: List<PostInsulinCurve>): Double? {
        val valid = curves.filter { it.dropAt120 > 0 && it.insulinEntry.units > 0 }
        if (valid.size < 3) return null
        val isfValues = valid.map { it.dropAt120.toDouble() / it.insulinEntry.units }
        // Trim outliers: remove top and bottom 20%
        val trimmed = isfValues.sorted().let {
            val cut = (it.size * 0.2).toInt().coerceAtLeast(1)
            it.drop(cut).dropLast(cut)
        }
        return trimmed.takeIf { it.isNotEmpty() }?.average()
    }

    private fun estimateIcr(
        meals: List<PostMealCurve>,
        insulin: List<PostInsulinCurve>
    ): Double? {
        if (meals.size < 5 || insulin.size < 3) return null
        val avgRise = meals.map { it.deltaAt60.toDouble() }.filter { it > 0 }.average()
        val isf = estimateIsf(insulin) ?: return null
        if (avgRise <= 0 || isf <= 0) return null
        val avgCarbs = meals.map { it.foodEntry.carbs }.average()
        // Rough ICR from: unitsNeeded = rise/ISF, ICR = carbs/unitsNeeded
        val unitsNeeded = avgRise / isf
        return if (unitsNeeded > 0) avgCarbs / unitsNeeded else null
    }

    // ── Warnings & tips ───────────────────────────────────────────────────────

    private fun buildWarnings(
        tod: List<TimeOfDayStats>,
        isf: Double?,
        avgRise: Double?,
        glucose: List<StoredReading>,
        meals: List<PostMealCurve>
    ): List<String> {
        val tips = mutableListOf<String>()

        // High post-meal spikes
        if (avgRise != null && avgRise > 60) {
            tips.add("Your glucose rises an average of ${"%.0f".format(avgRise)} mg/dL within 60 min of eating. " +
                "Consider taking rapid insulin 15–20 min before meals rather than at meal time.")
        }

        // Dawn phenomenon
        val earlyAm = tod.find { it.slotLabel == "4–8 AM" }
        val overnight = tod.find { it.slotLabel == "12–4 AM" }
        if (earlyAm != null && overnight != null &&
            earlyAm.avgGlucose > 140 && earlyAm.avgGlucose > overnight.avgGlucose + 20) {
            tips.add("Dawn phenomenon detected: your glucose rises significantly between 4–8 AM " +
                "(avg ${"%.0f".format(earlyAm.avgGlucose)} mg/dL). Consider adjusting basal insulin or " +
                "discussing timing with your care team.")
        }

        // Nocturnal lows
        val nightSlot = tod.find { it.slotLabel == "12–4 AM" }
        if (nightSlot != null && nightSlot.pctLow > 10) {
            tips.add("${nightSlot.pctLow}% of your overnight readings are below 70 mg/dL. " +
                "Nocturnal hypoglycemia is a safety concern — consider reducing evening long-acting insulin.")
        }

        // Consistent post-meal highs at specific time of day
        val lunchHighMeals = meals.filter {
            val h = hourOf(it.foodEntry.timestampMs)
            h in 11..13 && it.peak > 200
        }
        if (lunchHighMeals.size >= 3) {
            tips.add("Lunchtime meals frequently spike above 200 mg/dL. " +
                "Try lower-carb options or pre-bolusing 15–20 min before eating.")
        }

        // Overall time-in-range
        if (glucose.size > 50) {
            val tir = glucose.count { it.mg in 70..180 } * 100 / glucose.size
            if (tir < 50) {
                tips.add("Overall time-in-range is $tir%. The clinical target is 70%+. " +
                    "Use the AI Suggestions feature for personalized recommendations.")
            }
        }

        // Slow peak timing
        val slowPeak = meals.filter { it.peakMinutes > 120 && it.peakMinutes < 240 }
        if (slowPeak.size > meals.size / 2 && meals.size >= 5) {
            tips.add("Your glucose tends to peak late (${slowPeak.first().peakMinutes} min average after eating). " +
                "High-fat or high-protein meals can slow glucose absorption — consider extended or split bolusing.")
        }

        if (tips.isEmpty() && glucose.size > 100) {
            tips.add("Patterns look reasonable. Keep logging meals and insulin to get more precise recommendations.")
        }
        if (glucose.size < 30) {
            tips.add("Keep using the app to build up data. Insights become more accurate with 2+ weeks of readings.")
        }

        return tips
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun closestGlucose(
        sorted: List<StoredReading>, targetMs: Long, windowMs: Long
    ): StoredReading? =
        sorted.filter { kotlin.math.abs(it.epochMs - targetMs) <= windowMs }
            .minByOrNull { kotlin.math.abs(it.epochMs - targetMs) }

    private fun hourOf(epochMs: Long): Int =
        Calendar.getInstance().also { it.timeInMillis = epochMs }.get(Calendar.HOUR_OF_DAY)

    // ── Summary for Claude API ────────────────────────────────────────────────

    fun buildAiPrompt(result: InsightResult, ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("You are a diabetes management assistant. Analyze this patient's CGM and tracking data and provide specific, actionable insulin dosing suggestions. Be precise with numbers. Note: always recommend they discuss changes with their doctor.")
        sb.appendLine()
        sb.appendLine("=== DATA SUMMARY ===")
        sb.appendLine("Data span: ${result.dataSpanDays} days, ${result.totalReadings} glucose readings")

        val allGlucose = GlucoseLog.load(ctx)
        if (allGlucose.isNotEmpty()) {
            val avg = allGlucose.map { it.mg }.average()
            val tir = allGlucose.count { it.mg in 70..180 } * 100 / allGlucose.size
            val low = allGlucose.count { it.mg < 70 } * 100 / allGlucose.size
            val hi  = allGlucose.count { it.mg > 180 } * 100 / allGlucose.size
            sb.appendLine("Overall avg glucose: ${"%.0f".format(avg)} mg/dL")
            sb.appendLine("Time in range (70-180): $tir%  |  Time low: $low%  |  Time high: $hi%")
            sb.appendLine("Estimated A1c: ${"%.1f".format((avg + 46.7) / 28.7)}%")
        }

        if (result.estimatedIsf != null) {
            sb.appendLine("Estimated insulin sensitivity factor (ISF): ${"%.0f".format(result.estimatedIsf)} mg/dL per unit")
        }
        if (result.estimatedIcr != null) {
            sb.appendLine("Estimated insulin-to-carb ratio (ICR): 1 unit per ${"%.0f".format(result.estimatedIcr)}g carbs")
        }
        if (result.avgPostMealRise != null) {
            sb.appendLine("Average glucose rise at 60 min post-meal: +${"%.0f".format(result.avgPostMealRise)} mg/dL")
        }

        sb.appendLine()
        sb.appendLine("=== TIME OF DAY PATTERNS ===")
        result.timeOfDayStats.filter { it.count > 0 }.forEach { s ->
            sb.appendLine("${s.slotLabel}: avg ${"%.0f".format(s.avgGlucose)} mg/dL, in-range ${s.pctInRange}%, low ${s.pctLow}%, high ${s.pctHigh}% (n=${s.count})")
        }

        if (result.postMealCurves.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("=== POST-MEAL RESPONSES (recent ${result.postMealCurves.take(10).size} meals) ===")
            result.postMealCurves.take(10).forEach { m ->
                sb.appendLine("  ${m.foodEntry.name.ifBlank { m.foodEntry.mealType.label }}: " +
                    "${"%.0f".format(m.foodEntry.carbs)}g carbs, baseline ${m.baselineGlucose}, " +
                    "peak +${m.peak - m.baselineGlucose} at ${m.peakMinutes}min, " +
                    "+${m.deltaAt60} at 60min, +${m.deltaAt120} at 120min")
            }
        }

        if (result.postInsulinCurves.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("=== POST-INSULIN RESPONSES (recent ${result.postInsulinCurves.take(8).size} doses) ===")
            result.postInsulinCurves.take(8).forEach { i ->
                sb.appendLine("  ${"%.1f".format(i.insulinEntry.units)}u ${i.insulinEntry.type.label}: " +
                    "baseline ${i.baselineGlucose}, nadir ${i.nadir} at ${i.nadirMinutes}min, " +
                    "drop at 60=${i.dropAt60}, 120=${i.dropAt120}")
            }
        }

        if (result.hypoglycemiaHours.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Most common hypoglycemia hours: ${result.hypoglycemiaHours.joinToString { "${it}:00" }}")
        }

        sb.appendLine()
        sb.appendLine("=== REQUEST ===")
        sb.appendLine("Based on this data, please provide:")
        sb.appendLine("1. Assessment of current glucose control")
        sb.appendLine("2. Specific insulin timing recommendations (e.g. pre-bolus timing)")
        sb.appendLine("3. Suggested ISF and ICR adjustments if data supports it")
        sb.appendLine("4. Suggestions to reduce post-meal spikes")
        sb.appendLine("5. Any patterns that suggest basal insulin adjustment")
        sb.appendLine("6. Target glucose ranges and how close this patient is to non-diabetic levels")
        sb.appendLine()
        sb.appendLine("Be specific with numbers and timing. Keep response under 600 words.")

        return sb.toString()
    }
}
