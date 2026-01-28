package com.example.ollama

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.createBitmap
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.ollama.ui.theme.OllamaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream

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
    val conversation = viewModel.conversations.collectAsState().value.find { it.id == conversationId }
    val profiles by viewModel.profiles.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var enlargedImageUri by remember { mutableStateOf<Uri?>(null) }

    if (enlargedImageUri != null) {
        EnlargedImageDialog(
            imageUri = enlargedImageUri!!,
            onDismiss = { enlargedImageUri = null }
        )
    }

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
                            Text(
                                text = conversation?.title ?: stringResource(R.string.app_name),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (conversation?.inferenceStatus?.isNotBlank() == true) {
                                Text(
                                    text = conversation.inferenceStatus,
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
            val isProcessingPdf = conversation?.pdfProcessingStatus != null
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
                    if (conversationId != null) {
                        viewModel.setProfileForConversation(conversationId, profile.name)
                    }
                },
                isProcessingPdf = isProcessingPdf,
                onStopPdfProcessing = {
                    conversationId?.let { viewModel.stopPdfProcessing(it) }
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
            item {
                conversation?.pdfProcessingStatus?.let {
                    PdfProcessingStatusView(status = it)
                }
            }
            items(reversedMessages) { message ->
                MessageBubble(message = message) { uri ->
                    enlargedImageUri = uri
                }
            }
        }
    }
}

@Composable
fun EnlargedImageDialog(imageUri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset += pan
                        if (scale == 1f) {
                            offset = Offset.Zero
                        }
                    }
                }
        ) {
            AsyncImage(
                model = imageUri,
                contentDescription = "Enlarged image",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )
            Button(
                onClick = {
                    coroutineScope.launch {
                        val success = saveImageToGallery(context, imageUri)
                        val message = if (success) "Image saved to gallery" else "Failed to save image"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Text("Save")
            }
        }
    }
}

suspend fun saveImageToGallery(context: Context, imageUri: Uri): Boolean {
    val imageLoader = ImageLoader(context)
    val request = ImageRequest.Builder(context)
        .data(imageUri)
        .allowHardware(false) // Important for accessing bitmap
        .build()

    val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
    if (result !is BitmapDrawable) {
        return false
    }

    val bitmap = result.bitmap
    val newBitmap = createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
    val canvas = Canvas(newBitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.drawBitmap(bitmap, 0f, 0f, null)

    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "ollama_image_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    val contentResolver = context.contentResolver
    var uri: Uri? = null
    var outputStream: OutputStream? = null
    return try {
        uri = contentResolver.insert(collection, contentValues)
        if (uri == null) {
            return false
        }
        outputStream = contentResolver.openOutputStream(uri)
        if (outputStream == null) {
            return false
        }
        newBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, contentValues, null, null)
        true
    } catch (e: Exception) {
        uri?.let { contentResolver.delete(it, null, null) }
        Log.e("ChatScreen", "Error saving image to gallery", e)
        false
    } finally {
        outputStream?.close()
    }
}


@Composable
fun PdfProcessingStatusView(status: PdfProcessingStatus) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Converting page ${status.currentPage}/${status.totalPages} (${humanReadableByteCountSI(status.imageSize)})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CodeBlock(codeText: String) {
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
                clipboardManager.setText(AnnotatedString(codeText))
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
fun MessageBubble(message: ChatMessage, onImageClick: (Uri) -> Unit) {
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
                    message.fileUri?.let { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = "Selected file",
                            modifier = Modifier
                                .size(150.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onImageClick(uri) },
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
    onProfileSelected: (OllamaProfile) -> Unit,
    isProcessingPdf: Boolean,
    onStopPdfProcessing: () -> Unit
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
                if (isProcessingPdf) {
                    IconButton(onClick = onStopPdfProcessing) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop PDF Processing"
                        )
                    }
                } else {
                    IconButton(onClick = onAddFileClick) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = stringResource(R.string.add_file)
                        )
                    }
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
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                fileName = it.getString(nameIndex)
            }
        }
    }
    return fileName
}

class FakeMainViewModel(application: Application) : MainViewModel(application) {
    override val conversations: StateFlow<List<Conversation>> = MutableStateFlow(
        listOf(
            Conversation(
                id = "1",
                title = "Test Conversation",
                messages = mutableListOf(),
                pdfProcessingStatus = PdfProcessingStatus(1, 10, 12345)
            )
        )
    ).asStateFlow()

    override val messages: StateFlow<List<ChatMessage>> = MutableStateFlow(
        listOf(
            ChatMessage(sender = "You", content = "Hello"),
            ChatMessage(sender = "Ollama", content = "Hi there!")
        )
    ).asStateFlow()

    override val selectedFileUri: StateFlow<Uri?> = MutableStateFlow(Uri.EMPTY).asStateFlow()
}

@Preview(showBackground = true)
@Composable
fun ChatScreenPreview() {
    OllamaTheme {
        ChatScreen(
            viewModel = FakeMainViewModel(LocalContext.current.applicationContext as Application),
            conversationId = "1",
            onNavigateUp = {}
        )
    }
}
