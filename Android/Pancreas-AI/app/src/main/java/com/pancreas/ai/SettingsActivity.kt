package com.pancreas.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pancreas.ai.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var passwordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        loadSavedSettings()
        setupClickListeners()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadSavedSettings() {
        binding.etUsername.setText(CredentialsManager.getUsername(this))
        if (CredentialsManager.getPassword(this).isNotBlank()) {
            binding.etPassword.hint = "••••••••  (saved)"
        }
        val region = CredentialsManager.getRegion(this)
        if (region == "US") binding.rbUs.isChecked = true else binding.rbOutsideUs.isChecked = true

        val interval = CredentialsManager.getRefreshInterval(this)
        binding.sliderInterval.value = interval.toFloat()
        binding.tvIntervalValue.text = "$interval min"
    }

    private fun setupClickListeners() {
        binding.btnTogglePassword.setOnClickListener {
            passwordVisible = !passwordVisible
            if (passwordVisible) {
                binding.etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                binding.btnTogglePassword.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            } else {
                binding.etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                binding.btnTogglePassword.setImageResource(android.R.drawable.ic_menu_view)
            }
            binding.etPassword.setSelection(binding.etPassword.text?.length ?: 0)
        }

        binding.sliderInterval.addOnChangeListener { _, value, _ ->
            val mins = value.toInt()
            binding.tvIntervalValue.text = "$mins min"
            CredentialsManager.setRefreshInterval(this, mins)
        }

        binding.btnSave.setOnClickListener { saveCredentials() }
        binding.btnTest.setOnClickListener { testConnection() }

        binding.btnClearSession.setOnClickListener {
            CredentialsManager.clearSession(this)
            showToast("Session cleared. Next data fetch will re-authenticate.")
        }

        binding.tvHelpLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.dexcom.com/faqs/how-do-i-set-dexcom-share"))
            startActivity(intent)
        }
    }

    private fun saveCredentials() {
        val username = binding.etUsername.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""
        val region = if (binding.rbUs.isChecked) "US" else "OUTSIDE"

        if (username.isBlank()) { binding.etUsername.error = "Username is required"; return }

        val finalPassword = if (password.isBlank()) CredentialsManager.getPassword(this) else password
        if (finalPassword.isBlank()) { binding.etPassword.error = "Password is required"; return }

        CredentialsManager.saveCredentials(this, username, finalPassword, region)
        showToast("Credentials saved ✓")
        binding.etPassword.setText("")
        binding.etPassword.hint = "••••••••  (saved)"
    }

    private fun testConnection() {
        val username = binding.etUsername.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""
        val region = if (binding.rbUs.isChecked) "US" else "OUTSIDE"

        if (username.isBlank() || password.isBlank()) {
            showToast("Enter credentials first")
            return
        }

        CredentialsManager.saveCredentials(this, username, password, region)
        binding.btnTest.isEnabled = false
        binding.testProgressBar.visibility = View.VISIBLE
        binding.tvTestResult.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val repo = GlucoseRepository(applicationContext)
                repo.login()

                // Pull full 24 h so we get readings regardless of how long the sensor has been active
                val readings = repo.fetchReadings(minutes = 1440, maxCount = 288)

                binding.testProgressBar.visibility = View.GONE
                binding.btnTest.isEnabled = true

                if (readings.isNotEmpty()) {
                    val latest = readings.last()
                    val count  = readings.size
                    val oldest = java.text.SimpleDateFormat("MMM d h:mm a", java.util.Locale.getDefault())
                        .format(java.util.Date(readings.first().epochMillis()))
                    binding.tvTestResult.text =
                        "✓ Connected!  $count readings found\n" +
                        "Latest: ${latest.value} mg/dL ${latest.trendArrow()}\n" +
                        "History back to: $oldest"
                    binding.tvTestResult.setTextColor(getColor(android.R.color.holo_green_light))
                } else {
                    // Connected but no data — almost always means Share isn't enabled
                    binding.tvTestResult.text =
                        "⚠ Login succeeded but no readings returned.\n\n" +
                        "To fix this, open the Dexcom app and:\n" +
                        "  1. Tap Menu → Share\n" +
                        "  2. Turn the Share toggle ON\n" +
                        "  3. Tap 'Invite Follower' and send\n" +
                        "     an invite (any email, even your own)\n\n" +
                        "Once sharing is active, tap Test again."
                    binding.tvTestResult.setTextColor(getColor(android.R.color.holo_orange_light))
                }
                binding.tvTestResult.visibility = View.VISIBLE

            } catch (e: Exception) {
                binding.testProgressBar.visibility = View.GONE
                binding.btnTest.isEnabled = true
                binding.tvTestResult.text = "✗ ${e.message}"
                binding.tvTestResult.setTextColor(getColor(android.R.color.holo_red_light))
                binding.tvTestResult.visibility = View.VISIBLE
            }
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
