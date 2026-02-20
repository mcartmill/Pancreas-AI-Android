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
    private var secretVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
        loadSavedSettings()
        setupListeners()
        updateConnectionStatus()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressedDispatcher.onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() { super.onResume(); updateConnectionStatus() }

    private fun loadSavedSettings() {
        binding.etClientId.setText(CredentialsManager.getClientId(this))
        if (CredentialsManager.getClientSecret(this).isNotBlank())
            binding.etClientSecret.hint = "••••••••  (saved)"
        binding.switchSandbox.isChecked = CredentialsManager.useSandbox(this)
        val interval = CredentialsManager.getRefreshInterval(this)
        binding.sliderInterval.value = interval.toFloat()
        binding.tvIntervalValue.text = "$interval min"
    }

    private fun setupListeners() {
        binding.btnToggleSecret.setOnClickListener {
            secretVisible = !secretVisible
            binding.etClientSecret.inputType = if (secretVisible)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.etClientSecret.setSelection(binding.etClientSecret.text?.length ?: 0)
        }

        binding.btnSave.setOnClickListener { saveCredentials() }
        binding.btnConnect.setOnClickListener { launchOAuth() }

        binding.btnDisconnect.setOnClickListener {
            CredentialsManager.clearTokens(this)
            updateConnectionStatus()
            showToast("Disconnected from Dexcom")
        }

        binding.btnDiagnose.setOnClickListener { runDiagnostics() }

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
            updateConnectionStatus()
        }

        binding.tvDevPortalLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.dexcom.com/")))
        }
    }

    private fun saveCredentials() {
        val clientId     = binding.etClientId.text?.toString()?.trim() ?: ""
        val clientSecret = binding.etClientSecret.text?.toString() ?: ""
        val sandbox      = binding.switchSandbox.isChecked

        if (clientId.isBlank()) { binding.etClientId.error = "Client ID is required"; return }
        val finalSecret = if (clientSecret.isBlank()) CredentialsManager.getClientSecret(this) else clientSecret
        if (finalSecret.isBlank()) { binding.etClientSecret.error = "Client Secret is required"; return }

        CredentialsManager.saveClientCredentials(this, clientId, finalSecret, sandbox)
        binding.etClientSecret.setText("")
        binding.etClientSecret.hint = "••••••••  (saved)"
        showToast("Credentials saved ✓")
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
        if (!CredentialsManager.isConnected(this)) {
            showToast("Connect to Dexcom first")
            return
        }
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
        val connected = CredentialsManager.isConnected(this)
        val hasCreds  = CredentialsManager.hasClientCredentials(this)
        val sandbox   = CredentialsManager.useSandbox(this)

        if (connected) {
            binding.tvConnectionStatus.text =
                "● Connected${if (sandbox) " (Sandbox)" else " (Production)"}"
            binding.tvConnectionStatus.setTextColor(getColor(android.R.color.holo_green_light))
            binding.btnConnect.text = "Re-connect with Dexcom"
            binding.btnDisconnect.visibility = View.VISIBLE
            binding.btnDiagnose.visibility   = View.VISIBLE
        } else {
            binding.tvConnectionStatus.text =
                if (hasCreds) "○ Not connected — tap Connect below"
                else "○ Enter credentials then connect"
            binding.tvConnectionStatus.setTextColor(getColor(android.R.color.holo_orange_light))
            binding.btnConnect.text = "Connect with Dexcom"
            binding.btnDisconnect.visibility = View.GONE
            binding.btnDiagnose.visibility   = View.GONE
            binding.tvDiagnosticResult.visibility = View.GONE
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
