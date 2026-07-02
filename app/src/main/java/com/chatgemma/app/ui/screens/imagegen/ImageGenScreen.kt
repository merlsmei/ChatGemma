package com.chatgemma.app.ui.screens.imagegen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.chatgemma.app.ai.ImageGenerationEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenScreen(
    onNavigateUp: () -> Unit,
    viewModel: ImageGenViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val conditionImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(viewModel::setConditionImage) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }
    LaunchedEffect(state.savedMessage) {
        state.savedMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSavedMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image Studio") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshModelStatus) {
                        Icon(Icons.Default.Refresh, "Re-check model files")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val status = state.modelStatus
            if (status != null && !status.modelPresent) {
                ModelSetupCard(status)
            }

            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::setPrompt,
                label = { Text("Prompt") },
                placeholder = { Text("A watercolor painting of a castle on a lake…") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            // Iterations
            Column {
                Text("Iterations: ${state.iterations}", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = state.iterations.toFloat(),
                    onValueChange = { viewModel.setIterations(it.toInt()) },
                    valueRange = 5f..50f
                )
                Text(
                    "More iterations = better quality, slower (~1-3s per iteration on-device)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                )
            }

            // Seed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.useRandomSeed,
                    onCheckedChange = viewModel::setUseRandomSeed
                )
                Text("Random seed")
                Spacer(Modifier.width(16.dp))
                if (!state.useRandomSeed) {
                    OutlinedTextField(
                        value = state.seed.toString(),
                        onValueChange = { it.toIntOrNull()?.let(viewModel::setSeed) },
                        label = { Text("Seed") },
                        singleLine = true,
                        modifier = Modifier.width(140.dp)
                    )
                }
            }

            // Condition image (image prompt)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Image prompt (optional)", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Guide the generation with a photo: its edges, depth map, or face " +
                        "landmarks steer the layout of the generated image.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageGenerationEngine.Condition.entries.forEach { condition ->
                        val available = status?.availableConditions?.contains(condition) ?: (condition == ImageGenerationEngine.Condition.NONE)
                        FilterChip(
                            selected = state.condition == condition,
                            onClick = { viewModel.setCondition(condition) },
                            label = { Text(condition.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            enabled = available
                        )
                    }
                }
                if (state.condition != ImageGenerationEngine.Condition.NONE) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(onClick = { conditionImagePicker.launch("image/*") }) {
                            Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.conditionImageUri == null) "Pick image" else "Change image")
                        }
                        state.conditionImageUri?.let { uri ->
                            AsyncImage(
                                model = uri,
                                contentDescription = "Condition image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }
                }
            }

            Button(
                onClick = viewModel::generate,
                enabled = !state.isGenerating &&
                    state.prompt.isNotBlank() &&
                    (status?.modelPresent == true),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Generating… this can take a minute")
                } else {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Generate")
                }
            }

            state.result?.let { bitmap ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Generated image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.FillWidth
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = viewModel::saveToGallery) {
                                Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Save")
                            }
                            Text(
                                "Seed: ${state.seed}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(0.5f),
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSetupCard(status: ImageGenerationEngine.ModelStatus) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Image model not installed", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "On-device image generation uses a converted Stable Diffusion 1.5 model " +
                    "(≈1-2 GB). Convert it with MediaPipe's image_generator_converter and " +
                    "copy the files to:",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "${status.baseDir}/model/",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "Optional conditioning plugins go to plugins/ (edge_plugin.tflite, " +
                    "depth_plugin.tflite, face_plugin.tflite) and aux models to aux/ " +
                    "(depth_model.tflite, face_landmarker.task). " +
                    "Tap refresh in the top bar after copying.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(0.6f)
            )
        }
    }
}
