package com.chatgemma.app.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.domain.model.Branch
import com.chatgemma.app.domain.model.Session
import com.chatgemma.app.domain.usecase.session.CreateSessionUseCase
import com.chatgemma.app.domain.usecase.session.DeleteSessionUseCase
import com.chatgemma.app.domain.usecase.session.GetSessionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionListUiState(
    val sessions: List<Session> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val getSessionsUseCase: GetSessionsUseCase,
    private val createSessionUseCase: CreateSessionUseCase,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionListUiState(isLoading = true))
    val uiState: StateFlow<SessionListUiState> = _uiState.asStateFlow()

    // emits (sessionId, branchId) for navigation
    private val _navigateToChat = MutableSharedFlow<Pair<String, String>>()
    val navigateToChat: SharedFlow<Pair<String, String>> = _navigateToChat.asSharedFlow()

    init {
        getSessionsUseCase()
            .onEach { sessions ->
                _uiState.update { it.copy(sessions = sessions, isLoading = false) }
            }
            .launchIn(viewModelScope)
    }

    fun createNewSession() {
        viewModelScope.launch {
            val (session, branch) = createSessionUseCase()
            _navigateToChat.emit(Pair(session.id, branch.id))
        }
    }

    fun openSession(session: Session) {
        viewModelScope.launch {
            val branch = chatRepository.getMainBranch(session.id)
            if (branch != null) {
                _navigateToChat.emit(Pair(session.id, branch.id))
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            deleteSessionUseCase(sessionId)
        }
    }
}
