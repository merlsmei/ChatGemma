package com.chatgemma.app.ui.screens.chat.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.chatgemma.app.ui.theme.LocalChatGemmaColors

@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceToggle: () -> Unit,
    onAttachImage: () -> Unit,
    onAttachVideo: () -> Unit,
    isListening: Boolean,
    isGenerating: Boolean,
    onCancelGeneration: () -> Unit,
    attachedImages: List<Uri>,
    attachedVideoUri: Uri?,
    partialVoiceText: String,
    modifier: Modifier = Modifier
) {
    val chatColors = LocalChatGemmaColors.current
    var showAttachMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // Attached media previews
        if (attachedImages.isNotEmpty() || attachedVideoUri != null) {
            MediaAttachmentRow(
                images = attachedImages,
                videoUri = attachedVideoUri,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // Partial voice text
        AnimatedVisibility(isListening && partialVoiceText.isNotEmpty()) {
            Text(
                text = partialVoiceText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Attach button
            Box {
                IconButton(
                    onClick = { showAttachMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "Attach",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                DropdownMenu(
                    expanded = showAttachMenu,
                    onDismissRequest = { showAttachMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Image") },
                        leadingIcon = { Icon(Icons.Default.Image, null) },
                        onClick = { showAttachMenu = false; onAttachImage() }
                    )
                    DropdownMenuItem(
                        text = { Text("Video") },
                        leadingIcon = { Icon(Icons.Default.Videocam, null) },
                        onClick = { showAttachMenu = false; onAttachVideo() }
                    )
                }
            }

            // Text field
            TextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("Message Gemma…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = chatColors.inputBackground,
                    unfocusedContainerColor = chatColors.inputBackground,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                maxLines = 6
            )

            // Voice button
            IconButton(
                onClick = onVoiceToggle,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isListening) MaterialTheme.colorScheme.error
                        else Color.Transparent
                    )
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (isListening) "Stop listening" else "Voice input",
                    tint = if (isListening) Color.White else MaterialTheme.colorScheme.onSurface.copy(0.7f)
                )
            }

            // Send / Stop button
            if (isGenerating) {
                IconButton(
                    onClick = onCancelGeneration,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Stop, "Stop", tint = Color.White)
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank() || attachedImages.isNotEmpty() || attachedVideoUri != null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (text.isNotBlank() || attachedImages.isNotEmpty() || attachedVideoUri != null)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                ) {
                    Icon(Icons.Default.Send, "Send", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun MediaAttachmentRow(
    images: List<Uri>,
    videoUri: Uri?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        images.forEach { uri ->
            coil.compose.AsyncImage(
                model = uri,
                contentDescription = "Attached image",
                modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
        videoUri?.let {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Videocam, "Video", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
