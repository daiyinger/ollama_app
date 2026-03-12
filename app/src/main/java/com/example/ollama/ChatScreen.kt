package com.example.ollama

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.ExperimentalMaterialApi
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
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
import java.util.Locale
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import kotlin.math.abs
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.resolveDefaults


@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
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
    val systemPrompts by viewModel.systemPrompts.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var enlargedImageUri by remember { mutableStateOf<Uri?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var messageToDelete by remember { mutableStateOf<ChatMessage?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }

    if (showDeleteDialog && messageToDelete != null) {
        DeleteConfirmationDialog(
            onConfirm = {
                if (conversationId != null) {
                    viewModel.deleteMessage(conversationId, messageToDelete!!)
                }
                showDeleteDialog = false
                messageToDelete = null
            },
            onDismiss = {
                showDeleteDialog = false
                messageToDelete = null
            }
        )
    }

    if (enlargedImageUri != null) {
        EnlargedImageDialog(
            imageUri = enlargedImageUri!!,
            onDismiss = { enlargedImageUri = null }
        )
    }

    if (showSettingsDialog && activeProfile != null) {
        ChatSettingsDialog(
            profile = activeProfile!!,
            onDismiss = { showSettingsDialog = false },
            onSave = { temperature, topP, presencePenalty ->
                if (conversationId != null) {
                    viewModel.updateProfileParameters(conversationId, temperature, topP, presencePenalty)
                }
                showSettingsDialog = false
            }
        )
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            uri?.let { viewModel.onFileSelected(it) }
        }
    )

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
        onResult = { uri: Uri? ->
            if (uri != null && conversationId != null) {
                viewModel.exportToMarkdown(conversationId, uri)
            }
        }
    )

    LaunchedEffect(conversationId) {
        conversationId?.let {
            viewModel.loadConversation(it)
        }
    }

    // Removed the LaunchedEffect that forces scrolling to the top (item 0)
    // to preserve the scroll position when returning to the conversation.
    // If a specific scroll-to-latest-message behavior is desired,
    // it should be implemented with more specific conditions (e.g., only for new AI responses).

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
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Chat Settings"
                        )
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options"
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Export to Markdown") },
                            onClick = {
                                showMenu = false
                                val fileName = (conversation?.title ?: "conversation").replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".md"
                                exportLauncher.launch(fileName)
                            }
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
                },
                systemPrompts = systemPrompts,
                activeSystemPromptId = conversation?.systemPromptId,
                onSystemPromptSelected = { spId ->
                    if (conversationId != null) {
                        viewModel.setSystemPromptForConversation(conversationId, spId)
                    }
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
            items(reversedMessages, key = { it.id }) { message ->
                // 使用 LaunchedEffect 处理逻辑，避免 confirmValueChange 中的循环引用
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { false }, // 始终回弹，由 LaunchedEffect 决定是否触发对话框
                    positionalThreshold = { totalDistance -> totalDistance * 0.5f }
                )

                LaunchedEffect(dismissState.targetValue) {
                    if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                        // 🔑 只有在实际位移超过 30% 屏幕宽度时才触发对话框
                        // 即使是快速滑动（Fling），如果物理位移不够，也会被过滤
                        val currentOffset = try { dismissState.requireOffset() } catch (e: Exception) { 0f }
                        if (abs(currentOffset) > screenWidthPx * 0.3f) {
                            messageToDelete = message
                            showDeleteDialog = true
                        }
                    }
                }

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = true,
                    backgroundContent = {
                        val color = when (dismissState.targetValue) {
                            SwipeToDismissBoxValue.EndToStart -> Color.Red.copy(alpha = 0.8f)
                            else -> Color.Transparent
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(color, RoundedCornerShape(16.dp))
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Icon",
                                tint = Color.White
                            )
                        }
                    }
                ) {
                    MessageBubble(
                        message = message,
                        onImageClick = { uri ->
                            enlargedImageUri = uri
                        },
                        onMessageClick = {
                            if (conversationId != null) {
                                viewModel.toggleMessageExpanded(conversationId, it)
                            }
                        },
                        onThinkingClick = {
                            if (conversationId != null) {
                                viewModel.toggleThinkingExpanded(conversationId, it)
                            }
                        }
                    )
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
            verticalAlignment = Alignment.CenterVertically) {
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

@Composable
fun AttachmentView(message: ChatMessage, onImageClick: (Uri) -> Unit) {
    val context = LocalContext.current

    if (message.fileUri != null && isUriAccessible(context, message.fileUri)) {
        val mimeType = message.fileMimeType ?: getMimeType(context, message.fileUri)
        if (mimeType?.startsWith("image/") == true) {
            Log.i("ChatScreen", "AttachmentView is image: ${message.fileUri}")
            AsyncImage(
                model = message.fileUri,
                contentDescription = "Selected file",
                modifier = Modifier
                    .size(150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onImageClick(message.fileUri) },
                contentScale = ContentScale.Crop
            )
        } else {
            val isPdf = mimeType == "application/pdf"
            val icon = if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.AttachFile
            val description = if (isPdf) "PDF attachment" else "File attachment"
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = description)
                Text(
                    text = message.fileName ?: getFileName(context, message.fileUri),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    } else if (message.fileName != null) {
        // Fallback to showing just the file name if URI is null or inaccessible
        Row(
            modifier = Modifier
                .padding(8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.AttachFile, contentDescription = "File attachment")
            Text(
                text = message.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DeleteConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Delete Message") },
        text = { Text(text = "Are you sure you want to delete this message?") },
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

@Composable
fun ThinkingBlock(
    thinkingContent: String,
    isExpanded: Boolean,
    isDone: Boolean,
    onToggle: () -> Unit
) {
    val charCount = thinkingContent.length
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom while streaming
    LaunchedEffect(thinkingContent) {
        if (!isDone) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        // Header row: clickable to expand/collapse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = "Thinking",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (!isDone) "思考中…" else "思考过程",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (charCount > 0) {
                    Text(
                        text = "($charCount 字)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Expanded content with max height and scroll
        if (isExpanded && thinkingContent.isNotBlank()) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                thickness = 0.5.dp
            )
            Text(
                text = thinkingContent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(max = 200.dp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    onImageClick: (Uri) -> Unit,
    onMessageClick: (ChatMessage) -> Unit,
    onThinkingClick: (ChatMessage) -> Unit = {}
) {
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
            color = backgroundColor,
            modifier = Modifier.clickable { onMessageClick(message) }
        ) {
            SelectionContainer {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttachmentView(message = message, onImageClick = onImageClick)

                    // Thinking process section
                    if (message.thinkingContent != null) {
                        ThinkingBlock(
                            thinkingContent = message.thinkingContent,
                            isExpanded = message.isThinkingExpanded,
                            isDone = message.isThinkingDone,
                            onToggle = { onThinkingClick(message) }
                        )
                    }

                    if (message.isExpanded) {
                        if (message.content.isNotBlank()) {
                            // Use Markdown rendering for expanded messages
                            RichText(
                                modifier = Modifier.fillMaxWidth(),
                                style = RichTextStyle.Default.resolveDefaults()
                            ) {
                                Markdown(
                                    content = message.content
                                )
                            }
                        }
                    } else {
                        if (message.content.isNotBlank()) {
                            Text(
                                text = message.content,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
    onStopPdfProcessing: () -> Unit,
    systemPrompts: List<SystemPrompt> = emptyList(),
    activeSystemPromptId: String? = null,
    onSystemPromptSelected: (String?) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var systemPromptExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Column {
            selectedFileUri?.let { uri ->
                val mimeType = getMimeType(context, uri)
                Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (mimeType?.startsWith("image/") == true) {
                            Log.i("ChatScreen", "ChatInputBar is image: $uri")
                            AsyncImage(
                                model = uri,
                                contentDescription = "Selected file thumbnail",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Selected file",
                                modifier = Modifier.size(64.dp)
                            )
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = getFileName(context, uri),
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
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
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
                // 系统提示词选择器
                if (systemPrompts.isNotEmpty()) {
                    val activeSpName = systemPrompts.find { it.id == activeSystemPromptId }?.name
                    Row(
                        modifier = Modifier
                            .clickable { systemPromptExpanded = true }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "系统提示词",
                            modifier = Modifier.size(16.dp),
                            tint = if (activeSystemPromptId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = activeSpName ?: "无提示词",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (activeSystemPromptId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Dropdown",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = systemPromptExpanded,
                        onDismissRequest = { systemPromptExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("不使用系统提示词") },
                            onClick = {
                                onSystemPromptSelected(null)
                                systemPromptExpanded = false
                            },
                            leadingIcon = {
                                RadioButton(
                                    selected = activeSystemPromptId == null,
                                    onClick = {
                                        onSystemPromptSelected(null)
                                        systemPromptExpanded = false
                                    }
                                )
                            }
                        )
                        systemPrompts.forEach { sp ->
                            DropdownMenuItem(
                                text = { Text(sp.name) },
                                onClick = {
                                    onSystemPromptSelected(sp.id)
                                    systemPromptExpanded = false
                                },
                                leadingIcon = {
                                    RadioButton(
                                        selected = sp.id == activeSystemPromptId,
                                        onClick = {
                                            onSystemPromptSelected(sp.id)
                                            systemPromptExpanded = false
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
}

fun getMimeType(context: Context, uri: Uri): String? {
    var mimeType: String? = context.contentResolver.getType(uri)
    if (mimeType == null) {
        val fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.lowercase(Locale.ROOT))
    }
    return mimeType
}

fun getFileName(context: Context, uri: Uri): String {
    var fileName: String? = null

    // 方案一：尝试通过 ContentResolver 查询（这是最可靠的方法）
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = it.getString(nameIndex)
                }
            }
        }
    }

    // 方案二：如果方案一失败，尝试从路径中提取
    if (fileName == null) {
        Log.i("ChatScreen", "getFileName: retry")
        fileName = uri.path
        val cut = fileName?.lastIndexOf('/')
        if (cut != null && cut != -1) {
            fileName = fileName?.substring(cut + 1)
        }
    }

    // 如果文件名中包含编码字符（如 %20），进行解码
    return try {
        // 使用 java.net.URLDecoder 来处理可能存在的URL编码
        java.net.URLDecoder.decode(fileName, "UTF-8") ?: "unknown_file"
    } catch (e: Exception) {
        // 解码失败时返回原始文件名或默认值
        fileName ?: "unknown_file"
    }
}

fun isUriAccessible(context: Context, uri: Uri): Boolean {
    return try {
        context.contentResolver.openInputStream(uri)?.use { it.close() }
        true
    } catch (e: Exception) {
        false
    }
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

@Composable
fun ChatSettingsDialog(
    profile: OllamaProfile,
    onDismiss: () -> Unit,
    onSave: (Float, Float, Float) -> Unit
) {
    // Use default values if profile parameters are null
    var temperature by remember { mutableFloatStateOf(profile.temperature ?: 0.7f) }
    var topP by remember { mutableFloatStateOf(profile.topP ?: 0.9f) }
    var presencePenalty by remember { mutableFloatStateOf(profile.presencePenalty ?: 0.0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat Settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Temperature Slider
                Text(
                    text = "Temperature: ${String.format(java.util.Locale.getDefault(), "%.2f", temperature)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    steps = 39,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Controls randomness. Higher values make output more random, lower values more deterministic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Top P Slider
                Text(
                    text = "Top P: ${String.format(java.util.Locale.getDefault(), "%.2f", topP)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0f..1f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Nucleus sampling. Controls diversity via top-p sampling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Presence Penalty Slider
                Text(
                    text = "Presence Penalty: ${String.format(java.util.Locale.getDefault(), "%.2f", presencePenalty)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = presencePenalty,
                    onValueChange = { presencePenalty = it },
                    valueRange = -2f..2f,
                    steps = 39,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Penalize new tokens based on their presence in the text so far.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(temperature, topP, presencePenalty)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
