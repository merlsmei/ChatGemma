package com.chatgemma.app.ui.screens.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chatgemma.app.data.repository.ModelRepository
import com.chatgemma.app.domain.model.ModelVersion
import com.chatgemma.app.domain.usecase.model.CheckModelUpdatesUseCase
import com.chatgemma.app.domain.usecase.model.DownloadModelUseCase
import com.chatgemma.app.domain.usecase.model.SetActiveModelUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelManagerUiState(
    val models: List<ModelVersion> = emptyList(),
    val isCheckingUpdates: Boolean = false,
    val downloadProgress: Map<String, Int> = emptyMap(),
    val downloadPending: Set<String> = emptySet(),
    val error: String? = null
)

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val downloadModelUseCase: DownloadModelUseCase,
    private val setActiveModelUseCase: SetActiveModelUseCase,
    private val checkModelUpdatesUseCase: CheckModelUpdatesUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelManagerUiState())
    val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()

    init {
        modelRepository.getAllModels()
            .onEach { models -> _uiState.update { it.copy(models = models) } }
            .launchIn(viewModelScope)

        // Auto-fetch on first open if the database is empty
        viewModelScope.launch {
            val cached = modelRepository.getAllModels().first()
            if (cached.isEmpty()) checkForUpdates()
        }
    }

    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(downloadPending = it.downloadPending + modelId) }
            try {
                downloadModelUseCase(modelId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Download failed") }
            } finally {
                _uiState.update { it.copy(downloadPending = it.downloadPending - modelId) }
            }
        }
    }

    fun setActiveModel(modelId: String) {
        viewModelScope.launch {
            try {
                setActiveModelUseCase(modelId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to activate model: ${e.message}") }
            }
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            try {
                modelRepository.deleteModel(modelId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Delete failed: ${e.message}") }
            }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdates = true) }
            try {
                checkModelUpdatesUseCase()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isCheckingUpdates = false) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
