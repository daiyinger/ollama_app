package com.example.ollama

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Task
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth // Add this import
import java.time.format.DateTimeFormatter
import kotlin.math.abs

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
    var showRunningModelsDialog by remember { mutableStateOf(false) }
    var showOllamaModelsDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    val activity = (LocalContext.current as? Activity)
    val expandedGroups by viewModel.expandedGroups.collectAsState()

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }

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
        showExitDialog = true
    }

    Scaffold(
        topBar = {
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
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val newConversation = viewModel.createConversation()
                onNavigateToConversation(newConversation.id)
            }) {
                Icon(Icons.Default.Add, contentDescription = "New Chat")
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
                                else -> date.format(DateTimeFormatter.ofPattern("MM月dd日 EEEE"))
                            }
                            stickyHeader {
                                Text(
                                    text = dayHeaderText,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                        .padding(horizontal = 24.dp, vertical = 4.dp),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            items(conversationsInDay, key = { it.id }) { conversation ->
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { false },
                                    positionalThreshold = { totalDistance -> totalDistance * 0.5f }
                                )

                                LaunchedEffect(dismissState.targetValue) {
                                    if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                                        val currentOffset = try { dismissState.requireOffset() } catch (e: Exception) { 0f }
                                        if (abs(currentOffset) > screenWidthPx * 0.3f) {
                                            showDeleteDialog = conversation
                                        }
                                    }
                                }

                                SwipeToDismissBox(
                                    state = dismissState,
                                    enableDismissFromEndToStart = true,
                                    enableDismissFromStartToEnd = false,
                                    backgroundContent = {
                                        val color = when (dismissState.targetValue) {
                                            SwipeToDismissBoxValue.EndToStart -> Color.Red.copy(alpha = 0.8f)
                                            else -> Color.Transparent
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(color, RoundedCornerShape(16.dp))
                                                .padding(horizontal = 20.dp),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = Color.White
                                            )
                                        }
                                    }
                                ) {
                                    ConversationListItem(
                                        conversation = conversation,
                                        onClick = { onNavigateToConversation(conversation.id) },
                                        onLongClick = { showRenameDialog = conversation }
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
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Text(
            text = conversation.title,
            modifier = Modifier.padding(16.dp)
        )
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
                item { Text("Format: ${modelDetails.details.format}") }
                item { Text("Parameter Size: ${modelDetails.details.parameterSize}") }
                item { Text("Quantization Level: ${modelDetails.details.quantizationLevel}") }
                modelDetails.details.families?.let {
                    item { Text("Families: ${it.joinToString()}") }
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
