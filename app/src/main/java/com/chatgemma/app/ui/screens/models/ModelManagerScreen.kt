package com.chatgemma.app.ui.screens.models

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
import com.chatgemma.app.domain.model.ModelVersion
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    onNavigateUp: () -> Unit,
    viewModel: ModelManagerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Manager") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (state.isCheckingUpdates) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 16.dp))
                    } else {
                        IconButton(onClick = viewModel::checkForUpdates) {
                            Icon(Icons.Default.Refresh, "Check for updates")
                        }
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
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Download a Gemma model to start chatting. Models are stored locally on your device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            if (state.models.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Fetching available models…",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            val (downloaded, available) = state.models.partition { it.isDownloaded }

            if (downloaded.isNotEmpty()) {
                item {
                    Text("Downloaded", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                items(downloaded, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        downloadProgress = state.downloadProgress[model.id],
                        onDownload = { viewModel.downloadModel(model.id) },
                        onActivate = { viewModel.setActiveModel(model.id) },
                        onDelete = { viewModel.deleteModel(model.id) }
                    )
                }
            }

            if (available.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Available to Download",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary)
                }
                items(available, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        downloadProgress = state.downloadProgress[model.id],
                        onDownload = { viewModel.downloadModel(model.id) },
                        onActivate = { viewModel.setActiveModel(model.id) },
                        onDelete = { viewModel.deleteModel(model.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun ModelCard(
    model: ModelVersion,
    downloadProgress: Int?,
    onDownload: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (model.isActive)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = { Text(model.quantization, style = MaterialTheme.typography.labelSmall) }
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("${model.contextLength / 1024}k ctx", style = MaterialTheme.typography.labelSmall) }
                        )
                        val sizeText = if (model.sizeGb >= 1f)
                            "${"%.1f".format(model.sizeGb)} GB"
                        else "${model.sizeMb.roundToInt()} MB"
                        AssistChip(
                            onClick = {},
                            label = { Text(sizeText, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                if (model.isActive) {
                    Icon(Icons.Default.CheckCircle, "Active",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp))
                }
            }

            // Download progress
            downloadProgress?.let { progress ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Downloading… $progress%", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp))
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!model.isDownloaded) {
                    Button(
                        onClick = onDownload,
                        enabled = downloadProgress == null
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Download")
                    }
                } else {
                    if (!model.isActive) {
                        Button(onClick = onActivate) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Use")
                        }
                    }
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete model?") },
            text = { Text("\"${model.displayName}\" will be deleted from your device. You can re-download it later.") },
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
