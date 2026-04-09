package com.chatgemma.app.ui.screens.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chatgemma.app.domain.model.Topic

@Composable
fun TopicChip(
    topic: Topic,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val color = try {
        Color(android.graphics.Color.parseColor(topic.colorHex))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.18f))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = topic.label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
