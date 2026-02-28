package com.pancreas.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pancreas.ai.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var passwordVisible = false
    private var secretVisible   = false

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            CredentialsManager.setNotificationsEnabled(this, true)
            binding.switchNotifEnabled.isChecked = true
            binding.layoutNotifOptions.visibility = View.VISIBLE
            showToast("Notifications enabled ✓")
        } else {
            binding.switchNotifEnabled.isChecked = false
            CredentialsManager.setNotificationsEnabled(this, false)
            showToast("Permission denied — notifications won't work")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        loadSavedSettings()
        setupListeners()
        updateAuthModeUi()
        updateConnectionStatus()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressedDispatcher.onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() { super.onResume(); updateConnectionStatus() }

    private fun loadSavedSettings() {
        // Auth mode
        when (CredentialsManager.getAuthMode(this)) {
            AuthMode.SHARE -> binding.rbShare.isChecked = true
            AuthMode.OAUTH -> binding.rbOAuth.isChecked = true
        }

        // Share credentials
        binding.etShareUsername.setText(CredentialsManager.getShareUsername(this))
        if (CredentialsManager.getSharePassword(this).isNotBlank())
            binding.etSharePassword.hint = "••••••••  (saved)"
        binding.switchOutsideUs.isChecked = CredentialsManager.isOutsideUs(this)

        // OAuth credentials
        binding.etClientId.setText(CredentialsManager.getClientId(this))
        if (CredentialsManager.getClientSecret(this).isNotBlank())
            binding.etClientSecret.hint = "••••••••  (saved)"
        binding.switchSandbox.isChecked = CredentialsManager.useSandbox(this)

        // Device type
        when (CredentialsManager.getDeviceType(this)) {
            DeviceType.G6 -> binding.rbG6.isChecked = true
            DeviceType.G7 -> binding.rbG7.isChecked = true
        }
        updateSandboxHint()

        // Refresh interval
        val interval = CredentialsManager.getRefreshInterval(this)
        binding.sliderInterval.value = interval.toFloat()
        binding.tvIntervalValue.text = "$interval min"

        // Personal info
        val h = CredentialsManager.getHeightIn(this)
        val w = CredentialsManager.getWeightLb(this)
        val age = CredentialsManager.getAge(this)
        val gender = CredentialsManager.getGender(this)
        if (h > 0) binding.etHeight.setText("%.1f".format(h))
        if (w > 0) binding.etWeight.setText("%.1f".format(w))
        if (age > 0) binding.etAge.setText(age.toString())

        val genderOptions = listOf("Prefer not to say", "Female", "Male", "Non-binary", "Other")
        binding.spinnerGender.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, genderOptions)
        val genderIdx = genderOptions.indexOfFirst { it.equals(gender, ignoreCase = true) }
            .takeIf { it >= 0 } ?: 0
        binding.spinnerGender.setSelection(genderIdx)

        // Glucose thresholds
        val high = CredentialsManager.getGlucoseHigh(this)
        val low  = CredentialsManager.getGlucoseLow(this)
        binding.sliderHigh.value = high.toFloat().coerceIn(120f, 300f)
        binding.sliderLow.value  = low.toFloat().coerceIn(50f, 100f)
        binding.tvHighValue.text = "$high mg/dL"
        binding.tvLowValue.text  = "$low mg/dL"

        // Notifications
        val notifEnabled = CredentialsManager.isNotificationsEnabled(this)
        binding.switchNotifEnabled.isChecked  = notifEnabled
        binding.layoutNotifOptions.visibility = if (notifEnabled) View.VISIBLE else View.GONE
        binding.switchNotifHigh.isChecked     = CredentialsManager.isPredictHighEnabled(this)
        binding.switchNotifLow.isChecked      = CredentialsManager.isPredictLowEnabled(this)
        val projMin = CredentialsManager.getProjectionMinutes(this)
        binding.sliderProjection.value    = projMin.toFloat().coerceIn(10f, 40f)
        binding.tvProjectionMinutes.text  = "$projMin min"
    }

    private fun setupListeners() {
        // Auth mode toggle
        binding.rgAuthMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbShare) AuthMode.SHARE else AuthMode.OAUTH
            CredentialsManager.setAuthMode(this, mode)
            updateAuthModeUi()
            updateConnectionStatus()
        }

        // Share credentials
        binding.btnTogglePassword.setOnClickListener {
            passwordVisible = !passwordVisible
            binding.etSharePassword.inputType = if (passwordVisible)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.etSharePassword.setSelection(binding.etSharePassword.text?.length ?: 0)
        }

        binding.btnSaveShare.setOnClickListener { saveShareCredentials() }

        binding.switchOutsideUs.setOnCheckedChangeListener { _, _ -> updateConnectionStatus() }

        // OAuth credentials
        binding.btnToggleSecret.setOnClickListener {
            secretVisible = !secretVisible
            binding.etClientSecret.inputType = if (secretVisible)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.etClientSecret.setSelection(binding.etClientSecret.text?.length ?: 0)
        }

        binding.btnSaveOAuth.setOnClickListener { saveOAuthCredentials() }
        binding.btnConnect.setOnClickListener { launchOAuth() }

        binding.btnDisconnect.setOnClickListener {
            CredentialsManager.clearTokens(this)
            CredentialsManager.clearShareSession(this)
            updateConnectionStatus()
            showToast("Disconnected")
        }

        binding.btnDiagnose.setOnClickListener { runDiagnostics() }

        // Claude API key
        binding.etClaudeKey.setText(CredentialsManager.getClaudeApiKey(this))
        binding.btnSaveClaudeKey.setOnClickListener {
            val key = binding.etClaudeKey.text.toString().trim()
            CredentialsManager.saveClaudeApiKey(this, key)
            binding.tvClaudeKeyStatus.apply {
                visibility = android.view.View.VISIBLE
                if (key.isNotBlank()) {
                    text = "✓ API key saved"; setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                } else {
                    text = "API key cleared"; setTextColor(android.graphics.Color.parseColor("#6A8499"))
                }
            }
        }

        // Device type
        binding.rgDeviceType.setOnCheckedChangeListener { _, checkedId ->
            val type = if (checkedId == R.id.rbG6) DeviceType.G6 else DeviceType.G7
            CredentialsManager.setDeviceType(this, type)
            updateSandboxHint()
        }

        binding.sliderInterval.addOnChangeListener { _, value, _ ->
            val mins = value.toInt()
            binding.tvIntervalValue.text = "$mins min"
            CredentialsManager.setRefreshInterval(this, mins)
        }

        binding.switchSandbox.setOnCheckedChangeListener { _, checked ->
            if (CredentialsManager.hasClientCredentials(this)) {
                CredentialsManager.saveClientCredentials(
                    this,
                    CredentialsManager.getClientId(this),
                    CredentialsManager.getClientSecret(this),
                    checked
                )
            }
            updateSandboxHint()
            updateConnectionStatus()
        }

        binding.tvDevPortalLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.dexcom.com/")))
        }

        // Personal info
        binding.btnSavePersonalInfo.setOnClickListener { savePersonalInfo() }

        // Glucose thresholds — save immediately on slider change
        binding.sliderHigh.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            binding.tvHighValue.text = "$v mg/dL"
            val low = CredentialsManager.getGlucoseLow(this)
            CredentialsManager.saveGlucoseThresholds(this, v, low)
        }
        binding.sliderLow.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            binding.tvLowValue.text = "$v mg/dL"
            val high = CredentialsManager.getGlucoseHigh(this)
            CredentialsManager.saveGlucoseThresholds(this, high, v)
        }

        // Notifications
        binding.switchNotifEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                requestNotificationPermission()
            } else {
                CredentialsManager.setNotificationsEnabled(this, false)
                binding.layoutNotifOptions.visibility = View.GONE
            }
        }
        binding.switchNotifHigh.setOnCheckedChangeListener { _, checked ->
            CredentialsManager.setPredictHighEnabled(this, checked)
        }
        binding.switchNotifLow.setOnCheckedChangeListener { _, checked ->
            CredentialsManager.setPredictLowEnabled(this, checked)
        }
        binding.sliderProjection.addOnChangeListener { _, value, _ ->
            val mins = value.toInt()
            binding.tvProjectionMinutes.text = "$mins min"
            CredentialsManager.setProjectionMinutes(this, mins)
        }
    }

    private fun updateAuthModeUi() {
        val isShare = CredentialsManager.getAuthMode(this) == AuthMode.SHARE
        binding.cardShareCreds.visibility = if (isShare) View.VISIBLE else View.GONE
        binding.cardOAuthCreds.visibility = if (isShare) View.GONE   else View.VISIBLE
    }

    private fun updateSandboxHint() {
        val sandbox = CredentialsManager.useSandbox(this)
        if (sandbox) {
            val device = if (binding.rbG6.isChecked) DeviceType.G6 else DeviceType.G7
            binding.tvSandboxHint.text =
                "Sandbox: connect as \"${device.sandboxUser}\" (no password needed)"
            binding.tvSandboxHint.visibility = View.VISIBLE
        } else {
            binding.tvSandboxHint.visibility = View.GONE
        }
    }

    private fun saveShareCredentials() {
        val username = binding.etShareUsername.text?.toString()?.trim() ?: ""
        val password = binding.etSharePassword.text?.toString() ?: ""
        val outsideUs = binding.switchOutsideUs.isChecked

        if (username.isBlank()) { binding.etShareUsername.error = "Username is required"; return }
        val finalPass = if (password.isBlank()) CredentialsManager.getSharePassword(this) else password
        if (finalPass.isBlank()) { binding.etSharePassword.error = "Password is required"; return }

        CredentialsManager.saveShareCredentials(this, username, finalPass, outsideUs)
        binding.etSharePassword.setText("")
        binding.etSharePassword.hint = "••••••••  (saved)"
        showToast("Share credentials saved ✓")
        updateConnectionStatus()
    }

    private fun saveOAuthCredentials() {
        val clientId     = binding.etClientId.text?.toString()?.trim() ?: ""
        val clientSecret = binding.etClientSecret.text?.toString() ?: ""
        val sandbox      = binding.switchSandbox.isChecked

        if (clientId.isBlank()) { binding.etClientId.error = "Client ID is required"; return }
        val finalSecret = if (clientSecret.isBlank()) CredentialsManager.getClientSecret(this) else clientSecret
        if (finalSecret.isBlank()) { binding.etClientSecret.error = "Client Secret is required"; return }

        CredentialsManager.saveClientCredentials(this, clientId, finalSecret, sandbox)
        binding.etClientSecret.setText("")
        binding.etClientSecret.hint = "••••••••  (saved)"
        showToast("OAuth credentials saved ✓")
        updateConnectionStatus()
    }

    private fun launchOAuth() {
        if (!CredentialsManager.hasClientCredentials(this)) {
            showToast("Save your Client ID and Secret first")
            return
        }
        startActivity(Intent(this, OAuthWebViewActivity::class.java))
    }

    private fun runDiagnostics() {
        binding.btnDiagnose.isEnabled = false
        binding.tvDiagnosticResult.text = "Running diagnostics…"
        binding.tvDiagnosticResult.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val result = GlucoseRepository(applicationContext).getDiagnostics()
                binding.tvDiagnosticResult.text = result
            } catch (e: Exception) {
                binding.tvDiagnosticResult.text = "Error: ${e.message}"
            }
            binding.btnDiagnose.isEnabled = true
        }
    }

    private fun updateConnectionStatus() {
        val mode    = CredentialsManager.getAuthMode(this)
        val sandbox = CredentialsManager.useSandbox(this)

        when (mode) {
            AuthMode.SHARE -> {
                val hasCreds = CredentialsManager.hasShareCredentials(this)
                binding.tvConnectionStatus.text = when {
                    hasCreds -> "● Dexcom Share — ${if (CredentialsManager.isOutsideUs(this)) "Outside US" else "US"}"
                    else     -> "○ Enter username and password above"
                }
                binding.tvConnectionStatus.setTextColor(
                    if (hasCreds) getColor(android.R.color.holo_green_light)
                    else getColor(android.R.color.holo_orange_light)
                )
                binding.btnConnect.visibility    = View.GONE
                binding.btnDisconnect.visibility = if (hasCreds) View.VISIBLE else View.GONE
                binding.btnDiagnose.visibility   = if (hasCreds) View.VISIBLE else View.GONE
            }
            AuthMode.OAUTH -> {
                val connected = CredentialsManager.isOAuthConnected(this)
                val hasCreds  = CredentialsManager.hasClientCredentials(this)
                binding.tvConnectionStatus.text = when {
                    connected -> "● Connected${if (sandbox) " (Sandbox)" else " (Production)"}"
                    hasCreds  -> "○ Not connected — tap Connect below"
                    else      -> "○ Enter credentials then connect"
                }
                binding.tvConnectionStatus.setTextColor(
                    if (connected) getColor(android.R.color.holo_green_light)
                    else getColor(android.R.color.holo_orange_light)
                )
                binding.btnConnect.visibility    = View.VISIBLE
                binding.btnConnect.text = if (connected) "Re-connect with Dexcom" else "Connect with Dexcom"
                binding.btnDisconnect.visibility = if (connected) View.VISIBLE else View.GONE
                binding.btnDiagnose.visibility   = if (connected) View.VISIBLE else View.GONE
            }
        }

        if (!CredentialsManager.isConnected(this))
            binding.tvDiagnosticResult.visibility = View.GONE
    }

    private fun savePersonalInfo() {
        val heightStr = binding.etHeight.text?.toString()?.trim() ?: ""
        val weightStr = binding.etWeight.text?.toString()?.trim() ?: ""
        val ageStr    = binding.etAge.text?.toString()?.trim() ?: ""
        val gender    = binding.spinnerGender.selectedItem?.toString() ?: ""

        val height = heightStr.toFloatOrNull() ?: CredentialsManager.getHeightIn(this)
        val weight = weightStr.toFloatOrNull() ?: CredentialsManager.getWeightLb(this)
        val age    = ageStr.toIntOrNull()      ?: CredentialsManager.getAge(this)

        CredentialsManager.savePersonalInfo(this, height, weight, gender, age)
        showToast("Personal info saved ✓")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED -> {
                    // Already granted
                    CredentialsManager.setNotificationsEnabled(this, true)
                    binding.layoutNotifOptions.visibility = View.VISIBLE
                }
                else -> notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // Pre-Android 13 — permission granted at install time
            CredentialsManager.setNotificationsEnabled(this, true)
            binding.layoutNotifOptions.visibility = View.VISIBLE
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
