package com.pancreas.ai

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.pancreas.ai.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: GlucoseViewModel by viewModels()

    private val timeFmt    = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val updatedFmt = SimpleDateFormat("h:mm:ss a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupChart()
        observeViewModel()
        handleOAuthResult(intent)

        binding.fabRefresh.setOnClickListener { viewModel.refresh() }
        binding.btnGoToSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        viewModel.startAutoRefresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthResult(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopAutoRefresh()
    }

    private fun handleOAuthResult(intent: Intent?) {
        val success = intent?.getBooleanExtra("oauth_success", false) ?: return
        val message = intent.getStringExtra("oauth_message") ?: ""
        if (success) {
            Toast.makeText(this, "✓ Connected to Dexcom!", Toast.LENGTH_LONG).show()
            viewModel.refresh()
        } else if (message.isNotBlank()) {
            Toast.makeText(this, "⚠ $message", Toast.LENGTH_LONG).show()
        }
        // Clear the extras so rotation doesn't re-show the toast
        intent.removeExtra("oauth_success")
        intent.removeExtra("oauth_message")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_refresh  -> { viewModel.refresh(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ─── Chart ───────────────────────────────────────────────────────────────

    private fun setupChart() {
        binding.glucoseChart.apply {
            description.isEnabled = false
            setTouchEnabled(true); isDragEnabled = true; setScaleEnabled(true); setPinchZoom(true)
            setDrawGridBackground(false); setBackgroundColor(Color.TRANSPARENT); legend.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.parseColor("#B0BEC5")
                gridColor = Color.parseColor("#1A2A3A")
                axisLineColor = Color.parseColor("#1A2A3A")
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) =
                        timeFmt.format(Date(value.toLong()))
                }
                labelRotationAngle = -45f
                setLabelCount(6, true)
            }

            axisLeft.apply {
                textColor = Color.parseColor("#B0BEC5")
                gridColor = Color.parseColor("#1A2A3A")
                axisLineColor = Color.parseColor("#1A2A3A")
                axisMinimum = 40f; axisMaximum = 400f

                addLimitLine(LimitLine(70f, "Low").apply {
                    lineColor = Color.parseColor("#FF4444"); lineWidth = 1.5f
                    textColor = Color.parseColor("#FF4444"); textSize = 9f
                    enableDashedLine(10f, 6f, 0f)
                })
                addLimitLine(LimitLine(180f, "High").apply {
                    lineColor = Color.parseColor("#FF8800"); lineWidth = 1.5f
                    textColor = Color.parseColor("#FF8800"); textSize = 9f
                    enableDashedLine(10f, 6f, 0f)
                })
            }
            axisRight.isEnabled = false
            setNoDataText("Connect your Dexcom to see glucose data")
            setNoDataTextColor(Color.parseColor("#546E7A"))
        }
    }

    // ─── ViewModel ────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Loading       -> showLoading()
                is UiState.NoCredentials -> showNoCredentials()
                is UiState.NotConnected  -> showNotConnected()
                is UiState.Success       -> showData(state.readings)
                is UiState.Error         -> showError(state.message)
            }
        }
        viewModel.lastUpdated.observe(this) { ts ->
            if (ts > 0) binding.tvLastUpdated.text = "Updated ${updatedFmt.format(Date(ts))}"
        }
    }

    // ─── UI States ────────────────────────────────────────────────────────────

    private fun showLoading() {
        binding.progressBar.visibility    = View.VISIBLE
        binding.cardCurrent.visibility    = View.GONE
        binding.cardStatus.visibility     = View.GONE
        binding.noCredentialsGroup.visibility = View.GONE
        binding.errorCard.visibility      = View.GONE
    }

    private fun showNoCredentials() {
        binding.progressBar.visibility    = View.GONE
        binding.cardCurrent.visibility    = View.GONE
        binding.cardStatus.visibility     = View.GONE
        binding.noCredentialsGroup.visibility = View.VISIBLE
        binding.tvNoCredsMessage.text     = "Enter your Dexcom Developer Client ID and Secret in Settings to get started."
        binding.errorCard.visibility      = View.GONE
        binding.glucoseChart.clear()
    }

    private fun showNotConnected() {
        binding.progressBar.visibility    = View.GONE
        binding.cardCurrent.visibility    = View.GONE
        binding.cardStatus.visibility     = View.GONE
        binding.noCredentialsGroup.visibility = View.VISIBLE
        binding.tvNoCredsMessage.text     = "Tap Settings and press \"Connect with Dexcom\" to authorize the app."
        binding.errorCard.visibility      = View.GONE
        binding.glucoseChart.clear()
    }

    private fun showData(readings: List<EgvReading>) {
        binding.progressBar.visibility    = View.GONE
        binding.noCredentialsGroup.visibility = View.GONE
        binding.errorCard.visibility      = View.GONE

        if (readings.isEmpty()) {
            binding.cardCurrent.visibility = View.GONE
            binding.cardStatus.visibility  = View.GONE
            return
        }
        binding.cardCurrent.visibility = View.VISIBLE
        binding.cardStatus.visibility  = View.VISIBLE

        updateCurrentReading(readings.last())
        updateChart(readings)
        updateStats(readings)
    }

    private fun showError(message: String) {
        binding.progressBar.visibility    = View.GONE
        binding.noCredentialsGroup.visibility = View.GONE
        binding.errorCard.visibility      = View.VISIBLE
        binding.tvErrorMessage.text       = message
        binding.cardCurrent.visibility    = View.GONE
        binding.cardStatus.visibility     = View.GONE
    }

    // ─── Current Reading ──────────────────────────────────────────────────────

    private fun updateCurrentReading(reading: EgvReading) {
        val v = reading.glucoseValue()
        binding.tvCurrentGlucose.text = v.toString()
        binding.tvCurrentGlucose.setTextColor(reading.glucoseColor())
        binding.tvTrend.text = reading.trendArrow()
        binding.tvTrend.setTextColor(reading.glucoseColor())
        binding.tvUnit.text = "mg/dL"

        val label = when { v < 70 -> "LOW"; v <= 180 -> "IN RANGE"; else -> "HIGH" }
        binding.tvRangeLabel.text = label
        binding.tvRangeLabel.setTextColor(reading.glucoseColor())
        binding.tvReadingTime.text = "at ${timeFmt.format(Date(reading.epochMillis()))}"
    }

    // ─── Chart ───────────────────────────────────────────────────────────────

    private fun updateChart(readings: List<EgvReading>) {
        val entries = readings.map { Entry(it.epochMillis().toFloat(), it.glucoseValue().toFloat()) }
        val dataSet = LineDataSet(entries, "Glucose").apply {
            color = Color.parseColor("#00BCD4")
            setCircleColor(Color.parseColor("#00BCD4"))
            lineWidth = 2.5f; circleRadius = 3f
            setDrawCircleHole(false); setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER; cubicIntensity = 0.2f
            setDrawFilled(true); fillColor = Color.parseColor("#00BCD4"); fillAlpha = 30
            highLightColor = Color.WHITE; highlightLineWidth = 1f
            enableDashedHighlightLine(10f, 5f, 0f)
        }
        binding.glucoseChart.data = LineData(dataSet)
        binding.glucoseChart.notifyDataSetChanged()
        binding.glucoseChart.invalidate()
        binding.glucoseChart.animateX(500)
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    private fun updateStats(readings: List<EgvReading>) {
        val values   = readings.map { it.glucoseValue() }
        val avg      = values.average()
        val inRange  = values.count { it in 70..180 }
        val low      = values.count { it < 70 }
        val high     = values.count { it > 180 }
        val total    = values.size.toDouble()

        binding.tvAvgGlucose.text  = "%.0f".format(avg)
        binding.tvTimeInRange.text = "${(inRange / total * 100).toInt()}%"
        binding.tvTimeLow.text     = "${(low    / total * 100).toInt()}%"
        binding.tvTimeHigh.text    = "${(high   / total * 100).toInt()}%"
        binding.tvReadingCount.text = "${readings.size} readings"
    }
}
