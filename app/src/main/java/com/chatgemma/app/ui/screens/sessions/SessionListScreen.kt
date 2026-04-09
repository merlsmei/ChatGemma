package com.chatgemma.app.ui.screens.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chatgemma.app.domain.model.Session
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    onSessionClick: (sessionId: String, branchId: String) -> Unit,
    onSettingsClick: () -> Unit,
    onModelsClick: () -> Unit,
    viewModel: SessionListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigate when new session created
    LaunchedEffect(Unit) {
        viewModel.navigateToChat.collect { (sessionId, branchId) ->
            onSessionClick(sessionId, branchId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ChatGemma") },
                actions = {
                    IconButton(onClick = onModelsClick) {
                        Icon(Icons.Default.Memory, "Models")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::createNewSession,
                icon = { Icon(Icons.Default.Add, "New chat") },
                text = { Text("New Chat") },
                containerColor = MaterialTheme.colorScheme.primary
            )
        }
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (state.sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.ChatBubble,
                        null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.3f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No conversations yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to start a new chat",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(state.sessions, key = { it.id }) { session ->
                    SessionItem(
                        session = session,
                        onClick = { viewModel.openSession(session) },
                        onDelete = { viewModel.deleteSession(session.id) }
                    )
                    HorizontalDivider(thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(0.3f))
                }
            }
        }
    }
}

@Composable
fun SessionItem(
    session: Session,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val dateStr = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(session.updatedAt))

    ListItem(
        headlineContent = {
            Text(
                session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            Text(
                dateStr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
            )
        },
        leadingContent = {
            Icon(
                Icons.Default.ChatBubble,
                null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Default.DeleteOutline, "Delete",
                    tint = MaterialTheme.colorScheme.onSurface.copy(0.4f))
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete conversation?") },
            text = { Text("This will permanently delete \"${session.title}\" and all its messages.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
