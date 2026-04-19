package com.chatgemma.app.ui.screens.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.chatgemma.app.domain.model.Message
import com.chatgemma.app.domain.model.Topic
import com.chatgemma.app.ui.theme.LocalChatGemmaColors
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    topic: Topic?,
    isStreaming: Boolean = false,
    streamingText: String = "",
    modifier: Modifier = Modifier,
    onLongPress: (Message) -> Unit = {},
    onTopicClick: (String) -> Unit = {}
) {
    val isUser = message.role == "user"
    val clipContext = LocalContext.current
    val chatColors = LocalChatGemmaColors.current
    val bubbleColor = if (isUser) chatColors.userBubble else chatColors.modelBubble
    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(Date(message.createdAt))

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (!isUser) {
            Text(
                text = "Gemma",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
            )
        }

        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = if (isUser) 18.dp else 4.dp,
                        topEnd = if (isUser) 4.dp else 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 18.dp
                    )
                )
                .background(bubbleColor)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val text = message.textContent ?: ""
                        if (text.isNotEmpty()) {
                            val clipboard = clipContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
                            Toast.makeText(clipContext, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                        onLongPress(message)
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                // Media attachment
                when (message.mediaType) {
                    "image" -> message.mediaUri?.let { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = "Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        if (!message.textContent.isNullOrEmpty()) {
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    "video" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Videocam, "Video", tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("Video attached", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Text content
                val displayText = if (isStreaming && !isUser) streamingText
                    else message.textContent ?: ""

                if (displayText.isNotEmpty()) {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                // Timestamp
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isStreaming && !isUser) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // Topic chip
        if (topic != null) {
            Spacer(Modifier.height(4.dp))
            TopicChip(
                topic = topic,
                modifier = Modifier.padding(horizontal = 4.dp),
                onClick = { onTopicClick(topic.id) }
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}
