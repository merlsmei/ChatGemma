package com.chatgemma.app.ui.screens.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatgemma.app.ai.GemmaInferenceEngine
import com.chatgemma.app.ai.PromptBuilder
import com.chatgemma.app.ai.VideoFrameExtractor
import com.chatgemma.app.data.preferences.AppPreferences
import com.chatgemma.app.data.repository.ModelRepository
import com.chatgemma.app.domain.model.InferenceParams
import com.chatgemma.app.domain.model.Message
import com.chatgemma.app.domain.usecase.context.CalculateContextUsageUseCase
import com.chatgemma.app.domain.usecase.message.GetMessagesUseCase
import com.chatgemma.app.domain.usecase.session.RollbackToMessageUseCase
import com.chatgemma.app.domain.usecase.topic.ArchiveTopicUseCase
import com.chatgemma.app.domain.usecase.topic.AutoTagTopicUseCase
import com.chatgemma.app.domain.usecase.topic.CompressTopicUseCase
import com.chatgemma.app.domain.usecase.topic.SummarizeTopicUseCase
import com.chatgemma.app.service.SpeechService
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val getMessagesUseCase: GetMessagesUseCase,
    private val rollbackUseCase: RollbackToMessageUseCase,
    private val autoTagTopicUseCase: AutoTagTopicUseCase,
    private val compressTopicUseCase: CompressTopicUseCase,
    private val summarizeTopicUseCase: SummarizeTopicUseCase,
    private val archiveTopicUseCase: ArchiveTopicUseCase,
    private val calculateContextUseCase: CalculateContextUsageUseCase,
    private val modelRepository: ModelRepository,
    private val gemmaEngine: GemmaInferenceEngine,
    private val videoFrameExtractor: VideoFrameExtractor,
    private val speechService: SpeechService,
    private val appPreferences: AppPreferences,
    private val gson: Gson
) : ViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""
    private val branchId: String = savedStateHandle["branchId"] ?: ""

    private val _uiState = MutableStateFlow(
        ChatUiState(sessionId = sessionId, branchId = branchId)
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    // Accumulated messages for building the next prompt
    private val messageCache = mutableListOf<Message>()

    init {
        loadMessages()
        loadModel()
        observeSpeech()
    }

    private fun loadMessages() {
        getMessagesUseCase(sessionId, branchId)
            .onEach { messages ->
                messageCache.clear()
                messageCache.addAll(messages)
                _uiState.update { it.copy(messages = messages) }
                updateContextUsage()
            }
            .launchIn(viewModelScope)
    }

    private fun loadModel() {
        viewModelScope.launch {
            val model = modelRepository.getActiveModel() ?: run {
                _uiState.update { it.copy(modelLoadingError = "No model downloaded. Go to Model Manager.") }
                return@launch
            }
            val path = model.localPath ?: run {
                _uiState.update { it.copy(modelLoadingError = "Active model not downloaded yet.") }
                return@launch
            }
            try {
                val params = _uiState.value.inferenceParams.copy(
                    modelId = model.id,
                    maxTokens = 1024
                )
                gemmaEngine.initialize(path, params)
                _uiState.update { it.copy(isModelLoaded = true, modelLoadingError = null, inferenceParams = params) }
            } catch (e: Exception) {
                _uiState.update { it.copy(modelLoadingError = "Failed to load model: ${e.message}") }
            }
        }
    }

    private fun observeSpeech() {
        speechService.recognizedText
            .onEach { text ->
                _uiState.update { it.copy(
                    currentInput = it.currentInput + (if (it.currentInput.isNotEmpty()) " " else "") + text,
                    isVoiceListening = false,
                    partialVoiceText = ""
                )}
            }
            .launchIn(viewModelScope)

        speechService.partialText
            .onEach { partial ->
                _uiState.update { it.copy(partialVoiceText = partial) }
            }
            .launchIn(viewModelScope)

        speechService.isListening
            .onEach { listening ->
                _uiState.update { it.copy(isVoiceListening = listening) }
            }
            .launchIn(viewModelScope)
    }

    fun sendMessage() {
        val state = _uiState.value
        val text = state.currentInput.trim()
        val images = state.attachedImages
        val videoUri = state.attachedVideoUri
        if (text.isEmpty() && images.isEmpty() && videoUri == null) return
        if (!state.isModelLoaded) return

        val now = System.currentTimeMillis()
        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            branchId = branchId,
            role = "user",
            textContent = text,
            mediaType = when {
                videoUri != null -> "video"
                images.isNotEmpty() -> "image"
                else -> null
            },
            mediaUri = videoUri?.toString() ?: images.firstOrNull()?.toString(),
            createdAt = now,
            tokenCount = gemmaEngine.let { (text.length / 4).coerceAtLeast(1) },
            inferenceParamsJson = gson.toJson(state.inferenceParams)
        )

        _uiState.update {
            it.copy(
                currentInput = "",
                attachedImages = emptyList(),
                attachedVideoUri = null,
                isGenerating = true,
                streamingText = ""
            )
        }

        generationJob = viewModelScope.launch {
            try {
                // Build image bitmaps
                val bitmaps = mutableListOf<Bitmap>()
                images.forEach { uri -> uriToBitmap(uri)?.let { bitmaps.add(it) } }
                videoUri?.let { uri ->
                    val frames = videoFrameExtractor.extractFrames(context, uri)
                    bitmaps.addAll(frames)
                }

                val prompt = PromptBuilder.buildChatPrompt(
                    messageCache + userMessage
                )

                val accumulated = StringBuilder()
                gemmaEngine.generateStream(prompt, bitmaps, state.inferenceParams)
                    .catch { e -> _uiState.update { it.copy(error = e.message, isGenerating = false) } }
                    .collect { partial ->
                        accumulated.append(partial)
                        _uiState.update { it.copy(streamingText = accumulated.toString()) }
                    }

                val modelMessage = Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    branchId = branchId,
                    role = "model",
                    textContent = accumulated.toString(),
                    createdAt = System.currentTimeMillis(),
                    tokenCount = (accumulated.length / 4).coerceAtLeast(1),
                    inferenceParamsJson = gson.toJson(state.inferenceParams)
                )

                // Persist both messages (insert via repository would go here in production)
                // For now we trigger auto-tagging and update state
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        messages = it.messages + userMessage + modelMessage
                    )
                }

                // Auto-speak if enabled
                if (_uiState.value.isAutoSpeaking) {
                    speechService.speak(accumulated.toString())
                }

                // Background auto-tag
                autoTagTopicUseCase(sessionId, branchId)
                updateContextUsage()

            } catch (e: Exception) {
                _uiState.update { it.copy(isGenerating = false, error = e.message) }
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        gemmaEngine.cancelGeneration()
        _uiState.update { it.copy(isGenerating = false, streamingText = "") }
    }

    fun rollbackToMessage(messageId: String) {
        viewModelScope.launch {
            val newBranch = rollbackUseCase(sessionId, branchId, messageId)
            _uiState.update { it.copy(showBranchSelector = false) }
            // Navigation to new branch is handled by the screen via onBranchSwitch callback
        }
    }

    fun onTopicCompress(topicId: String) {
        viewModelScope.launch {
            compressTopicUseCase(topicId, branchId)
            updateContextUsage()
        }
    }

    fun onTopicSummarize(topicId: String) {
        viewModelScope.launch {
            summarizeTopicUseCase(topicId, branchId)
        }
    }

    fun onTopicArchive(topicId: String) {
        viewModelScope.launch {
            archiveTopicUseCase(topicId, branchId)
            updateContextUsage()
        }
    }

    fun toggleVoiceInput() {
        if (_uiState.value.isVoiceListening) {
            speechService.stopListening()
        } else {
            speechService.startListening()
        }
    }

    fun toggleAutoSpeak() {
        _uiState.update { it.copy(isAutoSpeaking = !it.isAutoSpeaking) }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(currentInput = text) }
    }

    fun attachImage(uri: Uri) {
        _uiState.update { it.copy(attachedImages = it.attachedImages + uri, attachedVideoUri = null) }
    }

    fun attachVideo(uri: Uri) {
        _uiState.update { it.copy(attachedVideoUri = uri, attachedImages = emptyList()) }
    }

    fun removeAttachment(uri: Uri) {
        _uiState.update { it.copy(attachedImages = it.attachedImages - uri) }
    }

    fun clearVideoAttachment() {
        _uiState.update { it.copy(attachedVideoUri = null) }
    }

    fun setSortMode(mode: SortMode) {
        _uiState.update { it.copy(sortMode = mode) }
    }

    fun setShowBranchSelector(show: Boolean) {
        _uiState.update { it.copy(showBranchSelector = show) }
    }

    fun setShowParamsSheet(show: Boolean) {
        _uiState.update { it.copy(showParamsSheet = show) }
    }

    fun updateInferenceParams(params: InferenceParams) {
        _uiState.update { it.copy(inferenceParams = params) }
        viewModelScope.launch { appPreferences.saveInferenceParams(params) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun updateContextUsage() {
        val usage = calculateContextUseCase(sessionId, branchId)
        _uiState.update {
            it.copy(
                contextUsagePercent = usage,
                showContextAlert = usage >= 0.85f
            )
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) { null }
    }

    override fun onCleared() {
        super.onCleared()
        speechService.release()
    }
}
