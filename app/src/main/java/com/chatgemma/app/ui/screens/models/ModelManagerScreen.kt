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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chatgemma.app.domain.model.ModelVersion
import kotlin.math.roundToInt

// ── Generation grouping ──────────────────────────────────────────────────────

private data class GenerationGroup(
    val generation: Int,
    val label: String,
    val models: List<ModelVersion>
)

private fun List<ModelVersion>.toGenerationGroups(): List<GenerationGroup> =
    groupBy { it.gemmaGeneration }
        .entries
        // Descending: 4, 3, 2, 1; unknown (0) always last
        .sortedWith(compareByDescending { if (it.key == 0) -1 else it.key })
        .map { (gen, models) ->
            GenerationGroup(
                generation = gen,
                label = when (gen) {
                    4    -> "Gemma 4"
                    3    -> "Gemma 3"
                    2    -> "Gemma 2"
                    1    -> "Gemma 1"
                    else -> "Other Models"
                },
                // Within each generation: smallest first (unknown size last)
                models = models.sortedBy { m ->
                    if (m.sizeBytes > 0L) m.sizeBytes else Long.MAX_VALUE
                }
            )
        }

private fun formatContextLength(ctx: Int): String = when {
    ctx >= 1_000_000 -> "${ctx / 1_000_000}M ctx"
    ctx >= 1_024     -> "${ctx / 1_024}k ctx"
    else             -> "$ctx ctx"
}

private fun formatSize(model: ModelVersion): String = when {
    model.sizeBytes <= 0L -> "size unknown"
    model.sizeGb >= 1f    -> "${"%.1f".format(model.sizeGb)} GB"
    else                  -> "${model.sizeMb.roundToInt()} MB"
}

private fun formatRam(model: ModelVersion): String? {
    if (model.sizeBytes <= 0L) return null
    return "~${"%.1f".format(model.ramRequiredGb)} GB RAM"
}

// ── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    onNavigateUp: () -> Unit,
    viewModel: ModelManagerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val (downloaded, available) = remember(state.models) {
        state.models.partition { it.isDownloaded }
    }
    val downloadedGroups = remember(downloaded) { downloaded.toGenerationGroups() }
    val availableGroups  = remember(available)  { available.toGenerationGroups() }

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
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 16.dp)
                        )
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
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Info banner
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Info, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp).padding(top = 1.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "Download a Gemma model to start chatting. Models are stored on your device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Google Official models are verified releases from google/ on HuggingFace. " +
                                "Mobile Ready models run on phones with 6 GB+ RAM.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
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

            // ── Downloaded section ──────────────────────────────────────────
            if (downloaded.isNotEmpty()) {
                item {
                    Text(
                        "Downloaded (${downloaded.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                downloadedGroups.forEach { group ->
                    if (downloadedGroups.size > 1) {
                        item(key = "dl_hdr_${group.generation}") {
                            GenerationHeader(group.label)
                        }
                    }
                    items(group.models, key = { "dl_${it.id}" }) { model ->
                        ModelCard(
                            model = model,
                            downloadProgress = state.downloadProgress[model.id],
                            onDownload  = { viewModel.downloadModel(model.id) },
                            onActivate  = { viewModel.setActiveModel(model.id) },
                            onDelete    = { viewModel.deleteModel(model.id) }
                        )
                    }
                }
            }

            // ── Available section ───────────────────────────────────────────
            if (available.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Available to Download (${available.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                availableGroups.forEach { group ->
                    item(key = "av_hdr_${group.generation}") {
                        GenerationHeader(group.label, count = group.models.size)
                    }
                    items(group.models, key = { "av_${it.id}" }) { model ->
                        ModelCard(
                            model = model,
                            downloadProgress = state.downloadProgress[model.id],
                            onDownload  = { viewModel.downloadModel(model.id) },
                            onActivate  = { viewModel.setActiveModel(model.id) },
                            onDelete    = { viewModel.deleteModel(model.id) }
                        )
                    }
                }
            }
        }
    }
}

// ── Generation header ─────────────────────────────────────────────────────────

@Composable
private fun GenerationHeader(label: String, count: Int? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (count != null) "$label  ($count)" else label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

// ── Model card ────────────────────────────────────────────────────────────────

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
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            // Row 1: name + active indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    model.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (model.isActive) {
                    Icon(
                        Icons.Default.CheckCircle, "Active",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp).size(20.dp)
                    )
                }
            }

            // Row 2: source / mobile badges
            if (model.source == "google" || model.isMobileSuitable) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (model.source == "google") {
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    "Google Official",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            icon = {
                                Icon(
                                    Icons.Default.Verified, null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                iconContentColor = MaterialTheme.colorScheme.secondary
                            )
                        )
                    }
                    if (model.isMobileSuitable) {
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    "Mobile Ready",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            icon = {
                                Icon(
                                    Icons.Default.PhoneAndroid, null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF1B5E20).copy(alpha = 0.15f),
                                labelColor = Color(0xFF2E7D32),
                                iconContentColor = Color(0xFF2E7D32)
                            )
                        )
                    }
                }
            }

            // Row 3: spec chips
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(model.quantization.uppercase(), style = MaterialTheme.typography.labelSmall) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text(formatContextLength(model.contextLength), style = MaterialTheme.typography.labelSmall) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text(formatSize(model), style = MaterialTheme.typography.labelSmall) }
                )
                formatRam(model)?.let { ram ->
                    AssistChip(
                        onClick = {},
                        label = { Text(ram, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // Row 4: download progress
            downloadProgress?.let { progress ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Downloading… $progress%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Row 5: action buttons
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
