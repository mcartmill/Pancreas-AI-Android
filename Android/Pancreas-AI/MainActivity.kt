package com.pancreas.ai

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
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
    private val dateFmt    = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    private val updatedFmt = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
    private var chartBaseMs = 0L

    private var currentReadings: List<EgvReading> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupChart()
        observeViewModel()
        setupRangeButtons()
        handleOAuthResult(intent)

        binding.fabRefresh.setOnClickListener { viewModel.refresh() }
        binding.fabInsulin.setOnClickListener { showLogInsulinDialog() }
        binding.fabFood.setOnClickListener { showLogFoodDialog() }
        binding.btnGoToSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        viewModel.startAutoRefresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthResult(intent)
    }

    override fun onResume() { super.onResume(); viewModel.refresh() }
    override fun onDestroy() { super.onDestroy(); viewModel.stopAutoRefresh() }

    private fun handleOAuthResult(intent: Intent?) {
        val success = intent?.getBooleanExtra("oauth_success", false) ?: return
        val message = intent.getStringExtra("oauth_message") ?: ""
        if (success) {
            Toast.makeText(this, "✓ Connected to Dexcom!", Toast.LENGTH_LONG).show()
            viewModel.refresh()
        } else if (message.isNotBlank()) {
            Toast.makeText(this, "⚠ $message", Toast.LENGTH_LONG).show()
        }
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
        R.id.action_export   -> { showExportDialog(); true }
        else                 -> super.onOptionsItemSelected(item)
    }

    // ── Time Range ────────────────────────────────────────────────────────────

    private val rangeButtons by lazy {
        listOf(binding.btn1h, binding.btn3h, binding.btn6h, binding.btn12h, binding.btn24h)
    }
    private val rangeHours = listOf(1, 3, 6, 12, 24)

    private fun setupRangeButtons() {
        val currentHours = CredentialsManager.getChartHours(this)
        updateRangeButtonSelection(currentHours)
        rangeButtons.forEachIndexed { i, btn ->
            btn.setOnClickListener {
                val hours = rangeHours[i]
                viewModel.setChartHours(hours)
                updateRangeButtonSelection(hours)
            }
        }
    }

    private fun updateRangeButtonSelection(selectedHours: Int) {
        rangeButtons.forEachIndexed { i, btn ->
            val selected = rangeHours[i] == selectedHours
            btn.setTextColor(if (selected) Color.parseColor("#00BCD4") else Color.parseColor("#546E7A"))
            btn.setBackgroundColor(if (selected) Color.parseColor("#1A3A4A") else Color.TRANSPARENT)
        }
    }

    // ── Chart ─────────────────────────────────────────────────────────────────

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
                        timeFmt.format(Date(chartBaseMs + value.toLong()))
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

    private fun updateChart(
        readings: List<EgvReading>,
        insulinEntries: List<InsulinEntry>,
        foodEntries: List<FoodEntry>
    ) {
        if (readings.isEmpty()) return

        val baseMs = readings.first().epochMillis()
        chartBaseMs = baseMs

        val glucoseEntries = readings.map { r ->
            Entry((r.epochMillis() - baseMs).toFloat(), r.glucoseValue().toFloat())
        }

        val glucoseSet = LineDataSet(glucoseEntries, "Glucose").apply {
            color = Color.parseColor("#00BCD4")
            setCircleColor(Color.parseColor("#00BCD4"))
            lineWidth = 2.5f; circleRadius = 3f
            setDrawCircleHole(false); setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER; cubicIntensity = 0.2f
            setDrawFilled(true); fillColor = Color.parseColor("#00BCD4"); fillAlpha = 30
            highLightColor = Color.WHITE; highlightLineWidth = 1f
            enableDashedHighlightLine(10f, 5f, 0f)
        }

        val windowStart = readings.first().epochMillis()
        val windowEnd   = readings.last().epochMillis()

        // Clear previous event markers (keep Low/High lines on Y axis)
        binding.glucoseChart.xAxis.removeAllLimitLines()

        // Insulin markers — purple dashed lines
        insulinEntries.filter { it.timestampMs in windowStart..windowEnd }.forEach { dose ->
            val xOffset = (dose.timestampMs - baseMs).toFloat()
            binding.glucoseChart.xAxis.addLimitLine(LimitLine(xOffset, "%.1fu".format(dose.units)).apply {
                lineColor = Color.parseColor("#CC44FF")
                lineWidth = 1.5f
                textColor = Color.parseColor("#CC44FF")
                textSize  = 9f
                labelPosition = LimitLine.LimitLabelPosition.LEFT_TOP
                enableDashedLine(8f, 4f, 0f)
            })
        }

        // Food markers — green dashed lines
        foodEntries.filter { it.timestampMs in windowStart..windowEnd }.forEach { meal ->
            val xOffset = (meal.timestampMs - baseMs).toFloat()
            val label   = "%.0fg".format(meal.carbs)
            binding.glucoseChart.xAxis.addLimitLine(LimitLine(xOffset, label).apply {
                lineColor = Color.parseColor("#4CAF50")
                lineWidth = 1.5f
                textColor = Color.parseColor("#4CAF50")
                textSize  = 9f
                labelPosition = LimitLine.LimitLabelPosition.RIGHT_BOTTOM
                enableDashedLine(8f, 4f, 0f)
            })
        }

        binding.glucoseChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float) =
                timeFmt.format(Date(chartBaseMs + value.toLong()))
        }

        binding.glucoseChart.data = LineData(glucoseSet)
        binding.glucoseChart.notifyDataSetChanged()
        binding.glucoseChart.invalidate()
        binding.glucoseChart.animateX(500)
    }

    // ── ViewModel ─────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Loading       -> showLoading()
                is UiState.NoCredentials -> showNoCredentials()
                is UiState.NotConnected  -> showNotConnected()
                is UiState.Success       -> {
                    currentReadings = state.readings
                    showData(state.readings)
                }
                is UiState.Error -> showError(state.message)
            }
        }
        viewModel.lastUpdated.observe(this) { ts ->
            if (ts > 0) binding.tvLastUpdated.text = "Updated ${updatedFmt.format(Date(ts))}"
        }
        viewModel.insulinEntries.observe(this) { entries ->
            updateInsulinCard(entries)
            if (currentReadings.isNotEmpty())
                updateChart(currentReadings, entries, viewModel.foodEntries.value ?: emptyList())
        }
        viewModel.foodEntries.observe(this) { entries ->
            updateFoodCard(entries)
            if (currentReadings.isNotEmpty())
                updateChart(currentReadings, viewModel.insulinEntries.value ?: emptyList(), entries)
        }
    }

    // ── UI States ─────────────────────────────────────────────────────────────

    private fun showLoading() {
        binding.progressBar.visibility        = View.VISIBLE
        binding.cardCurrent.visibility        = View.GONE
        binding.cardStatus.visibility         = View.GONE
        binding.rangeBar.visibility           = View.GONE
        binding.noCredentialsGroup.visibility = View.GONE
        binding.errorCard.visibility          = View.GONE
    }

    private fun showNoCredentials() {
        binding.progressBar.visibility        = View.GONE
        binding.cardCurrent.visibility        = View.GONE
        binding.cardStatus.visibility         = View.GONE
        binding.rangeBar.visibility           = View.GONE
        binding.noCredentialsGroup.visibility = View.VISIBLE
        binding.tvNoCredsMessage.text         = "Open Settings and enter your Dexcom username and password to get started."
        binding.errorCard.visibility          = View.GONE
        binding.glucoseChart.clear()
    }

    private fun showNotConnected() {
        binding.progressBar.visibility        = View.GONE
        binding.cardCurrent.visibility        = View.GONE
        binding.cardStatus.visibility         = View.GONE
        binding.rangeBar.visibility           = View.GONE
        binding.noCredentialsGroup.visibility = View.VISIBLE
        binding.tvNoCredsMessage.text         = "Tap Settings and press \"Connect with Dexcom\" to authorize the app."
        binding.errorCard.visibility          = View.GONE
        binding.glucoseChart.clear()
    }

    private fun showData(readings: List<EgvReading>) {
        binding.progressBar.visibility        = View.GONE
        binding.noCredentialsGroup.visibility = View.GONE
        binding.errorCard.visibility          = View.GONE
        binding.rangeBar.visibility           = View.VISIBLE

        if (readings.isEmpty()) {
            binding.cardCurrent.visibility = View.GONE
            binding.cardStatus.visibility  = View.GONE
            binding.glucoseChart.setNoDataText("No readings in selected time range.")
            binding.glucoseChart.clear()
            return
        }

        binding.cardCurrent.visibility = View.VISIBLE
        binding.cardStatus.visibility  = View.VISIBLE

        updateCurrentReading(readings.last())
        updateChart(readings,
            viewModel.insulinEntries.value ?: emptyList(),
            viewModel.foodEntries.value ?: emptyList())
        updateStats(readings)
    }

    private fun showError(message: String) {
        binding.progressBar.visibility        = View.GONE
        binding.noCredentialsGroup.visibility = View.GONE
        binding.errorCard.visibility          = View.VISIBLE
        binding.tvErrorMessage.text           = message
        binding.cardCurrent.visibility        = View.GONE
        binding.cardStatus.visibility         = View.GONE
        binding.rangeBar.visibility           = View.GONE
    }

    // ── Current Reading ───────────────────────────────────────────────────────

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

    // ── Stats ─────────────────────────────────────────────────────────────────

    private fun updateStats(readings: List<EgvReading>) {
        val values  = readings.map { it.glucoseValue() }
        val avg     = values.average()
        val total   = values.size.toDouble()
        val inRange = values.count { it in 70..180 }
        val low     = values.count { it < 70 }
        val high    = values.count { it > 180 }
        binding.tvAvgGlucose.text   = "%.0f".format(avg)
        binding.tvTimeInRange.text  = "${(inRange / total * 100).toInt()}%"
        binding.tvTimeLow.text      = "${(low    / total * 100).toInt()}%"
        binding.tvTimeHigh.text     = "${(high   / total * 100).toInt()}%"
        binding.tvReadingCount.text = "${readings.size} readings"
    }

    // ── Insulin Card ──────────────────────────────────────────────────────────

    private fun updateInsulinCard(entries: List<InsulinEntry>) {
        binding.cardInsulin.visibility = View.VISIBLE
        val since  = System.currentTimeMillis() - 24 * 3_600_000L
        val recent = entries.filter { it.timestampMs >= since }.take(8)
        val totalUnits = recent.sumOf { it.units }
        binding.tvTotalInsulin.text = if (recent.isNotEmpty()) "%.1f u (24h)".format(totalUnits) else ""

        binding.insulinListContainer.removeAllViews()
        if (recent.isEmpty()) { binding.tvNoInsulin.visibility = View.VISIBLE; return }
        binding.tvNoInsulin.visibility = View.GONE

        recent.forEach { dose ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val dot = TextView(this).apply {
                text = "●"; textSize = 14f
                setTextColor(when (dose.type) {
                    InsulinType.RAPID -> Color.parseColor("#CC44FF")
                    InsulinType.LONG  -> Color.parseColor("#00BCD4")
                    InsulinType.OTHER -> Color.parseColor("#546E7A")
                })
                setPadding(0, 0, 12, 0)
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = "%.1f u  •  %s".format(dose.units, dose.type.label)
                textSize = 14f; setTextColor(Color.parseColor("#E0E8F0"))
            })
            info.addView(TextView(this).apply {
                text = dateFmt.format(Date(dose.timestampMs)) +
                    if (dose.note.isNotBlank()) "  —  ${dose.note}" else ""
                textSize = 11f; setTextColor(Color.parseColor("#6A8499"))
            })
            row.addView(dot); row.addView(info)
            row.addView(TextView(this).apply {
                text = "✕"; textSize = 14f; setTextColor(Color.parseColor("#546E7A"))
                setPadding(24, 0, 0, 0); setOnClickListener { confirmDeleteInsulin(dose) }
            })
            binding.insulinListContainer.addView(row)
            if (dose != recent.last()) binding.insulinListContainer.addView(makeDivider())
        }
    }

    private fun confirmDeleteInsulin(dose: InsulinEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete dose?")
            .setMessage("%.1f u %s at %s".format(dose.units, dose.type.label, dateFmt.format(Date(dose.timestampMs))))
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteInsulin(dose.id) }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Food Card ─────────────────────────────────────────────────────────────

    private fun updateFoodCard(entries: List<FoodEntry>) {
        binding.cardFood.visibility = View.VISIBLE
        val since  = System.currentTimeMillis() - 24 * 3_600_000L
        val recent = entries.filter { it.timestampMs >= since }.take(8)
        val totalCarbs = recent.sumOf { it.carbs }
        val totalCals  = recent.sumOf { it.calories }
        binding.tvTotalCarbs.text = when {
            recent.isEmpty() -> ""
            totalCals > 0    -> "%.0fg carbs  •  %d cal (24h)".format(totalCarbs, totalCals)
            else             -> "%.0fg carbs (24h)".format(totalCarbs)
        }

        binding.foodListContainer.removeAllViews()
        if (recent.isEmpty()) { binding.tvNoFood.visibility = View.VISIBLE; return }
        binding.tvNoFood.visibility = View.GONE

        recent.forEach { meal ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val dot = TextView(this).apply {
                text = "●"; textSize = 14f
                setTextColor(mealColor(meal.mealType))
                setPadding(0, 0, 12, 0)
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val carbsLine = if (meal.calories > 0)
                "%.0fg carbs  •  %d cal  •  %s".format(meal.carbs, meal.calories, meal.mealType.label)
            else
                "%.0fg carbs  •  %s".format(meal.carbs, meal.mealType.label)
            info.addView(TextView(this).apply {
                text = meal.name.ifBlank { meal.mealType.label }
                textSize = 14f; setTextColor(Color.parseColor("#E0E8F0"))
            })
            info.addView(TextView(this).apply {
                text = carbsLine
                textSize = 12f; setTextColor(mealColor(meal.mealType))
            })
            info.addView(TextView(this).apply {
                text = dateFmt.format(Date(meal.timestampMs)) +
                    if (meal.note.isNotBlank()) "  —  ${meal.note}" else ""
                textSize = 11f; setTextColor(Color.parseColor("#6A8499"))
            })
            row.addView(dot); row.addView(info)
            row.addView(TextView(this).apply {
                text = "✕"; textSize = 14f; setTextColor(Color.parseColor("#546E7A"))
                setPadding(24, 0, 0, 0); setOnClickListener { confirmDeleteFood(meal) }
            })
            binding.foodListContainer.addView(row)
            if (meal != recent.last()) binding.foodListContainer.addView(makeDivider())
        }
    }

    private fun mealColor(type: MealType) = when (type) {
        MealType.BREAKFAST -> Color.parseColor("#FF9800")
        MealType.LUNCH     -> Color.parseColor("#4CAF50")
        MealType.DINNER    -> Color.parseColor("#2196F3")
        MealType.SNACK     -> Color.parseColor("#9C27B0")
        MealType.DRINK     -> Color.parseColor("#00BCD4")
        MealType.OTHER     -> Color.parseColor("#546E7A")
    }

    private fun confirmDeleteFood(meal: FoodEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete meal?")
            .setMessage("${meal.name.ifBlank { meal.mealType.label }} at ${dateFmt.format(Date(meal.timestampMs))}")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteFood(meal.id) }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private fun makeDivider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(Color.parseColor("#1A2A3A"))
    }

    private fun makeTimePicker(cal: Calendar, label: TextView) {
        DatePickerDialog(this,
            { _, y, m, d ->
                cal.set(y, m, d)
                TimePickerDialog(this,
                    { _, h, min -> cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
                        label.text = dateFmt.format(cal.time) },
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false
                ).show()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ── Log Insulin Dialog ────────────────────────────────────────────────────

    private fun showLogInsulinDialog() {
        val doseTime = Calendar.getInstance()

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(64, 32, 64, 16) }

        val unitsInput = EditText(this).apply {
            hint = "Units (e.g. 4.5)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.parseColor("#E0E8F0")); setHintTextColor(Color.parseColor("#546E7A"))
        }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, InsulinType.values().map { it.label })
        }
        val timeBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = dateFmt.format(doseTime.time)
            setTextColor(Color.parseColor("#E0E8F0")); setBackgroundColor(Color.parseColor("#1A2A3A"))
            setOnClickListener { makeTimePicker(doseTime, this); }
        }
        val noteInput = EditText(this).apply {
            hint = "Note (optional)"; setTextColor(Color.parseColor("#E0E8F0")); setHintTextColor(Color.parseColor("#546E7A"))
        }

        container.addView(label("Units")); container.addView(unitsInput)
        container.addView(label("Type")); container.addView(typeSpinner)
        container.addView(label("Time")); container.addView(timeBtn)
        container.addView(label("Note (optional)")); container.addView(noteInput)

        AlertDialog.Builder(this).setTitle("Log Insulin Dose").setView(container)
            .setPositiveButton("Save") { _, _ ->
                val units = unitsInput.text.toString().trim().toDoubleOrNull()
                if (units != null && units > 0) {
                    val type = InsulinType.values()[typeSpinner.selectedItemPosition]
                    viewModel.addInsulin(units, type, doseTime.timeInMillis, noteInput.text.toString().trim())
                    Toast.makeText(this, "%.1f u logged".format(units), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Enter a valid number of units", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Log Food Dialog ───────────────────────────────────────────────────────

    private fun showLogFoodDialog() {
        val mealTime = Calendar.getInstance()

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(64, 32, 64, 16) }

        val nameInput = EditText(this).apply {
            hint = "Food name (e.g. Oatmeal)"
            setTextColor(Color.parseColor("#E0E8F0")); setHintTextColor(Color.parseColor("#546E7A"))
        }
        val carbsInput = EditText(this).apply {
            hint = "Carbs in grams (e.g. 45)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.parseColor("#E0E8F0")); setHintTextColor(Color.parseColor("#546E7A"))
        }
        val calInput = EditText(this).apply {
            hint = "Calories (optional)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.parseColor("#E0E8F0")); setHintTextColor(Color.parseColor("#546E7A"))
        }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, MealType.values().map { it.label })
        }
        val timeBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = dateFmt.format(mealTime.time)
            setTextColor(Color.parseColor("#E0E8F0")); setBackgroundColor(Color.parseColor("#1A2A3A"))
            setOnClickListener { makeTimePicker(mealTime, this) }
        }
        val noteInput = EditText(this).apply {
            hint = "Note (optional)"; setTextColor(Color.parseColor("#E0E8F0")); setHintTextColor(Color.parseColor("#546E7A"))
        }

        container.addView(label("Food Name")); container.addView(nameInput)
        container.addView(label("Carbohydrates (g)")); container.addView(carbsInput)
        container.addView(label("Calories (optional)")); container.addView(calInput)
        container.addView(label("Meal Type")); container.addView(typeSpinner)
        container.addView(label("Time")); container.addView(timeBtn)
        container.addView(label("Note (optional)")); container.addView(noteInput)

        AlertDialog.Builder(this).setTitle("Log Meal / Food").setView(container)
            .setPositiveButton("Save") { _, _ ->
                val carbs = carbsInput.text.toString().trim().toDoubleOrNull()
                if (carbs != null && carbs >= 0) {
                    val name  = nameInput.text.toString().trim()
                    val cals  = calInput.text.toString().trim().toIntOrNull() ?: 0
                    val type  = MealType.values()[typeSpinner.selectedItemPosition]
                    val note  = noteInput.text.toString().trim()
                    viewModel.addFood(name, carbs, cals, type, mealTime.timeInMillis, note)
                    val display = name.ifBlank { type.label }
                    Toast.makeText(this, "$display logged (%.0fg carbs)".format(carbs), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Enter a valid carb count", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f
        setTextColor(Color.parseColor("#6A8499"))
        setPadding(0, 16, 0, 4)
    }

    // ── Export Report Dialog ───────────────────────────────────────────────────

    private fun showExportDialog() {
        val periods = ReportPeriod.values()
        val labels  = periods.map { it.label }.toTypedArray()
        var selected = 0  // default: Last 24 Hours

        AlertDialog.Builder(this)
            .setTitle("Export Report")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton("Export") { _, _ ->
                try {
                    val intent = ReportExporter.export(this, periods[selected])
                    startActivity(Intent.createChooser(intent, "Share Report via..."))
                } catch (e: Exception) {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
