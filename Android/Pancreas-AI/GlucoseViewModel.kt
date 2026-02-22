package com.pancreas.ai

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class UiState {
    object Loading       : UiState()
    object NoCredentials : UiState()
    object NotConnected  : UiState()
    data class Success(val readings: List<EgvReading>) : UiState()
    data class Error(val message: String) : UiState()
}

class GlucoseViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "GlucoseViewModel"
    private val repository = GlucoseRepository(app)

    private val _uiState = MutableLiveData<UiState>(UiState.Loading)
    val uiState: LiveData<UiState> = _uiState

    private val _lastUpdated = MutableLiveData<Long>(0L)
    val lastUpdated: LiveData<Long> = _lastUpdated

    private val _chartHours = MutableLiveData(CredentialsManager.getChartHours(app))
    val chartHours: LiveData<Int> = _chartHours

    // ── Insulin ───────────────────────────────────────────────────────────────

    private val _insulinEntries = MutableLiveData<List<InsulinEntry>>(emptyList())
    val insulinEntries: LiveData<List<InsulinEntry>> = _insulinEntries

    init {
        loadInsulin()
    }

    private fun loadInsulin() {
        val ctx = getApplication<Application>()
        _insulinEntries.postValue(InsulinManager.load(ctx))
    }

    fun addInsulin(units: Double, type: InsulinType, timestampMs: Long, note: String = "") {
        val ctx   = getApplication<Application>()
        val entry = InsulinEntry(units = units, type = type, timestampMs = timestampMs, note = note)
        val updated = InsulinManager.add(ctx, entry)
        _insulinEntries.postValue(updated)
    }

    fun deleteInsulin(id: String) {
        val ctx = getApplication<Application>()
        val updated = InsulinManager.delete(ctx, id)
        _insulinEntries.postValue(updated)
    }

    // ── Glucose ───────────────────────────────────────────────────────────────

    private var refreshJob: Job? = null

    fun setChartHours(hours: Int) {
        CredentialsManager.setChartHours(getApplication(), hours)
        _chartHours.value = hours
        refresh()
    }

    fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                loadReadings()
                val intervalMs = CredentialsManager.getRefreshInterval(getApplication()) * 60_000L
                delay(intervalMs)
            }
        }
    }

    fun stopAutoRefresh() { refreshJob?.cancel(); refreshJob = null }

    fun refresh() { viewModelScope.launch { loadReadings() } }

    private suspend fun loadReadings() {
        val ctx  = getApplication<Application>()
        val mode = CredentialsManager.getAuthMode(ctx)

        val credentialsReady = when (mode) {
            AuthMode.SHARE -> CredentialsManager.hasShareCredentials(ctx)
            AuthMode.OAUTH -> CredentialsManager.hasClientCredentials(ctx)
        }

        if (!credentialsReady) { _uiState.postValue(UiState.NoCredentials); return }
        if (mode == AuthMode.OAUTH && !CredentialsManager.isOAuthConnected(ctx)) {
            _uiState.postValue(UiState.NotConnected); return
        }

        _uiState.postValue(UiState.Loading)
        try {
            val hours    = _chartHours.value ?: CredentialsManager.getChartHours(ctx)
            val readings = repository.fetchReadings(hours)
            _uiState.postValue(UiState.Success(readings))
            _lastUpdated.postValue(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching readings", e)
            _uiState.postValue(UiState.Error(e.message ?: "Unknown error"))
        }
    }

    override fun onCleared() { super.onCleared(); stopAutoRefresh() }
}
