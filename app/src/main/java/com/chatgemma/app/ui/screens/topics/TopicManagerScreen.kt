package com.chatgemma.app.ui.screens.topics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chatgemma.app.domain.model.ArchivedTopic
import com.chatgemma.app.domain.model.Topic
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicManagerScreen(
    sessionId: String,
    onNavigateUp: () -> Unit,
    viewModel: TopicManagerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Context Manager") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Sort toggle
                    IconButton(onClick = {
                        viewModel.setSortMode(
                            if (state.sortMode == TopicSortMode.BY_TIME) TopicSortMode.BY_LABEL
                            else TopicSortMode.BY_TIME
                        )
                    }) {
                        Icon(
                            if (state.sortMode == TopicSortMode.BY_TIME) Icons.Default.SortByAlpha
                            else Icons.Default.AccessTime,
                            "Sort"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Active topics section
            item {
                Text(
                    "Active Topics",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Manage topics in the current context window",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.6f)
                )
            }

            val sortedTopics = when (state.sortMode) {
                TopicSortMode.BY_TIME -> state.topics.sortedBy { it.createdAt }
                TopicSortMode.BY_LABEL -> state.topics.sortedBy { it.label }
            }

            if (sortedTopics.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            "No active topics yet. Topics are auto-tagged as you chat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            items(sortedTopics, key = { it.id }) { topic ->
                TopicCard(
                    topic = topic,
                    isProcessing = state.processingTopicId == topic.id,
                    onCompress = { viewModel.compressTopic(topic.id) },
                    onSummarize = { viewModel.summarizeTopic(topic.id) },
                    onArchive = { viewModel.archiveTopic(topic.id) },
                    onRename = { newLabel -> viewModel.renameTopic(topic, newLabel) }
                )
            }

            // Archived topics section
            if (state.archivedTopics.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Archived Topics",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Removed from context — restore to bring back",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.6f)
                    )
                }

                items(state.archivedTopics, key = { "archived_${it.id}" }) { archived ->
                    ArchivedTopicCard(
                        archivedTopic = archived,
                        onRestore = { viewModel.restoreArchivedTopic(archived.id) },
                        onDelete = { viewModel.deleteArchivedTopic(archived.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun TopicCard(
    topic: Topic,
    isProcessing: Boolean,
    onCompress: () -> Unit,
    onSummarize: () -> Unit,
    onArchive: () -> Unit,
    onRename: (String) -> Unit
) {
    val color = try { Color(android.graphics.Color.parseColor(topic.colorHex)) }
    catch (e: Exception) { MaterialTheme.colorScheme.primary }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(topic.label) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    topic.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (topic.isAutoTagged) {
                    Text(
                        "auto",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(0.7f)
                    )
                }
                IconButton(onClick = { showRenameDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, "Rename", modifier = Modifier.size(16.dp))
                }
            }

            topic.summary?.let { summary ->
                Spacer(Modifier.height(6.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.7f),
                    maxLines = 2
                )
            }

            Spacer(Modifier.height(12.dp))

            if (isProcessing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Processing…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCompress,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Icon(Icons.Default.Compress, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Compress", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onSummarize,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Icon(Icons.Default.Summarize, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Summarize", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = onArchive,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Archive, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Archive", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Topic") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Topic name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) onRename(renameText.trim())
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun ArchivedTopicCard(
    archivedTopic: ArchivedTopic,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        .format(Date(archivedTopic.archivedAt))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(archivedTopic.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Archived $dateStr · saved ${archivedTopic.tokensSaved} tokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                )
            }
            IconButton(onClick = onRestore) {
                Icon(Icons.Default.Unarchive, "Restore",
                    tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Default.DeleteForever, "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete archive?") },
            text = { Text("\"${archivedTopic.label}\" will be permanently deleted.") },
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
