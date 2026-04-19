package com.chatgemma.app.ui.screens.chat

import android.net.Uri
import com.chatgemma.app.domain.model.Branch
import com.chatgemma.app.domain.model.InferenceParams
import com.chatgemma.app.domain.model.Message
import com.chatgemma.app.domain.model.Topic

enum class SortMode { BY_TIME, BY_TOPIC }

data class ChatUiState(
    val sessionId: String = "",
    val branchId: String = "",
    val sessionTitle: String = "New Chat",
    val messages: List<Message> = emptyList(),
    val topics: Map<String, Topic> = emptyMap(),  // topicId -> Topic
    val currentInput: String = "",
    val attachedImages: List<Uri> = emptyList(),
    val attachedVideoUri: Uri? = null,
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val contextUsagePercent: Float = 0f,
    val showContextAlert: Boolean = false,
    val inferenceParams: InferenceParams = InferenceParams(),
    val isVoiceListening: Boolean = false,
    val partialVoiceText: String = "",
    val error: String? = null,
    val availableBranches: List<Branch> = emptyList(),
    val sortMode: SortMode = SortMode.BY_TIME,
    val showBranchSelector: Boolean = false,
    val showParamsSheet: Boolean = false,
    val isModelLoaded: Boolean = false,
    val modelLoadingError: String? = null,
    val isAutoSpeaking: Boolean = false,
    val isUsingGpu: Boolean = false
)
