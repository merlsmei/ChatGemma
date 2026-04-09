package com.chatgemma.app.ui.screens.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ContextUsageBar(
    usagePercent: Float,
    modifier: Modifier = Modifier,
    onManageContext: () -> Unit
) {
    val animatedProgress by animateFloatAsState(usagePercent, label = "context_progress")
    val barColor by animateColorAsState(
        targetValue = when {
            usagePercent >= 0.85f -> Color(0xFFEF4444)
            usagePercent >= 0.70f -> Color(0xFFF59E0B)
            else -> MaterialTheme.colorScheme.primary
        },
        label = "bar_color"
    )

    if (usagePercent < 0.50f) return  // Only show when above 50%

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (usagePercent >= 0.85f) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = barColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = "Context: ${(usagePercent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = barColor,
                        fontWeight = if (usagePercent >= 0.85f) FontWeight.Bold else FontWeight.Normal
                    )
                )
            }
            if (usagePercent >= 0.85f) {
                TextButton(
                    onClick = onManageContext,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Manage", fontSize = 11.sp, color = barColor)
                }
            }
        }
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}
