package com.chatgemma.app.ui.screens.chat.components

import androidx.compose.foundation.layout.*
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
    onParamsChange: (InferenceParams) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
