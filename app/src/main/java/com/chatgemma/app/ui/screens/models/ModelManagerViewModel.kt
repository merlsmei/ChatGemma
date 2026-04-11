package com.chatgemma.app.ui.screens.models

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chatgemma.app.data.repository.ModelRepository
import com.chatgemma.app.domain.model.ModelVersion
import com.chatgemma.app.domain.usecase.model.CheckModelUpdatesUseCase
import com.chatgemma.app.domain.usecase.model.DownloadModelUseCase
import com.chatgemma.app.domain.usecase.model.SetActiveModelUseCase
import com.chatgemma.app.worker.ModelDownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelManagerUiState(
    val models: List<ModelVersion> = emptyList(),
    val isCheckingUpdates: Boolean = false,
    val downloadProgress: Map<String, Int> = emptyMap(),
    val downloadPending: Set<String> = emptySet(),
    val error: String? = null,
    val modelsDirectory: String = "",
    val linkingModelId: String? = null,     // model currently awaiting file pick
    val localFiles: List<String> = emptyList()  // files in models dir for picker
)

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository,
    private val downloadModelUseCase: DownloadModelUseCase,
    private val setActiveModelUseCase: SetActiveModelUseCase,
    private val checkModelUpdatesUseCase: CheckModelUpdatesUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelManagerUiState())
    val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()

    init {
        val modelsDir = modelRepository.getModelsDirectory()
        _uiState.update { it.copy(modelsDirectory = modelsDir.absolutePath) }

        modelRepository.getAllModels()
            .onEach { models -> _uiState.update { it.copy(models = models) } }
            .launchIn(viewModelScope)

        // Auto-fetch on first open if the database is empty
        viewModelScope.launch {
            val cached = modelRepository.getAllModels().first()
            if (cached.isEmpty()) checkForUpdates()
        }

        // Re-observe any in-progress downloads (survives navigation away and back)
        resumeActiveDownloads()
    }

    private val observedModelIds = mutableSetOf<String>()

    private fun resumeActiveDownloads() {
        val workManager = WorkManager.getInstance(context)
        viewModelScope.launch {
            // Wait for models to load, then check for active downloads
            modelRepository.getAllModels().first { it.isNotEmpty() }.forEach { model ->
                if (model.id !in observedModelIds) {
                    val workInfos = workManager.getWorkInfosByTagFlow("download_${model.id}")
                        .first()
                    val isActive = workInfos.any {
                        it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                    }
                    if (isActive) {
                        observeDownloadProgress(model.id)
                    }
                }
            }
        }
    }

    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(downloadPending = it.downloadPending + modelId) }
            try {
                downloadModelUseCase(modelId)
                observeDownloadProgress(modelId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = e.message ?: "Download failed",
                        downloadPending = it.downloadPending - modelId
                    )
                }
            }
        }
    }

    /** Begin local-file linking flow: show the file picker for this model. */
    fun startLinkLocal(modelId: String) {
        val dir = modelRepository.getModelsDirectory()
        val files = dir.listFiles()
            ?.filter { it.extension.lowercase() in listOf("gguf", "ggml", "task") }
            ?.map { it.absolutePath }
            ?: emptyList()
        _uiState.update { it.copy(linkingModelId = modelId, localFiles = files) }
    }

    fun cancelLinkLocal() {
        _uiState.update { it.copy(linkingModelId = null, localFiles = emptyList()) }
    }

    fun confirmLinkLocal(filePath: String) {
        val modelId = _uiState.value.linkingModelId ?: return
        viewModelScope.launch {
            val conflict = modelRepository.linkLocalFile(modelId, filePath)
            if (conflict != null) {
                _uiState.update {
                    it.copy(
                        error = "File already linked to \"$conflict\"",
                        linkingModelId = null,
                        localFiles = emptyList()
                    )
                }
            } else {
                _uiState.update { it.copy(linkingModelId = null, localFiles = emptyList()) }
            }
        }
    }

    private fun observeDownloadProgress(modelId: String) {
        if (!observedModelIds.add(modelId)) return  // already observing
        val workManager = WorkManager.getInstance(context)
        workManager.getWorkInfosByTagFlow("download_$modelId")
            .onEach { workInfos ->
                val workInfo = workInfos.firstOrNull() ?: return@onEach
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getInt(
                            ModelDownloadWorker.KEY_PROGRESS, 0
                        )
                        val current = _uiState.value.downloadProgress[modelId]
                        if (current != progress || modelId in _uiState.value.downloadPending) {
                            _uiState.update {
                                it.copy(
                                    downloadProgress = it.downloadProgress + (modelId to progress),
                                    downloadPending = it.downloadPending - modelId
                                )
                            }
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        observedModelIds.remove(modelId)
                        _uiState.update {
                            it.copy(
                                downloadProgress = it.downloadProgress - modelId,
                                downloadPending = it.downloadPending - modelId
                            )
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        observedModelIds.remove(modelId)
                        val errorMsg = workInfo.outputData.getString(
                            ModelDownloadWorker.KEY_ERROR
                        ) ?: "Download failed"
                        _uiState.update {
                            it.copy(
                                error = errorMsg,
                                downloadProgress = it.downloadProgress - modelId,
                                downloadPending = it.downloadPending - modelId
                            )
                        }
                    }
                    WorkInfo.State.CANCELLED -> {
                        observedModelIds.remove(modelId)
                        _uiState.update {
                            it.copy(
                                downloadProgress = it.downloadProgress - modelId,
                                downloadPending = it.downloadPending - modelId
                            )
                        }
                    }
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        _uiState.update {
                            it.copy(
                                downloadProgress = it.downloadProgress + (modelId to 0),
                                downloadPending = it.downloadPending - modelId
                            )
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
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
