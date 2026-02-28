package com.pancreas.ai

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class ReportPeriod(val label: String, val days: Int) {
    DAY("Last 24 Hours", 1),
    WEEK("Last 7 Days", 7),
    MONTH("Last 30 Days", 30),
    THREE_MONTHS("Last 3 Months", 90),
    SIX_MONTHS("Last 6 Months", 180),
    YEAR("Last Year", 365)
}

object ReportExporter {

    private val dateFmt   = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
    private val shortDate = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val timeFmt   = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val fileFmt   = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun export(ctx: Context, period: ReportPeriod): Intent {
        val toMs   = System.currentTimeMillis()
        val fromMs = toMs - period.days * 86_400_000L

        // Load data for window
        val glucose  = loadGlucoseForPeriod(ctx, fromMs, toMs)
        val insulin  = InsulinManager.forWindow(ctx, fromMs, toMs)
        val food     = FoodManager.forWindow(ctx, fromMs, toMs)

        val html = buildHtml(period, fromMs, toMs, glucose, insulin, food)

        // Write to app-private files dir
        val dir = File(ctx.filesDir, "reports").also { it.mkdirs() }
        val file = File(dir, "PancreasAI_Report_${fileFmt.format(Date())}.html")
        file.writeText(html)

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Pancreas AI Report — ${period.label}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ── Glucose loading ───────────────────────────────────────────────────────

    /**
     * Glucose readings are not stored locally — they are fetched live from Dexcom.
     * We store only the most recent fetch in the ViewModel (not accessible from here).
     * Instead we build a "glucose log" on-the-fly from the file-based cache we write
     * after each successful fetch, OR we simply note in the report that glucose data
     * covers the chart window and invite the user to view it in the app.
     *
     * For a proper persisted glucose history we maintain a rolling log file.
     */
    private fun loadGlucoseForPeriod(ctx: Context, fromMs: Long, toMs: Long): List<StoredReading> {
        return GlucoseLog.load(ctx).filter { it.epochMs in fromMs..toMs }
    }

    // ── HTML generation ───────────────────────────────────────────────────────

    private fun buildHtml(
        period: ReportPeriod,
        fromMs: Long, toMs: Long,
        glucose: List<StoredReading>,
        insulin: List<InsulinEntry>,
        food: List<FoodEntry>
    ): String {
        val rangeStr = "${shortDate.format(Date(fromMs))} – ${shortDate.format(Date(toMs))}"
        val glucoseStats = computeGlucoseStats(glucose)

        return buildString {
            append(htmlHead(period.label, rangeStr))
            append(summarySection(period, rangeStr, glucose, insulin, food, glucoseStats))
            if (glucose.isNotEmpty()) {
                append(glucoseChartSection(glucose))
                append(glucoseTableSection(glucose))
            } else {
                append("""<div class="card"><p class="muted">No glucose data stored for this period.
                    Glucose history is captured automatically each time you refresh the app.</p></div>""")
            }
            if (insulin.isNotEmpty()) append(insulinSection(insulin))
            if (food.isNotEmpty()) append(foodSection(food))
            append(htmlFoot())
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    data class GlucoseStats(
        val avg: Double, val min: Int, val max: Int,
        val pctInRange: Int, val pctLow: Int, val pctHigh: Int,
        val estimatedA1c: Double
    )

    private fun computeGlucoseStats(readings: List<StoredReading>): GlucoseStats? {
        if (readings.isEmpty()) return null
        val values = readings.map { it.mg }
        val avg    = values.average()
        val total  = values.size.toDouble()
        return GlucoseStats(
            avg          = avg,
            min          = values.min(),
            max          = values.max(),
            pctInRange   = (values.count { it in 70..180 } / total * 100).toInt(),
            pctLow       = (values.count { it < 70 }       / total * 100).toInt(),
            pctHigh      = (values.count { it > 180 }      / total * 100).toInt(),
            // eAG to A1c: A1c = (eAG + 46.7) / 28.7
            estimatedA1c = (avg + 46.7) / 28.7
        )
    }

    // ── HTML sections ─────────────────────────────────────────────────────────

    private fun htmlHead(title: String, range: String) = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Pancreas AI — $title</title>
<style>
  :root {
    --bg: #0D1B2A; --surface: #132030; --border: #1E3448;
    --cyan: #00BCD4; --green: #4CAF50; --purple: #CC44FF;
    --orange: #FF9800; --red: #FF4444; --text: #E0E8F0; --muted: #6A8499;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { background: var(--bg); color: var(--text); font-family: 'Segoe UI', system-ui, sans-serif;
         font-size: 14px; line-height: 1.6; }
  .container { max-width: 900px; margin: 0 auto; padding: 24px 16px; }
  header { text-align: center; margin-bottom: 32px; padding-bottom: 20px;
           border-bottom: 1px solid var(--border); }
  header h1 { font-size: 26px; color: var(--cyan); margin-bottom: 4px; }
  header p  { color: var(--muted); font-size: 13px; }
  .card { background: var(--surface); border-radius: 12px; padding: 20px;
          margin-bottom: 20px; border: 1px solid var(--border); }
  .card h2 { font-size: 12px; text-transform: uppercase; letter-spacing: 0.12em;
             color: var(--muted); margin-bottom: 16px; }
  .stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 12px; }
  .stat { background: var(--bg); border-radius: 8px; padding: 12px 14px; }
  .stat .val { font-size: 24px; font-weight: 700; }
  .stat .lbl { font-size: 11px; color: var(--muted); margin-top: 2px; }
  .cyan   { color: var(--cyan); }
  .green  { color: var(--green); }
  .purple { color: var(--purple); }
  .orange { color: var(--orange); }
  .red    { color: var(--red); }
  .muted  { color: var(--muted); font-style: italic; text-align: center; padding: 12px; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th { text-align: left; padding: 8px 10px; color: var(--muted); font-weight: 600;
       font-size: 11px; text-transform: uppercase; letter-spacing: 0.08em;
       border-bottom: 1px solid var(--border); }
  td { padding: 8px 10px; border-bottom: 1px solid var(--border); }
  tr:last-child td { border-bottom: none; }
  tr:hover td { background: rgba(0,188,212,0.04); }
  .badge { display: inline-block; padding: 2px 8px; border-radius: 10px;
           font-size: 11px; font-weight: 600; }
  .badge-low    { background: rgba(255,68,68,0.15);  color: var(--red); }
  .badge-range  { background: rgba(76,175,80,0.15);  color: var(--green); }
  .badge-high   { background: rgba(255,152,0,0.15);  color: var(--orange); }
  .tir-bar { height: 20px; border-radius: 10px; overflow: hidden; display: flex; margin: 8px 0; }
  .tir-seg { height: 100%; }
  svg.sparkline { width: 100%%; height: 120px; display: block; }
  @media print {
    body { background: #fff; color: #111; }
    .card { border: 1px solid #ccc; background: #f9f9f9; break-inside: avoid; }
    header h1 { color: #007acc; }
    .stat { background: #fff; border: 1px solid #ddd; }
    .stat .val { color: #007acc !important; }
    th { color: #555; }
  }
  @media (max-width: 600px) {
    .stat-grid { grid-template-columns: repeat(2, 1fr); }
  }
</style>
</head>
<body>
<div class="container">
<header>
  <h1>Pancreas AI</h1>
  <p>$title &nbsp;|&nbsp; $range</p>
  <p style="margin-top:6px;font-size:12px;">Generated ${dateFmt.format(Date())}</p>
</header>
"""

    private fun summarySection(
        period: ReportPeriod, range: String,
        glucose: List<StoredReading>, insulin: List<InsulinEntry>, food: List<FoodEntry>,
        stats: GlucoseStats?
    ) = buildString {
        append("""<div class="card"><h2>Summary</h2><div class="stat-grid">""")

        if (stats != null) {
            append(stat("%.0f".format(stats.avg), "Avg Glucose (mg/dL)", "cyan"))
            append(stat("%.1f%%".format(stats.estimatedA1c), "Est. A1c", "cyan"))
            append(stat("${stats.pctInRange}%", "Time in Range", "green"))
            append(stat("${stats.pctLow}%", "Time Low (<70)", "red"))
            append(stat("${stats.pctHigh}%", "Time High (>180)", "orange"))
            append(stat("${stats.min}–${stats.max}", "Glucose Range", "muted"))
            append(stat("${glucose.size}", "Readings", "muted"))
        } else {
            append(stat("—", "Avg Glucose", "muted"))
            append(stat("—", "Est. A1c", "muted"))
        }

        val totalUnits = insulin.sumOf { it.units }
        val totalCarbs = food.sumOf { it.carbs }
        val totalCals  = food.sumOf { it.calories }
        append(stat("${insulin.size}", "Insulin Doses", "purple"))
        append(stat("%.0f u".format(totalUnits), "Total Insulin", "purple"))
        append(stat("${food.size}", "Meals Logged", "green"))
        append(stat("%.0f g".format(totalCarbs), "Total Carbs", "orange"))
        if (totalCals > 0) append(stat("$totalCals", "Total Calories", "orange"))

        append("</div>")

        // Time-in-range bar
        if (stats != null) {
            val lo = stats.pctLow; val hi = stats.pctHigh; val ir = stats.pctInRange
            append("""
<div style="margin-top:20px;">
  <div style="display:flex;justify-content:space-between;font-size:12px;color:var(--muted);margin-bottom:6px;">
    <span>Time in Range</span>
    <span style="color:var(--green);font-weight:700">$ir%</span>
  </div>
  <div class="tir-bar">
    <div class="tir-seg" style="width:${lo}%;background:var(--red);" title="Low: $lo%"></div>
    <div class="tir-seg" style="width:${ir}%;background:var(--green);" title="In Range: $ir%"></div>
    <div class="tir-seg" style="width:${hi}%;background:var(--orange);" title="High: $hi%"></div>
  </div>
  <div style="display:flex;gap:16px;font-size:11px;color:var(--muted);margin-top:4px;">
    <span><span style="color:var(--red)">■</span> Low $lo%</span>
    <span><span style="color:var(--green)">■</span> In Range $ir%</span>
    <span><span style="color:var(--orange)">■</span> High $hi%</span>
  </div>
</div>""")
        }
        append("</div>")
    }

    private fun stat(value: String, label: String, color: String) =
        """<div class="stat"><div class="val $color">$value</div><div class="lbl">$label</div></div>"""

    private fun glucoseChartSection(readings: List<StoredReading>): String {
        if (readings.isEmpty()) return ""
        val w = 860; val h = 120
        val minG = 40; val maxG = 400
        val minT = readings.minOf { it.epochMs }
        val maxT = readings.maxOf { it.epochMs }
        val rangeT = (maxT - minT).coerceAtLeast(1)

        fun px(t: Long)  = ((t - minT).toDouble() / rangeT * w).toInt()
        fun py(mg: Int)  = (h - ((mg - minG).toDouble() / (maxG - minG) * h)).toInt().coerceIn(0, h)

        val points = readings.joinToString(" ") { "${px(it.epochMs)},${py(it.mg)}" }
        val areaClose = " ${px(readings.last().epochMs)},$h 0,$h"

        return """
<div class="card">
  <h2>Glucose Trend</h2>
  <svg class="sparkline" viewBox="0 0 $w $h" preserveAspectRatio="none">
    <!-- Low / High bands -->
    <rect x="0" y="${py(180)}" width="$w" height="${py(70)-py(180)}" fill="rgba(76,175,80,0.08)"/>
    <!-- Low line -->
    <line x1="0" y1="${py(70)}"  x2="$w" y2="${py(70)}"  stroke="#FF4444" stroke-width="1" stroke-dasharray="4 4" opacity="0.5"/>
    <!-- High line -->
    <line x1="0" y1="${py(180)}" x2="$w" y2="${py(180)}" stroke="#FF9800" stroke-width="1" stroke-dasharray="4 4" opacity="0.5"/>
    <!-- Area fill -->
    <polygon points="$points $areaClose" fill="rgba(0,188,212,0.12)"/>
    <!-- Line -->
    <polyline points="$points" fill="none" stroke="#00BCD4" stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round"/>
  </svg>
  <div style="display:flex;justify-content:space-between;font-size:11px;color:var(--muted);margin-top:4px;">
    <span>${dateFmt.format(Date(minT))}</span>
    <span>${dateFmt.format(Date(maxT))}</span>
  </div>
</div>"""
    }

    private fun glucoseTableSection(readings: List<StoredReading>): String {
        // For large datasets, show summary by day instead of every reading
        val showByDay = readings.size > 200
        return if (showByDay) glucoseByDayTable(readings) else glucoseDetailTable(readings)
    }

    private fun glucoseDetailTable(readings: List<StoredReading>) = buildString {
        append("""<div class="card"><h2>Glucose Readings (${readings.size})</h2>
<table><tr><th>Time</th><th>Glucose</th><th>Status</th><th>Trend</th></tr>""")
        readings.sortedByDescending { it.epochMs }.take(500).forEach { r ->
            val badge = when { r.mg < 70 -> "badge-low" to "Low"
                               r.mg <= 180 -> "badge-range" to "In Range"
                               else -> "badge-high" to "High" }
            append("""<tr><td>${dateFmt.format(Date(r.epochMs))}</td>
<td><strong>${r.mg}</strong> mg/dL</td>
<td><span class="badge ${badge.first}">${badge.second}</span></td>
<td>${r.trend}</td></tr>""")
        }
        if (readings.size > 500) append("""<tr><td colspan="4" class="muted">... and ${readings.size - 500} more readings</td></tr>""")
        append("</table></div>")
    }

    private fun glucoseByDayTable(readings: List<StoredReading>) = buildString {
        val dayFmt = SimpleDateFormat("MMM d, yyyy (EEE)", Locale.getDefault())
        val byDay  = readings.groupBy { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.epochMs)) }
            .entries.sortedByDescending { it.key }

        append("""<div class="card"><h2>Daily Glucose Summary</h2>
<table><tr><th>Date</th><th>Avg</th><th>Min</th><th>Max</th>
<th>In Range</th><th>Low</th><th>High</th><th>Readings</th></tr>""")

        byDay.forEach { (_, rds) ->
            val mg    = rds.map { it.mg }
            val avg   = mg.average()
            val total = mg.size.toDouble()
            val ir    = (mg.count { it in 70..180 } / total * 100).toInt()
            val lo    = (mg.count { it < 70 }        / total * 100).toInt()
            val hi    = (mg.count { it > 180 }       / total * 100).toInt()
            append("""<tr>
<td>${dayFmt.format(Date(rds.first().epochMs))}</td>
<td><strong>${"%.0f".format(avg)}</strong></td>
<td class="red">${mg.min()}</td>
<td class="orange">${mg.max()}</td>
<td class="green">$ir%</td>
<td class="red">$lo%</td>
<td class="orange">$hi%</td>
<td class="muted">${rds.size}</td></tr>""")
        }
        append("</table></div>")
    }

    private fun insulinSection(entries: List<InsulinEntry>) = buildString {
        val sorted = entries.sortedByDescending { it.timestampMs }
        val total  = entries.sumOf { it.units }
        val rapid  = entries.filter { it.type == InsulinType.RAPID }.sumOf { it.units }
        val long2  = entries.filter { it.type == InsulinType.LONG  }.sumOf { it.units }

        append("""<div class="card">
<h2>Insulin Log (${entries.size} doses &nbsp;|&nbsp; Total: ${"%.1f".format(total)} u)</h2>""")

        if (entries.size > 1) {
            append("""<div style="display:flex;gap:20px;margin-bottom:16px;font-size:13px;">
  <span><span class="purple">■</span> Rapid: ${"%.1f".format(rapid)} u</span>
  <span><span class="cyan">■</span> Long-acting: ${"%.1f".format(long2)} u</span>
</div>""")
        }

        append("""<table><tr><th>Time</th><th>Units</th><th>Type</th><th>Note</th></tr>""")
        sorted.forEach { d ->
            val color = when (d.type) {
                InsulinType.RAPID -> "purple"; InsulinType.LONG -> "cyan"; else -> "muted" }
            append("""<tr><td>${dateFmt.format(Date(d.timestampMs))}</td>
<td class="$color"><strong>${"%.1f".format(d.units)} u</strong></td>
<td>${d.type.label}</td>
<td class="muted">${d.note.ifBlank { "—" }}</td></tr>""")
        }
        append("</table></div>")
    }

    private fun foodSection(entries: List<FoodEntry>) = buildString {
        val sorted     = entries.sortedByDescending { it.timestampMs }
        val totalCarbs = entries.sumOf { it.carbs }
        val totalCals  = entries.sumOf { it.calories }
        val calStr     = if (totalCals > 0) " &nbsp;|&nbsp; $totalCals cal" else ""

        append("""<div class="card">
<h2>Food Log (${entries.size} entries &nbsp;|&nbsp; ${"%.0f".format(totalCarbs)} g carbs$calStr)</h2>
<table><tr><th>Time</th><th>Food</th><th>Carbs</th><th>Calories</th><th>Type</th><th>Note</th></tr>""")

        sorted.forEach { m ->
            val calCell = if (m.calories > 0) "${m.calories}" else "—"
            append("""<tr><td>${dateFmt.format(Date(m.timestampMs))}</td>
<td><strong>${m.name.ifBlank { m.mealType.label }}</strong></td>
<td class="orange"><strong>${"%.0f".format(m.carbs)} g</strong></td>
<td class="muted">$calCell</td>
<td>${m.mealType.label}</td>
<td class="muted">${m.note.ifBlank { "—" }}</td></tr>""")
        }
        append("</table></div>")
    }

    private fun htmlFoot() = """
<footer style="text-align:center;padding:24px 0 8px;color:var(--muted);font-size:12px;">
  Pancreas AI &nbsp;•&nbsp; For personal tracking only &nbsp;•&nbsp; Not a medical device
</footer>
</div></body></html>"""
}

// ── Persistent glucose log ─────────────────────────────────────────────────────
// Appends readings to a local JSON log so reports can cover historical periods.

data class StoredReading(val epochMs: Long, val mg: Int, val trend: String)

object GlucoseLog {
    private const val FILE = "glucose_log.json"
    private val gson = com.google.gson.Gson()

    private data class Entry(val t: Long, val mg: Int, val tr: String)

    fun load(ctx: Context): List<StoredReading> {
        return try {
            val text = SecureFileStore.read(ctx, FILE) ?: return emptyList()
            val type = object : com.google.gson.reflect.TypeToken<List<Entry>>() {}.type
            val raw: List<Entry> = gson.fromJson(text, type) ?: emptyList()
            raw.map { StoredReading(it.t, it.mg, it.tr) }
        } catch (_: Exception) { emptyList() }
    }

    fun append(ctx: Context, readings: List<EgvReading>) {
        if (readings.isEmpty()) return
        try {
            val existing = load(ctx).associateBy { it.epochMs }.toMutableMap()
            readings.forEach { r ->
                if (r.glucoseValue() > 0 && r.epochMillis() > 0)
                    existing[r.epochMillis()] = StoredReading(r.epochMillis(), r.glucoseValue(), r.trendArrow())
            }
            // Keep only last 13 months
            val cutoff = System.currentTimeMillis() - 400L * 86_400_000L
            val trimmed = existing.values.filter { it.epochMs > cutoff }.sortedBy { it.epochMs }
            SecureFileStore.write(ctx, FILE, gson.toJson(trimmed.map { Entry(it.epochMs, it.mg, it.trend) }))
        } catch (_: Exception) {}
    }
}
