package com.chatgemma.app.ui.screens.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chatgemma.app.domain.model.Message
import com.chatgemma.app.ui.screens.chat.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    branchId: String,
    onNavigateUp: () -> Unit,
    onOpenTopicManager: () -> Unit,
    onBranchSwitch: (String) -> Unit,
    onSessionsClick: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(state.messages.size, state.streamingText) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(
                (state.messages.size + (if (state.isGenerating) 1 else 0)).coerceAtLeast(0)
            )
        }
    }

    // Image picker
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris: List<Uri> -> uris.forEach { viewModel.attachImage(it) } }

    // Video picker
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { viewModel.attachVideo(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.sessionTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onSessionsClick) {
                        Icon(Icons.Default.Menu, "Sessions")
                    }
                },
                actions = {
                    // Branch / rollback
                    IconButton(onClick = { viewModel.setShowBranchSelector(true) }) {
                        Icon(Icons.Default.AccountTree, "Branches")
                    }
                    // Topic manager
                    IconButton(onClick = onOpenTopicManager) {
                        Icon(Icons.Default.Label, "Topics")
                    }
                    // Sort toggle
                    IconButton(onClick = {
                        viewModel.setSortMode(
                            if (state.sortMode == SortMode.BY_TIME) SortMode.BY_TOPIC else SortMode.BY_TIME
                        )
                    }) {
                        Icon(
                            if (state.sortMode == SortMode.BY_TIME) Icons.Default.Sort else Icons.Default.Category,
                            "Sort"
                        )
                    }
                    // Inference params
                    IconButton(onClick = { viewModel.setShowParamsSheet(true) }) {
                        Icon(Icons.Default.Tune, "Parameters")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                InputBar(
                    text = state.currentInput,
                    onTextChange = viewModel::updateInput,
                    onSend = viewModel::sendMessage,
                    onVoiceToggle = viewModel::toggleVoiceInput,
                    onAttachImage = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onAttachVideo = {
                        videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                    isListening = state.isVoiceListening,
                    isGenerating = state.isGenerating,
                    onCancelGeneration = viewModel::cancelGeneration,
                    attachedImages = state.attachedImages,
                    attachedVideoUri = state.attachedVideoUri,
                    partialVoiceText = state.partialVoiceText
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Context usage bar
            ContextUsageBar(
                usagePercent = state.contextUsagePercent,
                onManageContext = onOpenTopicManager
            )

            // Model loading error
            state.modelLoadingError?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(error, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f))
                    }
                }
            }

            // Empty state
            if (state.messages.isEmpty() && !state.isGenerating) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Gemma", style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("Start a conversation", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                    }
                }
            } else {
                val sortedMessages = when (state.sortMode) {
                    SortMode.BY_TIME -> state.messages
                    SortMode.BY_TOPIC -> state.messages.sortedWith(
                        compareBy({ it.topicId ?: "zzz" }, { it.createdAt })
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(sortedMessages, key = { it.id }) { msg ->
                        val isLast = msg == sortedMessages.last()
                        MessageBubble(
                            message = msg,
                            topic = msg.topicId?.let { state.topics[it] },
                            isStreaming = isLast && state.isGenerating && msg.role == "model",
                            streamingText = if (isLast && state.isGenerating && msg.role == "model")
                                state.streamingText else "",
                            onLongPress = { viewModel.setShowBranchSelector(true) },
                            onTopicClick = { /* open inline topic actions */ }
                        )
                    }

                    // Streaming indicator when generating and no streaming message yet
                    if (state.isGenerating && state.streamingText.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Generating…", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                            }
                        }
                    }
                }
            }
        }
    }

    // Branch selector sheet
    if (state.showBranchSelector) {
        BranchSelectorSheet(
            branches = state.availableBranches,
            currentBranchId = branchId,
            messages = state.messages,
            onBranchSelect = { branch -> onBranchSwitch(branch.id) },
            onRollbackMessage = { msg -> viewModel.rollbackToMessage(msg.id) },
            onDismiss = { viewModel.setShowBranchSelector(false) }
        )
    }

    // Inference params sheet
    if (state.showParamsSheet) {
        InferenceParamsSheet(
            params = state.inferenceParams,
            onParamsChange = viewModel::updateInferenceParams,
            onDismiss = { viewModel.setShowParamsSheet(false) }
        )
    }

    // Error snackbar
    state.error?.let { error ->
        LaunchedEffect(error) {
            // In production, show a Snackbar here
            viewModel.dismissError()
        }
    }
}
