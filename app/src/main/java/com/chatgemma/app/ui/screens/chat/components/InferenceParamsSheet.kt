package com.chatgemma.app.ui.screens.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chatgemma.app.domain.model.InferenceParams
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceParamsSheet(
    params: InferenceParams,
    autoCompressEnabled: Boolean,
    compressionThreshold: Float,
    onParamsChange: (InferenceParams) -> Unit,
    onAutoCompressChange: (Boolean) -> Unit,
    onCompressionThresholdChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Inference Parameters", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            // Temperature
            ParamSlider(
                label = "Temperature",
                value = params.temperature,
                valueRange = 0f..2f,
                displayValue = "%.2f".format(params.temperature),
                onValueChange = { onParamsChange(params.copy(temperature = it)) }
            )

            // Top-K
            ParamSlider(
                label = "Top-K",
                value = params.topK.toFloat(),
                valueRange = 1f..100f,
                displayValue = params.topK.toString(),
                onValueChange = { onParamsChange(params.copy(topK = it.roundToInt())) }
            )

            // Top-P
            ParamSlider(
                label = "Top-P",
                value = params.topP,
                valueRange = 0f..1f,
                displayValue = "%.2f".format(params.topP),
                onValueChange = { onParamsChange(params.copy(topP = it)) }
            )

            // Max tokens
            ParamSlider(
                label = "Max Tokens",
                value = params.maxTokens.toFloat(),
                valueRange = 128f..4096f,
                displayValue = params.maxTokens.toString(),
                steps = 30,
                onValueChange = { onParamsChange(params.copy(maxTokens = it.roundToInt())) }
            )

            // Context window size (n_ctx). Larger = more history before
            // compression kicks in, but more memory/VRAM for the KV cache.
            ParamSlider(
                label = "Context Window",
                value = params.contextSize.toFloat(),
                valueRange = 1024f..8192f,
                displayValue = "${params.contextSize} tok",
                steps = 6,
                onValueChange = {
                    onParamsChange(params.copy(contextSize = (it / 1024f).roundToInt() * 1024))
                }
            )
            Text(
                "Larger windows hold more chat history but use more memory. Requires model reload.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            // Auto context compression
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Compress Context", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Summarize older messages in the background when the context window fills up.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoCompressEnabled,
                    onCheckedChange = onAutoCompressChange
                )
            }
            if (autoCompressEnabled) {
                Spacer(Modifier.height(8.dp))
                ParamSlider(
                    label = "Compression Threshold",
                    value = compressionThreshold,
                    valueRange = 0.3f..0.95f,
                    displayValue = "${(compressionThreshold * 100).roundToInt()}%",
                    steps = 12,
                    onValueChange = { onCompressionThresholdChange(it) }
                )
            }
            Spacer(Modifier.height(8.dp))

            // GPU Acceleration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("GPU Acceleration", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Experimental — may crash on some devices. Requires model reload.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = params.gpuLayers > 0,
                    onCheckedChange = { enabled ->
                        onParamsChange(params.copy(gpuLayers = if (enabled) 99 else 0))
                    }
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                displayValue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
    }
}
