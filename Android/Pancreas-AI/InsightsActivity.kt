package com.pancreas.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit

class InsightsActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var container: LinearLayout
    private val BG   = "#0D1B2A"
    private val SURF = "#132030"
    private val BORD = "#1E3448"
    private val CYAN = "#00BCD4"
    private val GREEN = "#4CAF50"
    private val PURP = "#CC44FF"
    private val ORG  = "#FF9800"
    private val RED  = "#FF4444"
    private val TEXT = "#E0E8F0"
    private val MUTE = "#6A8499"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Insights"

        scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(BG)) }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(container)
        setContentView(scroll)

        buildUi()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun buildUi() {
        lifecycleScope.launch {
            addHeader("Insights", "Analyzing your glucose, food and insulin data…")

            val result = withContext(Dispatchers.IO) {
                InsightsAnalyzer.analyze(this@InsightsActivity)
            }

            container.removeAllViews()
            addHeader("Insights", "${result.dataSpanDays} days • ${result.totalReadings} readings")

            if (result.totalReadings < 20) {
                addInfoCard("⏳ More Data Needed",
                    "Keep using the app to collect readings. Insights become meaningful with at least " +
                    "2 weeks of glucose data alongside logged meals and insulin doses.",
                    CYAN)
                addAiSection(result)
                return@launch
            }

            addTimeOfDaySection(result)
            if (result.postMealCurves.isNotEmpty())   addPostMealSection(result)
            if (result.postInsulinCurves.isNotEmpty()) addPostInsulinSection(result)
            addMetricsSection(result)
            if (result.warningsAndTips.isNotEmpty())  addTipsSection(result)
            addAiSection(result)
        }
    }

    // ── Section: Time of Day ──────────────────────────────────────────────────

    private fun addTimeOfDaySection(result: InsightResult) {
        addSectionHeader("Glucose by Time of Day")
        val card = makeCard()

        val slots = result.timeOfDayStats.filter { it.count > 0 }
        slots.forEach { slot ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            labelRow.addView(tv(slot.slotLabel, 13f, TEXT).lp(0, wrap, 1f))
            labelRow.addView(tv("%.0f mg/dL".format(slot.avgGlucose), 13f,
                glucoseColor(slot.avgGlucose.toInt()), bold = true))

            val barRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(2))
            }
            fun seg(pct: Int, color: String) = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(10), pct.toFloat().coerceAtLeast(0f))
                setBackgroundColor(Color.parseColor(color))
            }
            barRow.addView(seg(slot.pctLow, RED))
            barRow.addView(seg(slot.pctInRange, GREEN))
            barRow.addView(seg(slot.pctHigh, ORG))
            // fill to 100
            val remainder = (100 - slot.pctLow - slot.pctInRange - slot.pctHigh).coerceAtLeast(0)
            if (remainder > 0) barRow.addView(seg(remainder, BORD))

            val pctLabel = tv("  ${slot.pctInRange}% in range  •  ${slot.pctLow}% low  •  ${slot.pctHigh}% high",
                10f, MUTE)

            row.addView(labelRow)
            row.addView(barRow)
            row.addView(pctLabel)

            if (slot != slots.last()) row.addView(divider())
            card.addView(row)
        }
        container.addView(wrapCard(card))
    }

    // ── Section: Post-Meal Curves ─────────────────────────────────────────────

    private fun addPostMealSection(result: InsightResult) {
        addSectionHeader("Post-Meal Glucose Response")
        val card = makeCard()

        val curves = result.postMealCurves.take(8)
        val validAt60  = curves.filter { it.deltaAt60  > -500 }
        val validAt120 = curves.filter { it.deltaAt120 > -500 }

        if (validAt60.isNotEmpty()) {
            val avg60  = validAt60.map { it.deltaAt60 }.average()
            val avg120 = validAt120.map { it.deltaAt120 }.average()
            val avgPeak = curves.map { it.peak - it.baselineGlucose }.average()

            card.addView(StatRowView(this,
                listOf(
                    Triple("+%.0f".format(avgPeak),  "Avg peak rise",   ORG),
                    Triple("+%.0f".format(avg60),    "+60 min",         if (avg60 > 60) ORG else GREEN),
                    Triple("+%.0f".format(avg120),   "+120 min",        if (avg120 > 30) ORG else GREEN)
                )))
            card.addView(divider())
        }

        // Sparkline chart: average curve
        card.addView(PostMealChartView(this).also { it.curves = curves })
        card.addView(divider())

        // Table
        card.addView(tv("Recent meals", 11f, MUTE).apply { setPadding(0, dp(8), 0, dp(4)) })
        curves.forEach { c ->
            val name = c.foodEntry.name.ifBlank { c.foodEntry.mealType.label }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tv(name, 13f, TEXT).lp(0, wrap, 1f))
                addView(tv("${"%.0f".format(c.foodEntry.carbs)}g carbs", 12f, ORG))
            })
            val detail = "Base ${c.baselineGlucose}  →  peak ${c.peak} (+${c.peak - c.baselineGlucose}) at ${c.peakMinutes}min"
            row.addView(tv(detail, 11f, MUTE))
            if (c != curves.last()) row.addView(divider())
            card.addView(row)
        }

        container.addView(wrapCard(card))
    }

    // ── Section: Post-Insulin Curves ──────────────────────────────────────────

    private fun addPostInsulinSection(result: InsightResult) {
        addSectionHeader("Post-Insulin Glucose Response")
        val card = makeCard()

        val curves = result.postInsulinCurves.take(8)
        val validAt120 = curves.filter { it.dropAt120 > -500 }

        if (validAt120.isNotEmpty()) {
            val avgDrop120 = validAt120.map { it.dropAt120 }.average()
            val avgNadir   = curves.map { it.nadir }.average()
            val avgNadirMin = curves.map { it.nadirMinutes }.average()
            card.addView(StatRowView(this,
                listOf(
                    Triple("%.0f".format(avgDrop120), "Avg drop/u at 2h", CYAN),
                    Triple("%.0f".format(avgNadir),   "Avg nadir",        CYAN),
                    Triple("%.0f min".format(avgNadirMin), "Time to nadir", MUTE)
                )))
            card.addView(divider())
        }

        card.addView(PostInsulinChartView(this).also { it.curves = curves })
        card.addView(divider())

        card.addView(tv("Recent doses", 11f, MUTE).apply { setPadding(0, dp(8), 0, dp(4)) })
        curves.forEach { c ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tv("${"%.1f".format(c.insulinEntry.units)}u ${c.insulinEntry.type.label}", 13f, TEXT).lp(0, wrap, 1f))
                addView(tv("nadir ${c.nadir} at ${c.nadirMinutes}min", 12f, CYAN))
            })
            val detail = "Base ${c.baselineGlucose}  →  −${c.dropAt60} at 60min  •  −${c.dropAt120} at 120min"
            row.addView(tv(detail, 11f, MUTE))
            if (c != curves.last()) row.addView(divider())
            card.addView(row)
        }
        container.addView(wrapCard(card))
    }

    // ── Section: Metrics ──────────────────────────────────────────────────────

    private fun addMetricsSection(result: InsightResult) {
        addSectionHeader("Estimated Metrics")
        val card = makeCard()

        val hasIsf = result.estimatedIsf != null
        val hasIcr = result.estimatedIcr != null

        if (!hasIsf && !hasIcr) {
            card.addView(tv("Log more meals with matching insulin doses to calculate ISF and ICR.", 13f, MUTE))
        } else {
            if (hasIsf) {
                val isfColor = when {
                    result.estimatedIsf!! < 20 -> RED    // very sensitive / low ISF unusual
                    result.estimatedIsf < 100  -> GREEN
                    else                       -> ORG
                }
                card.addView(metricRow(
                    "ISF — Insulin Sensitivity Factor",
                    "%.0f mg/dL per unit".format(result.estimatedIsf),
                    isfColor,
                    "How much 1 unit of rapid insulin lowers your glucose. " +
                    "Typical range: 20–100 mg/dL/unit. Lower = more sensitive."
                ))
                if (hasIcr) card.addView(divider())
            }
            if (hasIcr) {
                card.addView(metricRow(
                    "ICR — Insulin-to-Carb Ratio",
                    "1 unit per %.0fg carbs".format(result.estimatedIcr),
                    CYAN,
                    "Estimated carbs covered by 1 unit of insulin. " +
                    "Typical range: 5–20g/unit depending on insulin sensitivity."
                ))
            }
        }
        card.addView(divider())
        card.addView(tv("⚠ These are estimates from your logged data. Always confirm with your diabetes care team before adjusting doses.",
            11f, MUTE))
        container.addView(wrapCard(card))
    }

    private fun metricRow(title: String, value: String, color: String, desc: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(LinearLayout(this@InsightsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tv(title, 13f, TEXT).lp(0, wrap, 1f))
                addView(tv(value, 14f, color, bold = true))
            })
            addView(tv(desc, 11f, MUTE))
        }
    }

    // ── Section: Tips ─────────────────────────────────────────────────────────

    private fun addTipsSection(result: InsightResult) {
        addSectionHeader("Pattern Observations")
        result.warningsAndTips.forEach { tip ->
            val card = makeCard()
            card.addView(tv(tip, 13f, TEXT))
            container.addView(wrapCard(card))
        }
    }

    // ── Section: AI Suggestions ───────────────────────────────────────────────

    private fun addAiSection(result: InsightResult) {
        addSectionHeader("AI Suggestions")
        val card = makeCard()

        val hasKey = CredentialsManager.hasClaudeApiKey(this)

        if (!hasKey) {
            card.addView(tv("Connect Claude AI for personalised insulin and meal timing suggestions based on your actual data.", 13f, TEXT))
            card.addView(spacer(dp(12)))
            card.addView(tv("To enable:", 12f, MUTE))
            card.addView(tv("1. Get a free API key at console.anthropic.com", 12f, MUTE))
            card.addView(tv("2. Paste it in Settings → Claude API Key", 12f, MUTE))
            card.addView(spacer(dp(12)))
            val btn = makeButton("Open Settings") {
                startActivity(android.content.Intent(this, SettingsActivity::class.java))
            }
            card.addView(btn)
            container.addView(wrapCard(card))
            return
        }

        val statusTv = tv("Generating AI suggestions…", 13f, MUTE)
        val resultTv = tv("", 14f, TEXT).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        val btn = makeButton("Refresh Suggestions") {}

        card.addView(statusTv)
        card.addView(resultTv)
        card.addView(spacer(dp(8)))
        card.addView(btn)
        card.addView(spacer(dp(8)))
        card.addView(tv("⚠ AI suggestions are educational only. Consult your care team before changing insulin doses.",
            11f, MUTE))
        container.addView(wrapCard(card))

        fun fetchAi() {
            statusTv.text = "Generating AI suggestions…"
            statusTv.setTextColor(Color.parseColor(MUTE))
            resultTv.text = ""

            lifecycleScope.launch {
                val response = withContext(Dispatchers.IO) {
                    callClaudeApi(result)
                }
                if (response.startsWith("ERROR:")) {
                    statusTv.text = "Could not get suggestions"
                    resultTv.text = response.removePrefix("ERROR:")
                    resultTv.setTextColor(Color.parseColor(RED))
                } else {
                    statusTv.text = "AI Suggestions (claude-haiku-4-5)"
                    resultTv.text = response
                    resultTv.setTextColor(Color.parseColor(TEXT))
                }
            }
        }

        btn.setOnClickListener { fetchAi() }
        fetchAi()
    }

    private suspend fun callClaudeApi(result: InsightResult): String {
        return try {
            val apiKey = CredentialsManager.getClaudeApiKey(this@InsightsActivity)
            val prompt = InsightsAnalyzer.buildAiPrompt(result, this@InsightsActivity)

            val body = JSONObject().apply {
                put("model", "claude-haiku-4-5-20251001")
                put("max_tokens", 1024)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }.toString()

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .build()

            val resp = client.newCall(request).execute()
            val raw  = resp.body?.string() ?: return "ERROR:Empty response from Claude API"

            if (!resp.isSuccessful) {
                val msg = try { JSONObject(raw).optString("error", raw) } catch (_: Exception) { raw }
                return "ERROR:API error ${resp.code}: $msg"
            }

            val json = JSONObject(raw)
            json.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
        } catch (e: Exception) {
            "ERROR:${e.message ?: "Unknown error"}"
        }
    }

    // ── Shared UI helpers ─────────────────────────────────────────────────────

    private fun addHeader(title: String, subtitle: String) {
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(20))
            addView(tv(title, 22f, CYAN, bold = true))
            addView(tv(subtitle, 13f, MUTE))
        })
    }

    private fun addSectionHeader(text: String) {
        container.addView(tv(text, 12f, MUTE, bold = true).apply {
            letterSpacing = 0.12f
            setPadding(dp(4), dp(16), 0, dp(8))
        })
    }

    private fun addInfoCard(title: String, body: String, color: String) {
        val card = makeCard()
        card.addView(tv(title, 15f, color, bold = true))
        card.addView(spacer(dp(8)))
        card.addView(tv(body, 13f, TEXT))
        container.addView(wrapCard(card))
    }

    private fun makeCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun wrapCard(inner: LinearLayout): View {
        return androidx.cardview.widget.CardView(this).apply {
            setCardBackgroundColor(Color.parseColor(SURF))
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
            val pad = LinearLayout(this@InsightsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(inner)
            }
            addView(pad)
        }
    }

    private fun makeButton(label: String, onClick: () -> Unit) =
        com.google.android.material.button.MaterialButton(this).apply {
            text = label
            setTextColor(Color.BLACK)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(CYAN))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(wrap, wrap)
        }

    private fun tv(text: String, size: Float, color: String, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(match, dp(1)).apply { setMargins(0, dp(6), 0, dp(6)) }
        setBackgroundColor(Color.parseColor(BORD))
    }

    private fun spacer(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(match, height)
    }

    private fun glucoseColor(mg: Int) = when {
        mg < 70    -> RED
        mg <= 180  -> GREEN
        else       -> ORG
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap  = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun TextView.lp(w: Int, h: Int, weight: Float = 0f) = apply {
        layoutParams = LinearLayout.LayoutParams(w, h, weight)
    }
}

// ── Custom Views ──────────────────────────────────────────────────────────────

/** Horizontal stat summary strip */
class StatRowView(ctx: Context, stats: List<Triple<String, String, String>>) : LinearLayout(ctx) {
    init {
        orientation = HORIZONTAL
        stats.forEach { (value, label, color) ->
            addView(LinearLayout(ctx).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                val center = android.view.Gravity.CENTER_HORIZONTAL
                addView(TextView(ctx).apply {
                    text = value; textSize = 20f; gravity = center
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(color))
                })
                addView(TextView(ctx).apply {
                    this.text = label; textSize = 10f; gravity = center
                    setTextColor(Color.parseColor("#6A8499"))
                })
            })
        }
        val density = ctx.resources.displayMetrics.density
        setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
    }
}

/** Post-meal average curve chart */
class PostMealChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {
    var curves: List<PostMealCurve> = emptyList()

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    override fun onMeasure(ws: Int, hs: Int) =
        setMeasuredDimension(MeasureSpec.getSize(ws), dp(120f).toInt())

    override fun onDraw(c: Canvas) {
        if (curves.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val pad = dp(8f)

        // Reference lines
        val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E3448"); strokeWidth = 1f; style = Paint.Style.STROKE
        }
        // 0 line (baseline)
        val zeroY = h - pad
        c.drawLine(pad, zeroY, w - pad, zeroY, refPaint)

        // Time points: 0, 30, 60, 90, 120, 150, 180 min
        val timePoints = listOf(0, 30, 60, 90, 120, 150, 180)
        val maxDelta = curves.flatMap { listOf(it.peak - it.baselineGlucose) }.maxOrNull()?.toFloat() ?: 100f
        val scale = (h - pad * 2) / maxDelta.coerceAtLeast(60f)

        fun xAt(min: Int) = pad + (min.toFloat() / 180f) * (w - pad * 2)
        fun yAt(delta: Int) = zeroY - delta * scale

        // Individual curve lines (faint)
        val faintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A6A80"); strokeWidth = dp(1f)
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        curves.forEach { curve ->
            val pts = mutableListOf(Pair(0, 0))
            if (curve.deltaAt60 > -500)  pts.add(Pair(60, curve.deltaAt60))
            if (curve.deltaAt120 > -500) pts.add(Pair(120, curve.deltaAt120))
            if (curve.deltaAt180 > -500) pts.add(Pair(180, curve.deltaAt180))
            val path = Path()
            pts.forEachIndexed { i, (min, delta) ->
                if (i == 0) path.moveTo(xAt(min), yAt(delta))
                else path.lineTo(xAt(min), yAt(delta))
            }
            c.drawPath(path, faintPaint)
        }

        // Average line (bold)
        val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9800"); strokeWidth = dp(2.5f)
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val validAt60  = curves.filter { it.deltaAt60 > -500 }.map { it.deltaAt60 }
        val validAt120 = curves.filter { it.deltaAt120 > -500 }.map { it.deltaAt120 }
        val validAt180 = curves.filter { it.deltaAt180 > -500 }.map { it.deltaAt180 }
        val avgPath = Path()
        avgPath.moveTo(xAt(0), yAt(0))
        if (validAt60.isNotEmpty())  avgPath.lineTo(xAt(60),  yAt(validAt60.average().toInt()))
        if (validAt120.isNotEmpty()) avgPath.lineTo(xAt(120), yAt(validAt120.average().toInt()))
        if (validAt180.isNotEmpty()) avgPath.lineTo(xAt(180), yAt(validAt180.average().toInt()))
        c.drawPath(avgPath, avgPaint)

        // X-axis labels
        val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6A8499"); textSize = dp(9f); textAlign = Paint.Align.CENTER
        }
        listOf(0 to "meal", 60 to "60m", 120 to "120m", 180 to "180m").forEach { (min, lbl) ->
            c.drawText(lbl, xAt(min), h, lblPaint)
        }

        // Target zone (in-range shading above baseline — show +0 to +40 as acceptable)
        val zonePaint = Paint().apply {
            color = Color.parseColor("#0A4020"); style = Paint.Style.FILL
        }
        c.drawRect(RectF(pad, yAt(40), w - pad, zeroY), zonePaint)

        // Legend
        val legPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9800"); textSize = dp(9f)
        }
        c.drawText("— avg rise", dp(10f), dp(14f), legPaint)
    }
}

/** Post-insulin drop chart */
class PostInsulinChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {
    var curves: List<PostInsulinCurve> = emptyList()

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    override fun onMeasure(ws: Int, hs: Int) =
        setMeasuredDimension(MeasureSpec.getSize(ws), dp(120f).toInt())

    override fun onDraw(c: Canvas) {
        if (curves.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val pad = dp(8f)
        val topY = pad

        val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E3448"); strokeWidth = 1f; style = Paint.Style.STROKE
        }
        c.drawLine(pad, topY, w - pad, topY, refPaint)

        val maxDrop = curves.flatMap { listOf(it.baselineGlucose - it.nadir) }
            .maxOrNull()?.toFloat() ?: 80f
        val scale = (h - pad * 2) / maxDrop.coerceAtLeast(40f)

        fun xAt(min: Int) = pad + (min.toFloat() / 240f) * (w - pad * 2)
        fun yAt(drop: Int) = topY + drop * scale

        val faintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A3A5A"); strokeWidth = dp(1f)
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        curves.forEach { curve ->
            val pts = mutableListOf(Pair(0, 0))
            if (curve.dropAt60 > -500)  pts.add(Pair(60, curve.dropAt60))
            if (curve.dropAt120 > -500) pts.add(Pair(120, curve.dropAt120))
            if (curve.dropAt180 > -500) pts.add(Pair(180, curve.dropAt180))
            val path = Path()
            pts.forEachIndexed { i, (min, drop) ->
                if (i == 0) path.moveTo(xAt(min), yAt(drop))
                else path.lineTo(xAt(min), yAt(drop))
            }
            c.drawPath(path, faintPaint)
        }

        val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00BCD4"); strokeWidth = dp(2.5f)
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val valid60  = curves.filter { it.dropAt60  > -500 }.map { it.dropAt60 }
        val valid120 = curves.filter { it.dropAt120 > -500 }.map { it.dropAt120 }
        val valid180 = curves.filter { it.dropAt180 > -500 }.map { it.dropAt180 }
        val avgPath = Path()
        avgPath.moveTo(xAt(0), yAt(0))
        if (valid60.isNotEmpty())  avgPath.lineTo(xAt(60),  yAt(valid60.average().toInt()))
        if (valid120.isNotEmpty()) avgPath.lineTo(xAt(120), yAt(valid120.average().toInt()))
        if (valid180.isNotEmpty()) avgPath.lineTo(xAt(180), yAt(valid180.average().toInt()))
        c.drawPath(avgPath, avgPaint)

        val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6A8499"); textSize = dp(9f); textAlign = Paint.Align.CENTER
        }
        listOf(0 to "dose", 60 to "60m", 120 to "120m", 180 to "180m").forEach { (min, lbl) ->
            c.drawText(lbl, xAt(min), h, lblPaint)
        }
    }
}
