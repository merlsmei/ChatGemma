package com.chatgemma.app.ui.screens.topics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.data.repository.TopicRepository
import com.chatgemma.app.domain.model.ArchivedTopic
import com.chatgemma.app.domain.model.Topic
import com.chatgemma.app.domain.usecase.topic.ArchiveTopicUseCase
import com.chatgemma.app.domain.usecase.topic.CompressTopicUseCase
import com.chatgemma.app.domain.usecase.topic.DeleteArchivedTopicUseCase
import com.chatgemma.app.domain.usecase.topic.RestoreArchivedTopicUseCase
import com.chatgemma.app.domain.usecase.topic.SummarizeTopicUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TopicSortMode { BY_TIME, BY_LABEL }

data class TopicManagerUiState(
    val topics: List<Topic> = emptyList(),
    val archivedTopics: List<ArchivedTopic> = emptyList(),
    val sortMode: TopicSortMode = TopicSortMode.BY_TIME,
    val isLoading: Boolean = false,
    val processingTopicId: String? = null,
    val error: String? = null
)

@HiltViewModel
class TopicManagerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val topicRepository: TopicRepository,
    private val chatRepository: ChatRepository,
    private val compressTopicUseCase: CompressTopicUseCase,
    private val summarizeTopicUseCase: SummarizeTopicUseCase,
    private val archiveTopicUseCase: ArchiveTopicUseCase,
    private val restoreArchivedTopicUseCase: RestoreArchivedTopicUseCase,
    private val deleteArchivedTopicUseCase: DeleteArchivedTopicUseCase
) : ViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""

    private val _uiState = MutableStateFlow(TopicManagerUiState(isLoading = true))
    val uiState: StateFlow<TopicManagerUiState> = _uiState.asStateFlow()

    // branchId needed for topic operations; loaded from main branch
    private var currentBranchId = ""

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            currentBranchId = chatRepository.getMainBranch(sessionId)?.id ?: ""
        }
        combine(
            topicRepository.getTopicsForSession(sessionId),
            topicRepository.getArchivedTopics(sessionId)
        ) { topics, archived ->
            topics to archived
        }.onEach { (topics, archived) ->
            _uiState.update { it.copy(topics = topics, archivedTopics = archived, isLoading = false) }
        }.launchIn(viewModelScope)
    }

    fun setSortMode(mode: TopicSortMode) {
        _uiState.update { it.copy(sortMode = mode) }
    }

    fun compressTopic(topicId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processingTopicId = topicId) }
            try {
                compressTopicUseCase(topicId, currentBranchId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(processingTopicId = null) }
            }
        }
    }

    fun summarizeTopic(topicId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processingTopicId = topicId) }
            try {
                summarizeTopicUseCase(topicId, currentBranchId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(processingTopicId = null) }
            }
        }
    }

    fun archiveTopic(topicId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processingTopicId = topicId) }
            try {
                archiveTopicUseCase(topicId, currentBranchId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(processingTopicId = null) }
            }
        }
    }

    fun restoreArchivedTopic(archivedTopicId: String) {
        viewModelScope.launch {
            try {
                restoreArchivedTopicUseCase(archivedTopicId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteArchivedTopic(archivedTopicId: String) {
        viewModelScope.launch {
            deleteArchivedTopicUseCase(archivedTopicId)
        }
    }

    fun renameTopic(topic: Topic, newLabel: String) {
        viewModelScope.launch {
            topicRepository.updateTopic(topic.copy(label = newLabel, isAutoTagged = false))
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
