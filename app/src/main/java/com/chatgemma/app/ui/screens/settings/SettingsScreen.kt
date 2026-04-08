package com.chatgemma.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit
) {
    var darkTheme by remember { mutableStateOf(true) }
    var autoSpeak by remember { mutableStateOf(false) }
    var autoTag by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            item {
                SettingsSection("Appearance") {
                    SettingsToggleItem(
                        title = "Dark Theme",
                        subtitle = "Use dark background (recommended for OLED)",
                        icon = Icons.Default.DarkMode,
                        checked = darkTheme,
                        onCheckedChange = { darkTheme = it }
                    )
                }
            }

            item {
                SettingsSection("Chat") {
                    SettingsToggleItem(
                        title = "Auto-Speak Responses",
                        subtitle = "Read model responses aloud via text-to-speech",
                        icon = Icons.Default.RecordVoiceOver,
                        checked = autoSpeak,
                        onCheckedChange = { autoSpeak = it }
                    )
                    SettingsToggleItem(
                        title = "Auto-Tag Topics",
                        subtitle = "Automatically tag messages with topics after each response",
                        icon = Icons.Default.Label,
                        checked = autoTag,
                        onCheckedChange = { autoTag = it }
                    )
                }
            }

            item {
                SettingsSection("About") {
                    ListItem(
                        headlineContent = { Text("Version") },
                        trailingContent = { Text("1.0.0", color = MaterialTheme.colorScheme.onSurface.copy(0.5f)) },
                        leadingContent = { Icon(Icons.Default.Info, null) }
                    )
                    ListItem(
                        headlineContent = { Text("Powered by") },
                        supportingContent = { Text("Google Gemma via MediaPipe Tasks GenAI") },
                        leadingContent = { Icon(Icons.Default.Memory, null) }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(content = content)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SettingsToggleItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(icon, null) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    )
}
