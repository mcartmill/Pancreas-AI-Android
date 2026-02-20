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
    object Loading : UiState()
    object NoCredentials : UiState()
    data class Success(val readings: List<GlucoseReading>) : UiState()
    data class Error(val message: String) : UiState()
}

class GlucoseViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "GlucoseViewModel"
    private val repository = GlucoseRepository(app)

    private val _uiState = MutableLiveData<UiState>(UiState.Loading)
    val uiState: LiveData<UiState> = _uiState

    private val _lastUpdated = MutableLiveData<Long>(0L)
    val lastUpdated: LiveData<Long> = _lastUpdated

    private var refreshJob: Job? = null

    fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                loadReadings()
                val intervalMs = CredentialsManager.getRefreshInterval(getApplication()) * 60_000L
                Log.d(TAG, "Next refresh in ${intervalMs / 60_000} min")
                delay(intervalMs)
            }
        }
    }

    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    fun refresh() {
        viewModelScope.launch { loadReadings() }
    }

    private suspend fun loadReadings() {
        val ctx = getApplication<Application>()
        if (!CredentialsManager.hasCredentials(ctx)) {
            _uiState.postValue(UiState.NoCredentials)
            return
        }
        _uiState.postValue(UiState.Loading)
        try {
            val readings = repository.fetchReadings()
            _uiState.postValue(UiState.Success(readings))
            _lastUpdated.postValue(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching readings", e)
            _uiState.postValue(UiState.Error(e.message ?: "Unknown error"))
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoRefresh()
    }
}
