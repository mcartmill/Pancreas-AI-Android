package com.pancreas.ai

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.pancreas.ai.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: GlucoseViewModel by viewModels()

    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val updatedFormatter = SimpleDateFormat("h:mm:ss a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupChart()
        observeViewModel()

        binding.fabRefresh.setOnClickListener { viewModel.refresh() }
        binding.btnGoToSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        viewModel.startAutoRefresh()
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from settings
        viewModel.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopAutoRefresh()
    }

    // ─── Toolbar ─────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_refresh -> {
                viewModel.refresh()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ─── Chart Setup ─────────────────────────────────────────────────────────

    private fun setupChart() {
        binding.glucoseChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            setBackgroundColor(Color.TRANSPARENT)
            legend.isEnabled = false

            // X-Axis
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.parseColor("#B0BEC5")
                gridColor = Color.parseColor("#1A2A3A")
                axisLineColor = Color.parseColor("#1A2A3A")
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return timeFormatter.format(Date(value.toLong()))
                    }
                }
                labelRotationAngle = -45f
                setLabelCount(6, true)
            }

            // Y-Axis Left
            axisLeft.apply {
                textColor = Color.parseColor("#B0BEC5")
                gridColor = Color.parseColor("#1A2A3A")
                axisLineColor = Color.parseColor("#1A2A3A")
                axisMinimum = 40f
                axisMaximum = 400f
                setDrawZeroLine(false)

                // Target range lines
                val lowLine = LimitLine(70f, "Low").apply {
                    lineColor = Color.parseColor("#FF4444")
                    lineWidth = 1.5f
                    textColor = Color.parseColor("#FF4444")
                    textSize = 9f
                    enableDashedLine(10f, 6f, 0f)
                }
                val highLine = LimitLine(180f, "High").apply {
                    lineColor = Color.parseColor("#FF8800")
                    lineWidth = 1.5f
                    textColor = Color.parseColor("#FF8800")
                    textSize = 9f
                    enableDashedLine(10f, 6f, 0f)
                }
                addLimitLine(lowLine)
                addLimitLine(highLine)
            }

            axisRight.isEnabled = false

            // No data message
            setNoDataText("Pull data to see your glucose history")
            setNoDataTextColor(Color.parseColor("#546E7A"))
        }
    }

    // ─── ViewModel Observer ───────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Loading -> showLoading()
                is UiState.NoCredentials -> showNoCredentials()
                is UiState.Success -> showData(state.readings)
                is UiState.Error -> showError(state.message)
            }
        }

        viewModel.lastUpdated.observe(this) { ts ->
            if (ts > 0) {
                binding.tvLastUpdated.text = "Updated ${updatedFormatter.format(Date(ts))}"
            }
        }
    }

    // ─── UI States ───────────────────────────────────────────────────────────

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.cardCurrent.visibility = View.GONE
        binding.cardStatus.visibility = View.GONE
        binding.noCredentialsGroup.visibility = View.GONE
        binding.errorCard.visibility = View.GONE
    }

    private fun showNoCredentials() {
        binding.progressBar.visibility = View.GONE
        binding.cardCurrent.visibility = View.GONE
        binding.cardStatus.visibility = View.GONE
        binding.noCredentialsGroup.visibility = View.VISIBLE
        binding.errorCard.visibility = View.GONE
        binding.glucoseChart.clear()
    }

    private fun showData(readings: List<GlucoseReading>) {
        binding.progressBar.visibility = View.GONE
        binding.noCredentialsGroup.visibility = View.GONE
        binding.errorCard.visibility = View.GONE

        if (readings.isEmpty()) {
            binding.cardCurrent.visibility = View.GONE
            binding.cardStatus.visibility = View.GONE
            return
        }

        binding.cardCurrent.visibility = View.VISIBLE
        binding.cardStatus.visibility = View.VISIBLE

        val latest = readings.last()
        updateCurrentReading(latest)
        updateChart(readings)
        updateStats(readings)
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.noCredentialsGroup.visibility = View.GONE
        binding.errorCard.visibility = View.VISIBLE
        binding.tvErrorMessage.text = message
        binding.cardCurrent.visibility = View.GONE
        binding.cardStatus.visibility = View.GONE
    }

    // ─── Current Reading Card ─────────────────────────────────────────────────

    private fun updateCurrentReading(reading: GlucoseReading) {
        binding.tvCurrentGlucose.text = reading.value.toString()
        binding.tvCurrentGlucose.setTextColor(reading.glucoseColor())
        binding.tvTrend.text = reading.trendArrow()
        binding.tvTrend.setTextColor(reading.glucoseColor())
        binding.tvUnit.text = "mg/dL"

        val label = when {
            reading.value < 70  -> "LOW"
            reading.value <= 180 -> "IN RANGE"
            else                 -> "HIGH"
        }
        binding.tvRangeLabel.text = label
        binding.tvRangeLabel.setTextColor(reading.glucoseColor())

        val readingTime = timeFormatter.format(Date(reading.epochMillis()))
        binding.tvReadingTime.text = "at $readingTime"
    }

    // ─── Chart Population ─────────────────────────────────────────────────────

    private fun updateChart(readings: List<GlucoseReading>) {
        val entries = readings.map { r ->
            Entry(r.epochMillis().toFloat(), r.value.toFloat())
        }

        val dataSet = LineDataSet(entries, "Glucose").apply {
            color = Color.parseColor("#00BCD4")
            setCircleColor(Color.parseColor("#00BCD4"))
            lineWidth = 2.5f
            circleRadius = 3f
            setDrawCircleHole(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.2f

            // Gradient fill
            setDrawFilled(true)
            fillColor = Color.parseColor("#00BCD4")
            fillAlpha = 30

            // Highlight
            highLightColor = Color.parseColor("#FFFFFF")
            highlightLineWidth = 1f
            enableDashedHighlightLine(10f, 5f, 0f)
        }

        binding.glucoseChart.data = LineData(dataSet)
        binding.glucoseChart.notifyDataSetChanged()
        binding.glucoseChart.invalidate()
        binding.glucoseChart.animateX(500)
    }

    // ─── Stats Card ───────────────────────────────────────────────────────────

    private fun updateStats(readings: List<GlucoseReading>) {
        if (readings.isEmpty()) return

        val values = readings.map { it.value }
        val avg = values.average()
        val inRange = values.count { it in 70..180 }
        val inRangePct = (inRange.toDouble() / values.size * 100).toInt()
        val low = values.count { it < 70 }
        val lowPct = (low.toDouble() / values.size * 100).toInt()
        val high = values.count { it > 180 }
        val highPct = (high.toDouble() / values.size * 100).toInt()

        binding.tvAvgGlucose.text = "%.0f".format(avg)
        binding.tvTimeInRange.text = "$inRangePct%"
        binding.tvTimeLow.text = "$lowPct%"
        binding.tvTimeHigh.text = "$highPct%"
        binding.tvReadingCount.text = "${readings.size} readings"
    }
}
