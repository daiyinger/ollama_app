package com.example.ollama

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.ollama.ui.theme.OllamaTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    conversationId: String?,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val messages by viewModel.messages.collectAsState()
    val selectedFileUri by viewModel.selectedFileUri.collectAsState()
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val inferenceStatus by viewModel.inferenceStatus.collectAsState()
    val conversation = viewModel.conversations.collectAsState().value.find { it.id == conversationId }
    val profiles by viewModel.profiles.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            uri?.let { viewModel.onFileSelected(it) }
        }
    )

    LaunchedEffect(conversationId) {
        conversationId?.let {
            viewModel.loadConversation(it)
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.scrollToItem(0)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(conversation?.title ?: stringResource(R.string.app_name))
                            if (inferenceStatus.isNotBlank()) {
                                Text(
                                    text = inferenceStatus,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                text = text,
                onTextChange = { text = it },
                selectedFileUri = selectedFileUri,
                onFileClear = { 
                    viewModel.clearSelectedFile()
                    text = ""
                },
                onAddFileClick = { filePickerLauncher.launch("*/*") },
                onSendClick = {
                    if (conversationId != null) {
                        viewModel.sendMessage(text, conversationId)
                        text = ""
                        keyboardController?.hide()
                    }
                },
                profiles = profiles,
                activeProfile = activeProfile,
                onProfileSelected = { profile ->
                    viewModel.setActiveProfile(profile.name)
                }
            )
        }
    ) { innerPadding ->
        val reversedMessages = messages.reversed()
        LazyColumn(
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 8.dp)
        ) {
            items(reversedMessages) { message ->
                MessageBubble(message = message)
            }
        }
    }
}

@Composable
fun CodeBlock(codeText: String) {
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFF2E2E2E), // A dark background
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Code",
                color = Color.LightGray,
                style = MaterialTheme.typography.labelSmall
            )
            IconButton(onClick = {
                coroutineScope.launch {
                    clipboardManager.setText(AnnotatedString(codeText))
                }
            }) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    tint = Color.LightGray
                )
            }
        }
        val scrollState = rememberScrollState()
        Text(
            text = codeText,
            modifier = Modifier
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    bottom = 8.dp
                )
                .horizontalScroll(scrollState),
            fontFamily = FontFamily.Monospace,
            color = Color.White
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: ChatMessage) {
    val isUserMessage = message.sender.equals("You", ignoreCase = true)
    val arrangement = if (isUserMessage) Arrangement.End else Arrangement.Start
    val backgroundColor = if (isUserMessage) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.Bottom
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = backgroundColor
        ) {
            SelectionContainer {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    message.fileUri?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = "Selected file",
                            modifier = Modifier
                                .size(150.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }

                    val parts = message.content.split("```")
                    if (parts.size == 1) {
                        if (message.content.isNotBlank()) {
                            Text(
                                text = message.content,
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            parts.forEachIndexed { index, part ->
                                if (part.isNotBlank()) {
                                    if (index % 2 == 1) { // Code block
                                        CodeBlock(codeText = part.trim())
                                    } else { // Normal text
                                        Text(
                                            text = part.trim(),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    message.performance?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    selectedFileUri: Uri?,
    onFileClear: () -> Unit,
    onAddFileClick: () -> Unit,
    onSendClick: () -> Unit,
    profiles: List<OllamaProfile>,
    activeProfile: OllamaProfile?,
    onProfileSelected: (OllamaProfile) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Column {
            selectedFileUri?.let {
                Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = it,
                            contentDescription = "Selected file thumbnail",
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = getFileName(context, it),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear selected file",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .clip(CircleShape)
                            .clickable { onFileClear() },
                        tint = Color.White
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onAddFileClick) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = stringResource(R.string.add_file)
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.type_a_message)) },
                    shape = RoundedCornerShape(24.dp)
                )
                IconButton(
                    onClick = onSendClick,
                    enabled = text.isNotBlank() || selectedFileUri != null
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clickable { expanded = true }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(activeProfile?.model ?: "Select a profile")
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Dropdown"
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.name) },
                            onClick = {
                                onProfileSelected(profile)
                                expanded = false
                            },
                            leadingIcon = {
                                RadioButton(
                                    selected = profile.name == activeProfile?.name,
                                    onClick = {
                                        onProfileSelected(profile)
                                        expanded = false
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

fun getFileName(context: Context, uri: Uri): String {
    var fileName = "unknown_file"
    val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                fileName = it.getString(nameIndex)
            }
        }
    }
    return fileName
}

@Preview(showBackground = true)
@Composable
fun ChatScreenPreview() {
    OllamaTheme {
        ChatScreen(
            viewModel = MainViewModel(LocalContext.current.applicationContext as Application),
            conversationId = null,
            onNavigateUp = {}
        )
    }
}
