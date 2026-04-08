package com.chatgemma.app.ui.screens.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chatgemma.app.domain.model.Branch
import com.chatgemma.app.domain.model.Message
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchSelectorSheet(
    branches: List<Branch>,
    currentBranchId: String,
    messages: List<Message>,
    onBranchSelect: (Branch) -> Unit,
    onRollbackMessage: (Message) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Branches", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp))

            // Branch list
            if (branches.size > 1) {
                Text("Switch Branch", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp))
                branches.forEach { branch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (branch.id != currentBranchId) onBranchSelect(branch) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AccountTree, null,
                            tint = if (branch.id == currentBranchId)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(0.6f),
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(branch.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (branch.id == currentBranchId)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground)
                            Text(
                                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                                    .format(Date(branch.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                            )
                        }
                        if (branch.id == currentBranchId) {
                            Spacer(Modifier.weight(1f))
                            Text("Active", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // Rollback from message
            Text("Rollback From Message", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp))
            Text("Select a message to create a new branch from that point:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                modifier = Modifier.padding(bottom = 8.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                items(messages.filter { it.role == "user" }.takeLast(10)) { msg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRollbackMessage(msg); onDismiss() }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg.textContent?.take(60) ?: "(media)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            SimpleDateFormat("HH:mm", Locale.getDefault())
                                .format(Date(msg.createdAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
