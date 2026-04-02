package com.solosafe.app.ui.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.solosafe.app.data.repository.SoloSafeRepository
import com.solosafe.app.service.HeartbeatManager
import com.solosafe.app.service.SoloSafeService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionUiState(
    val step: Int = 1,
    val sessionType: String? = null,
    val durationHours: Int? = null,
    val preset: String = "WAREHOUSE",
    val isStarting: Boolean = false,
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val app: Application,
    private val repository: SoloSafeRepository,
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = SessionUiState(
                preset = repository.getDefaultPreset(),
            )
        }
    }

    fun selectType(type: String) {
        val duration = when (type) {
            "continua" -> null
            "intervento" -> 1
            else -> null
        }
        _uiState.value = _uiState.value.copy(
            sessionType = type,
            durationHours = duration,
            step = if (type == "continua" || type == "intervento") 3 else 2,
        )
    }

    fun selectDuration(hours: Int?) {
        _uiState.value = _uiState.value.copy(durationHours = hours, step = 3)
    }

    fun startSession() {
        val state = _uiState.value
        val type = state.sessionType ?: return
        _uiState.value = state.copy(isStarting = true)

        viewModelScope.launch {
            repository.startSession(type, state.preset, state.durationHours)
            SoloSafeService.startProtected(app, state.preset)
            HeartbeatManager.scheduleWorkManagerFallback(app, "protected")
        }
    }
}
