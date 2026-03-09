package com.example.ollama

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Task
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Surface
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationHistoryScreen(
    viewModel: MainViewModel,
    onNavigateToConversation: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val conversations by viewModel.conversations.collectAsState()
    val runningModels by viewModel.runningModels.collectAsState()
    val runningModelsError by viewModel.runningModelsError.collectAsState()
    val ollamaModels by viewModel.ollamaModels.collectAsState()
    val ollamaModelsError by viewModel.ollamaModelsError.collectAsState()
    val showModelDetailsDialog by viewModel.showModelDetailsDialog.collectAsState()
    val selectedModelDetails by viewModel.selectedModelDetails.collectAsState()
    var showRenameDialog by remember { mutableStateOf<Conversation?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Conversation?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showRunningModelsDialog by remember { mutableStateOf(false) }
    var showOllamaModelsDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    val context = LocalContext.current
    val expandedGroups by viewModel.expandedGroups.collectAsState()
    val expandedDays by viewModel.expandedDays.collectAsState()

    // 多选模式状态
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    fun exitMultiSelectMode() {
        isMultiSelectMode = false
        selectedIds = emptySet()
    }

    fun exportSelectedConversations() {
        val contents = selectedIds.mapNotNull { id ->
            viewModel.buildMarkdownContent(id)
        }
        if (contents.isEmpty()) return
        val combinedText = contents.joinToString(separator = "\n\n---\n\n")
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, combinedText)
            putExtra(Intent.EXTRA_SUBJECT, "导出会话")
        }
        context.startActivity(Intent.createChooser(shareIntent, "导出会话"))
        exitMultiSelectMode()
    }

    if (showRenameDialog != null) {
        RenameConversationDialog(
            conversation = showRenameDialog!!,
            onDismiss = { showRenameDialog = null },
            onRename = { newTitle ->
                viewModel.renameConversation(showRenameDialog!!.id, newTitle)
            }
        )
    }

    if (showDeleteDialog != null) {
        DeleteConversationConfirmationDialog(
            onConfirm = {
                viewModel.deleteConversation(showDeleteDialog!!.id)
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null }
        )
    }

    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("删除会话") },
            text = { Text("确定要删除选中的 ${selectedIds.size} 个会话吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversations(selectedIds.toList())
                    showBatchDeleteDialog = false
                    exitMultiSelectMode()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    if (showRunningModelsDialog) {
        RunningModelsDialog(
            runningModels = runningModels,
            error = runningModelsError,
            onDismiss = {
                showRunningModelsDialog = false
                viewModel.clearRunningModelsError()
            }
        )
    }

    if (showOllamaModelsDialog) {
        OllamaModelsDialog(
            ollamaModels = ollamaModels,
            error = ollamaModelsError,
            onDismiss = {
                showOllamaModelsDialog = false
                viewModel.clearOllamaModelsError()
            },
            onModelClick = { model ->
                viewModel.showOllamaModel(model.name)
            }
        )
    }

    if (showModelDetailsDialog) {
        selectedModelDetails?.let {
            ModelDetailsDialog(
                modelDetails = it,
                onDismiss = { viewModel.dismissOllamaModelDetailsDialog() }
            )
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("退出应用") },
            text = { Text("您确定要退出吗？") },
            confirmButton = {
                TextButton(onClick = { activity?.finish() }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    BackHandler {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
        } else {
            showExitDialog = true
        }
    }

    Scaffold(
        topBar = {
            if (isMultiSelectMode) {
                TopAppBar(
                    title = { Text("已选 ${selectedIds.size} 个") },
                    navigationIcon = {
                        IconButton(onClick = { exitMultiSelectMode() }) {
                            Icon(Icons.Default.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { exportSelectedConversations() },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "导出选中")
                        }
                        IconButton(
                            onClick = { showBatchDeleteDialog = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除选中",
                                tint = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = {
                            viewModel.listOllamaModels()
                            showOllamaModelsDialog = true
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "List Ollama Models")
                        }
                        IconButton(onClick = {
                            viewModel.fetchRunningModels()
                            showRunningModelsDialog = true
                        }) {
                            Icon(Icons.Default.Task, contentDescription = "Show Running Models")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isMultiSelectMode) {
                FloatingActionButton(onClick = {
                    val newConversation = viewModel.createConversation()
                    onNavigateToConversation(newConversation.id)
                }) {
                    Icon(Icons.Default.Add, contentDescription = "New Chat")
                }
            }
        }
    ) { padding ->
        if (conversations.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No conversations yet.", modifier = Modifier.padding(bottom = 16.dp))
                Button(onClick = {
                    val newConversation = viewModel.createConversation()
                    onNavigateToConversation(newConversation.id)
                }) {
                    Text("Start a New Chat")
                }
            }
        } else {
            val conversationsByMonth = conversations
                .groupBy { conversation ->
                    val localDate = if (conversation.createdAt != 0L) {
                        Instant.ofEpochMilli(conversation.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                    } else {
                        try {
                            val dateStr = conversation.title.substringAfter("Chat ").substringBefore("_")
                            LocalDate.parse(dateStr)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    localDate?.let { YearMonth.from(it) }
                }
                .filterKeys { it != null }
                .mapKeys { it.key!! }
                .toSortedMap(compareByDescending { it })

            LazyColumn(modifier = Modifier.padding(padding)) {
                conversationsByMonth.forEach { (month, conversationsInMonth) ->
                    val monthHeaderText = month.format(DateTimeFormatter.ofPattern("yyyy年MM月"))
                    val isMonthExpanded = expandedGroups.contains(monthHeaderText)

                    stickyHeader {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    viewModel.setExpandedGroups(
                                        if (isMonthExpanded) expandedGroups - monthHeaderText else expandedGroups + monthHeaderText
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = monthHeaderText,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = if (isMonthExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isMonthExpanded) "Collapse" else "Expand"
                            )
                        }
                    }

                    if (isMonthExpanded) {
                        val conversationsByDay = conversationsInMonth
                            .groupBy { conversation ->
                                if (conversation.createdAt != 0L) {
                                    Instant.ofEpochMilli(conversation.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                                } else {
                                    try {
                                        val dateStr = conversation.title.substringAfter("Chat ").substringBefore("_")
                                        LocalDate.parse(dateStr)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                            }
                            .filterKeys { it != null }
                            .mapKeys { it.key!! }
                            .toSortedMap(compareByDescending { it })
                    
                        conversationsByDay.forEach { (date, conversationsInDay) ->
                            val dayHeaderText = when {
                                date == LocalDate.now() -> "今天"
                                date == LocalDate.now().minusDays(1) -> "昨天"
                                else -> date.format(DateTimeFormatter.ofPattern("MM 月 dd 日 EEEE"))
                            }
                            val isDayExpanded = expandedDays.contains(dayHeaderText)
                    
                            stickyHeader {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                        .clickable {
                                            viewModel.setExpandedDays(
                                                if (isDayExpanded) expandedDays - dayHeaderText else expandedDays + dayHeaderText
                                            )
                                        }
                                        .padding(horizontal = 24.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = dayHeaderText,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Icon(
                                        imageVector = if (isDayExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (isDayExpanded) "Collapse" else "Expand",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                    
                            if (isDayExpanded) {
                                items(conversationsInDay, key = { it.id }) { conversation ->
                                    val isSelected = conversation.id in selectedIds
                                    ConversationListItem(
                                        conversation = conversation,
                                        isMultiSelectMode = isMultiSelectMode,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (isMultiSelectMode) {
                                                selectedIds = if (isSelected) selectedIds - conversation.id
                                                             else selectedIds + conversation.id
                                            } else {
                                                onNavigateToConversation(conversation.id)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isMultiSelectMode) {
                                                isMultiSelectMode = true
                                                selectedIds = setOf(conversation.id)
                                            }
                                        },
                                        onRename = { showRenameDialog = conversation },
                                        onDelete = { showDeleteDialog = conversation }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteConversationConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Delete Conversation") },
        text = { Text(text = "Are you sure you want to delete this conversation?") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationListItem(
    conversation: Conversation,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox 仅在多选模式下显示
            AnimatedVisibility(visible = isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null // Click handled by parent card
                )
            }
            
            Text(
                text = conversation.title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            
            // 三点菜单按钮 - 仅在非多选模式显示
            AnimatedVisibility(visible = !isMultiSelectMode) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多选项", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = {
                                showMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RenameConversationDialog(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var newTitle by remember { mutableStateOf(conversation.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Conversation") },
        text = {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text("New Title") }
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onRename(newTitle)
                    onDismiss()
                }
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun RunningModelsDialog(
    runningModels: List<RunningModelDisplayInfo>,
    error: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f),
        title = { Text("Running Models") },
        text = {
            if (error == "Loading...") {
                CircularProgressIndicator()
            } else if (error != null) {
                Text(error)
            } else if (runningModels.isEmpty()) {
                Text("No models are currently running.")
            } else {
                LazyColumn {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Model",
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Expires In",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    items(runningModels) { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = model.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(text = model.expirationTime)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OllamaModelsDialog(
    ollamaModels: List<OllamaModel>,
    error: String?,
    onDismiss: () -> Unit,
    onModelClick: (OllamaModel) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f),
        title = { Text("Ollama Models") },
        text = {
            if (error == "Loading...") {
                CircularProgressIndicator()
            } else if (error != null) {
                Text(error)
            } else if (ollamaModels.isEmpty()) {
                Text("No models found.")
            } else {
                LazyColumn {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Model Name",
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Size",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    items(ollamaModels) { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .clickable { onModelClick(model) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                SelectionContainer {
                                    Text(
                                        text = model.name,
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        maxLines = 1
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(text = "${model.size / 1_000_000} MB")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
fun ModelDetailsDialog(
    modelDetails: ShowResponse,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(modelDetails.details.family) },
        text = {
            LazyColumn {
                // Basic info
                item { Text("Format: ${modelDetails.details.format}", style = MaterialTheme.typography.bodyLarge) }
                item { Text("Families: ${modelDetails.details.families?.joinToString(", ") ?: "N/A"}", style = MaterialTheme.typography.bodyMedium) }
                item { Text("Parameter Size: ${modelDetails.details.parameterSize}", style = MaterialTheme.typography.bodyMedium) }
                item { Text("Quantization Level: ${modelDetails.details.quantizationLevel ?: "N/A"}", style = MaterialTheme.typography.bodyMedium) }
                
                // Capabilities
                modelDetails.capabilities?.let { caps ->
                    if (caps.isNotEmpty()) {
                        item { 
                            Spacer(modifier = Modifier.padding(8.dp))
                            Text("Capabilities", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.padding(4.dp))
                        }
                        caps.forEach { cap ->
                            item {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                ) {
                                    Text(
                                        text = cap,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Model Architecture (from model_info)
                val architectureParams = modelDetails.parameters?.filterKeys { key ->
                    key in listOf("context_length", "embedding_length", "attention_value_length", 
                                  "block_count", "feed_forward_length", "full_attention_interval", 
                                  "image_token_id")
                }
                
                if (!architectureParams.isNullOrEmpty()) {
                    item {
                        Spacer(modifier = Modifier.padding(8.dp))
                        Text("Model Architecture", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.padding(4.dp))
                    }
                    architectureParams.entries.sortedBy { (key, _) -> key }.forEach { (key, value) ->
                        item { 
                            Text("$key: $value", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                
                // Runtime Parameters
                val runtimeParams = modelDetails.parameters?.filterKeys { key ->
                    key !in listOf("context_length", "embedding_length", "attention_value_length", 
                                   "block_count", "feed_forward_length", "full_attention_interval", 
                                   "image_token_id")
                }
                
                // Parameters
                modelDetails.parameters?.let { params ->
                    if (params.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.padding(8.dp))
                            Text("Parameters", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.padding(4.dp))
                        }
                        runtimeParams?.entries?.sortedBy { (key, _) -> key }?.forEach { (key, value) ->
                            item { Text("$key: $value", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}
