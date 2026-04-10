package com.chatgemma.app.ui.screens.models

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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

    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    // File picker dialog for local model linking
    if (state.linkingModelId != null) {
        LocalFilePickerDialog(
            files = state.localFiles,
            modelsDir = state.modelsDirectory,
            onSelect = viewModel::confirmLinkLocal,
            onDismiss = viewModel::cancelLinkLocal
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Model Manager") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val dir = java.io.File(state.modelsDirectory)
                        dir.mkdirs()
                        try {
                            val uri = FileProvider.getUriForFile(
                                ctx, "${ctx.packageName}.fileprovider", dir
                            )
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "resource/folder")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                        } catch (_: Exception) {
                            // Fallback: open generic file manager
                            try {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(
                                        Uri.parse("content://com.android.externalstorage.documents/document/primary:Android%2Fdata"),
                                        "vnd.android.document/directory"
                                    )
                                )
                            } catch (_: Exception) { /* no file manager available */ }
                        }
                    }) {
                        Icon(Icons.Default.FolderOpen, "Open models folder")
                    }
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
                                "Download a Gemma model to start chatting. GGUF and .task formats are supported.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Mobile Ready = fits on a 12 GB device. Q4_K_M GGUF is downloaded when available.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Models folder: ${state.modelsDirectory}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
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
                            downloadPending = model.id in state.downloadPending,
                            onDownload  = { viewModel.downloadModel(model.id) },
                            onActivate  = { viewModel.setActiveModel(model.id) },
                            onDelete    = { viewModel.deleteModel(model.id) },
                            onLinkLocal = { viewModel.startLinkLocal(model.id) }
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
                            downloadPending = model.id in state.downloadPending,
                            onDownload  = { viewModel.downloadModel(model.id) },
                            onActivate  = { viewModel.setActiveModel(model.id) },
                            onDelete    = { viewModel.deleteModel(model.id) },
                            onLinkLocal = { viewModel.startLinkLocal(model.id) }
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
    downloadPending: Boolean = false,
    onDownload: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onLinkLocal: () -> Unit = {}
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

            Spacer(Modifier.height(10.dp))

            // Row 4: HuggingFace URL (for undownloaded models)
            if (!model.isDownloaded && model.downloadUrl.isNotBlank()) {
                val linkCtx = LocalContext.current
                Text(
                    text = model.downloadUrl,
                    style = MaterialTheme.typography.labelSmall.copy(
                        textDecoration = TextDecoration.Underline
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        linkCtx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.downloadUrl)))
                    }
                )
                Spacer(Modifier.height(6.dp))
            }

            // Row 5: action buttons / progress bar
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!model.isDownloaded) {
                    if (downloadProgress != null) {
                        DownloadProgressButton(progress = downloadProgress)
                    } else if (downloadPending) {
                        DownloadProgressButton(progress = 0, preparing = true)
                    } else {
                        Button(onClick = onDownload) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Download")
                        }
                        OutlinedButton(onClick = onLinkLocal) {
                            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Load Local")
                        }
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

// ── Download progress button ─────────────────────────────────────────────────

@Composable
private fun DownloadProgressButton(
    progress: Int,
    preparing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    val isIndeterminate = preparing || progress == 0

    // Determinate fill animation
    val animatedProgress by animateFloatAsState(
        targetValue = progress / 100f,
        animationSpec = tween(durationMillis = 300),
        label = "download_progress"
    )

    // Indeterminate shimmer: a narrow bar sweeps back and forth
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_offset"
    )

    Box(
        modifier = modifier
            .height(40.dp)
            .widthIn(min = 140.dp)
            .clip(shape)
            .background(Color(0xFF1A237E).copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        if (isIndeterminate) {
            // Shimmer bar: 25% width sliding across
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = 0.25f)
                    .offset(x = ((shimmerOffset * 140 * 0.75f).dp))
                    .clip(shape)
                    .background(Color(0xFF1976D2).copy(alpha = 0.6f))
            )
        } else {
            // Determinate fill layer — sweeps left to right
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = animatedProgress)
                    .clip(shape)
                    .background(Color(0xFF1976D2))
                    .align(Alignment.CenterStart)
            )
        }
        // Text overlay
        Text(
            text = when {
                preparing -> "Preparing…"
                progress == 0 -> "Starting…"
                else -> "$progress%"
            },
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── Local file picker dialog ─────────────────────────────────────────────────

@Composable
private fun LocalFilePickerDialog(
    files: List<String>,
    modelsDir: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select a model file") },
        text = {
            if (files.isEmpty()) {
                Column {
                    Text("No .gguf or .task files found in the models folder.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Place model files in:\n$modelsDir",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                Column {
                    Text(
                        "Files in $modelsDir:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(8.dp))
                    files.forEach { path ->
                        val fileName = path.substringAfterLast("/")
                        TextButton(
                            onClick = { onSelect(path) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.InsertDriveFile, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                fileName,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
