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
import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.data.repository.ModelRepository
import com.chatgemma.app.domain.model.InferenceParams
import com.chatgemma.app.domain.model.Message
import com.chatgemma.app.domain.usecase.context.CalculateContextUsageUseCase
import com.chatgemma.app.domain.usecase.context.CompressContextUseCase
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val compressContextUseCase: CompressContextUseCase,
    private val chatRepository: ChatRepository,
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
    private var compressionJob: Job? = null

    init {
        loadSession()
        loadCompressionPrefs()
        loadMessages()
        checkGpuCrashThenLoadModel()
        observeSpeech()
    }

    private fun loadCompressionPrefs() {
        viewModelScope.launch {
            val enabled = appPreferences.autoCompressEnabled.first()
            val threshold = appPreferences.compressionThreshold.first()
            val contextSize = appPreferences.contextSize.first()
            _uiState.update {
                it.copy(
                    autoCompressEnabled = enabled,
                    compressionThreshold = threshold,
                    inferenceParams = it.inferenceParams.copy(contextSize = contextSize)
                )
            }
        }
    }

    private fun loadSession() {
        viewModelScope.launch {
            chatRepository.getSession(sessionId)?.let { session ->
                _uiState.update {
                    it.copy(
                        sessionTitle = session.title,
                        systemPrompt = session.systemPrompt
                    )
                }
            }
        }
    }

    private fun checkGpuCrashThenLoadModel() {
        viewModelScope.launch {
            restoreSavedInferenceParams()
            val crashed = appPreferences.checkAndResetGpuCrash()
            if (crashed) {
                _uiState.update {
                    it.copy(
                        inferenceParams = it.inferenceParams.copy(gpuLayers = 0),
                        error = "GPU acceleration crashed on last run and has been disabled. " +
                            "You can re-enable it in the inference settings."
                    )
                }
            }
            loadModel()
        }
    }

    /**
     * Restore the persisted sampler + GPU settings before any GPU decision.
     * Without this, InferenceParams defaults (gpuLayers=99) win on every
     * launch, so a crash-triggered GPU disable never outlives the session and
     * sampler customizations are lost on restart.
     */
    private suspend fun restoreSavedInferenceParams() {
        val temperature = appPreferences.temperature.first()
        val topK = appPreferences.topK.first()
        val topP = appPreferences.topP.first()
        val gpuLayers = appPreferences.gpuLayers.first()
        _uiState.update {
            it.copy(
                inferenceParams = it.inferenceParams.copy(
                    temperature = temperature,
                    topK = topK,
                    topP = topP,
                    gpuLayers = gpuLayers
                )
            )
        }
    }

    private fun loadMessages() {
        getMessagesUseCase(sessionId, branchId)
            .onEach { messages ->
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
                val stored = _uiState.value.inferenceParams
                // Google AI Edge Gallery ships Gemma (LiteRT) with topK=64,
                // topP=0.95, temperature=1.0, maxTokens=4000 — apply the same
                // defaults so output quality matches, but only while the user
                // hasn't customized the sampler away from the app defaults.
                val isUntouchedSampler = stored.temperature == 0.8f &&
                    stored.topK == 40 && stored.topP == 0.95f
                val tuned = if (model.modelFormat == "LiteRT" && isUntouchedSampler) {
                    stored.copy(temperature = 1.0f, topK = 64, topP = 0.95f)
                } else stored
                val params = tuned.copy(
                    modelId = model.id,
                    maxTokens = if (model.modelFormat == "LiteRT") 4000 else 1024,
                    modelFormat = model.modelFormat,
                    // Read directly from prefs so the engine always gets the
                    // persisted value even if the async pref load hasn't landed
                    contextSize = appPreferences.contextSize.first()
                )
                gemmaEngine.initialize(path, params)
                val requestedGpu = params.gpuLayers > 0
                val actualGpu = gemmaEngine.isUsingGpu.value
                _uiState.update { it.copy(
                    isModelLoaded = true,
                    modelLoadingError = if (requestedGpu && !actualGpu) {
                        "GPU acceleration isn't supported for this model on this device — using CPU instead."
                    } else null,
                    inferenceParams = params,
                    isUsingGpu = actualGpu
                ) }
                // A relaunched session may already be over the threshold
                updateContextUsage()
                maybeCompressContext()
            } catch (e: Exception) {
                _uiState.update { it.copy(modelLoadingError = e.message ?: "Failed to load model") }
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
            // Provisional estimate; replaced with a real tokenizer count when
            // the message is persisted below. length/4 undercounts CJK text
            // (≈1 token per character, not per 4) by a factor of 4-8, which
            // made the context bar read ~20% when the window was nearly full.
            tokenCount = (text.length / 4).coerceAtLeast(1),
            inferenceParamsJson = gson.toJson(state.inferenceParams)
        )

        _uiState.update {
            it.copy(
                currentInput = "",
                attachedImages = emptyList(),
                attachedVideoUri = null,
                isGenerating = true,
                streamingText = "",
                messages = it.messages + userMessage
            )
        }

        // History snapshot taken before the user message was appended, so the
        // prompt can't double-count it
        val history = state.messages

        generationJob = viewModelScope.launch {
            try {
                // Persist the user message immediately so it survives even if
                // generation fails or is cancelled
                chatRepository.insertMessage(
                    userMessage.copy(
                        tokenCount = gemmaEngine.countTokens(text).coerceAtLeast(1)
                    )
                )

                // Build image bitmaps
                val bitmaps = mutableListOf<Bitmap>()
                images.forEach { uri -> uriToBitmap(uri)?.let { bitmaps.add(it) } }
                videoUri?.let { uri ->
                    val frames = videoFrameExtractor.extractFrames(context, uri)
                    bitmaps.addAll(frames)
                }

                // Build prompt with image descriptions so the model knows what's attached
                val imageDesc = bitmaps.mapIndexed { i, bmp ->
                    "[Image ${i + 1}: ${bmp.width}x${bmp.height}px]"
                }.joinToString("\n")
                val promptUserMessage = if (imageDesc.isNotEmpty()) {
                    userMessage.copy(textContent = "$imageDesc\n${userMessage.textContent ?: ""}")
                } else {
                    userMessage
                }
                val prompt = PromptBuilder.buildChatPrompt(
                    history = history + promptUserMessage,
                    systemPrompt = state.systemPrompt?.takeIf { it.isNotBlank() }
                )

                // Arm the GPU crash sentinel only when the engine actually runs
                // on the GPU — a requested-but-fallen-back-to-CPU engine must
                // not blame the GPU for a mid-generation process death.
                val gpuActive = state.inferenceParams.gpuLayers > 0 && gemmaEngine.isUsingGpu.value
                if (gpuActive) appPreferences.setGpuSentinel(true)

                val accumulated = StringBuilder()
                var streamError: String? = null
                try {
                    gemmaEngine.generateStream(prompt, bitmaps, state.inferenceParams)
                        .catch { e -> streamError = e.message ?: "Generation failed" }
                        .collect { partial ->
                            accumulated.append(partial)
                            _uiState.update { it.copy(streamingText = stripControlTokens(accumulated.toString())) }
                        }
                } finally {
                    // Clear the sentinel on every in-process outcome — success,
                    // error, or cancellation (stop button / leaving the screen).
                    // Only a real native crash skips this, so the sentinel no
                    // longer disables GPU just because a generation was
                    // interrupted. NonCancellable so the DataStore write still
                    // runs when this coroutine is being cancelled.
                    if (gpuActive) withContext(NonCancellable) {
                        appPreferences.setGpuSentinel(false)
                    }
                }

                val cleanResponse = stripControlTokens(accumulated.toString())
                val responseText = when {
                    streamError != null -> "[Error: $streamError]"
                    cleanResponse.isEmpty() -> "[No response generated. The model may not support this prompt format — try adjusting inference parameters.]"
                    else -> cleanResponse
                }

                val modelMessage = Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    branchId = branchId,
                    role = "model",
                    textContent = responseText,
                    createdAt = System.currentTimeMillis(),
                    tokenCount = gemmaEngine.countTokens(responseText).coerceAtLeast(1),
                    inferenceParamsJson = gson.toJson(state.inferenceParams)
                )

                chatRepository.insertMessage(modelMessage)

                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        // The DB flow may have already emitted the inserted message
                        messages = if (it.messages.any { m -> m.id == modelMessage.id })
                            it.messages else it.messages + modelMessage
                    )
                }

                // Auto-speak if enabled
                if (_uiState.value.isAutoSpeaking && streamError == null && cleanResponse.isNotEmpty()) {
                    speechService.speak(cleanResponse)
                }

                // Background auto-tag (isolated so failures don't crash the chat)
                viewModelScope.launch {
                    try {
                        autoTagTopicUseCase(sessionId, branchId)
                    } catch (_: Exception) { }
                }
                updateContextUsage()
                maybeCompressContext()

            } catch (e: CancellationException) {
                // User stopped generation or left the screen — not an error.
                _uiState.update { it.copy(isGenerating = false, streamingText = "") }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isGenerating = false, error = e.message) }
            }
        }
    }

    /**
     * Kicks off background context compression when usage crosses the
     * configured threshold. The engine's internal mutex serializes it with
     * any chat generation, so it never runs concurrently with a reply.
     */
    private fun maybeCompressContext() {
        val state = _uiState.value
        if (!state.autoCompressEnabled) return
        if (state.isCompressingContext || state.isGenerating) return
        if (!state.isModelLoaded) return
        if (state.contextUsagePercent < state.compressionThreshold) return

        compressionJob = viewModelScope.launch {
            _uiState.update { it.copy(isCompressingContext = true) }
            try {
                val compressed = compressContextUseCase(
                    sessionId, branchId,
                    contextSize = state.inferenceParams.contextSize
                )
                if (compressed) updateContextUsage()
            } catch (_: Exception) {
                // Compression is best-effort; never surface a crash for it
            } finally {
                _uiState.update { it.copy(isCompressingContext = false) }
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

    fun setShowSystemPromptDialog(show: Boolean) {
        _uiState.update { it.copy(showSystemPromptDialog = show) }
    }

    fun updateSystemPrompt(prompt: String) {
        val normalized = prompt.trim().ifEmpty { null }
        _uiState.update { it.copy(systemPrompt = normalized, showSystemPromptDialog = false) }
        viewModelScope.launch {
            chatRepository.updateSessionSystemPrompt(sessionId, normalized)
        }
    }

    fun updateInferenceParams(params: InferenceParams) {
        val current = _uiState.value.inferenceParams
        val needsReload = params.gpuLayers != current.gpuLayers ||
            params.contextSize != current.contextSize
        _uiState.update { it.copy(inferenceParams = params) }
        viewModelScope.launch {
            // Persist before reloading — loadModel reads contextSize back from prefs
            appPreferences.saveInferenceParams(params)
            if (needsReload) loadModel()
        }
    }

    fun setAutoCompressEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoCompressEnabled = enabled) }
        viewModelScope.launch { appPreferences.setAutoCompressEnabled(enabled) }
        if (enabled) maybeCompressContext()
    }

    fun setCompressionThreshold(threshold: Float) {
        val clamped = threshold.coerceIn(0.3f, 0.95f)
        _uiState.update { it.copy(compressionThreshold = clamped) }
        viewModelScope.launch { appPreferences.setCompressionThreshold(clamped) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun updateContextUsage() {
        val usage = calculateContextUseCase(
            sessionId, branchId,
            configuredContextSize = _uiState.value.inferenceParams.contextSize
        )
        _uiState.update {
            it.copy(
                contextUsagePercent = usage,
                showContextAlert = usage >= 0.85f
            )
        }
    }

    private fun stripControlTokens(text: String): String =
        text.replace("<end_of_turn>", "")
            .replace("<start_of_turn>", "")
            .replace("<eos>", "")
            .replace("<bos>", "")
            .trim()

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
        generationJob?.cancel()
        compressionJob?.cancel()
        speechService.release()
    }
}
