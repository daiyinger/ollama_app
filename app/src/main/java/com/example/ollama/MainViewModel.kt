package com.example.ollama

import android.app.Application
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit

data class RunningModelDisplayInfo(val name: String, val expirationTime: String)
data class LogFileInfo(val file: File, val size: Long)

@kotlinx.serialization.Serializable
data class PdfProcessingStatus(
    val currentPage: Int,
    val totalPages: Int,
    val imageSize: Long
)
open class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(application)
    private val json = Json { ignoreUnknownKeys = true }

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    open val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    open val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _selectedFileUri = MutableStateFlow<Uri?>(null)
    open val selectedFileUri: StateFlow<Uri?> = _selectedFileUri.asStateFlow()

    private val _runningModels = MutableStateFlow<List<RunningModelDisplayInfo>>(emptyList())
    val runningModels: StateFlow<List<RunningModelDisplayInfo>> = _runningModels.asStateFlow()

    private val _runningModelsError = MutableStateFlow<String?>(null)
    val runningModelsError: StateFlow<String?> = _runningModelsError.asStateFlow()

    private val _logContent = MutableStateFlow("")
    val logContent: StateFlow<String> = _logContent.asStateFlow()

    private val _logFiles = MutableStateFlow<List<LogFileInfo>>(emptyList())
    val logFiles: StateFlow<List<LogFileInfo>> = _logFiles.asStateFlow()

    private val _selectedLogFile = MutableStateFlow<File?>(null)
    val selectedLogFile: StateFlow<File?> = _selectedLogFile.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)

    val profiles: StateFlow<List<OllamaProfile>> = settingsManager.getProfilesFlow()
    val activeProfile: StateFlow<OllamaProfile?> = settingsManager.getActiveProfileFlow()

    private val _enableSessionLogging = MutableStateFlow(false)
    val enableSessionLogging: StateFlow<Boolean> = _enableSessionLogging.asStateFlow()

    private lateinit var ollamaApi: OllamaApiService
    private lateinit var ollamaApiPs: OllamaApiService
    private val pdfProcessingJobs = mutableMapOf<String, Job>()
    private val requestLoggingInterceptor: RequestLoggingInterceptor

    init {
        requestLoggingInterceptor = RequestLoggingInterceptor(application)
        _enableSessionLogging.value = settingsManager.getEnableSessionLogging()
        viewModelScope.launch {
            settingsManager.getActiveProfileFlow().collect { createOllamaService() }
        }
        loadConversations()
        createOllamaService()
    }

    fun onAppEnterBackground() {
        val application = getApplication<Application>()
        val intent = Intent(application, OllamaForegroundService::class.java)
        application.startService(intent)
    }

    fun onAppEnterForeground() {
        val application = getApplication<Application>()
        val intent = Intent(application, OllamaForegroundService::class.java)
        application.stopService(intent)
    }

    private fun createOllamaService() {
        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
            activeProfile.value?.apiKey?.let {
                if (it.isNotBlank()) {
                    builder.header("Authorization", "Bearer $it")
                }
            }
            val request = builder.build()
            chain.proceed(request)
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(requestLoggingInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        val psOkHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(requestLoggingInterceptor)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://localhost/") // Base URL is a placeholder
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        val psRetrofit = Retrofit.Builder()
            .baseUrl("http://localhost/") // Base URL is a placeholder
            .client(psOkHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        ollamaApi = retrofit.create(OllamaApiService::class.java)
        ollamaApiPs = psRetrofit.create(OllamaApiService::class.java)
    }

    fun addProfile(profile: OllamaProfile) {
        settingsManager.addProfile(profile)
    }

    fun updateProfile(profile: OllamaProfile) {
        val hostWithScheme = if (profile.apiHost.startsWith("http://") || profile.apiHost.startsWith("https://")) {
            profile.apiHost
        } else {
            "http://${profile.apiHost}"
        }
        settingsManager.updateProfile(profile.copy(apiHost = hostWithScheme))
    }

    fun deleteProfile(profileName: String) {
        settingsManager.deleteProfile(profileName)
    }

    fun setActiveProfile(profileName: String) {
        settingsManager.setActiveProfile(profileName)
    }

    fun setEnableSessionLogging(enabled: Boolean) {
        settingsManager.setEnableSessionLogging(enabled)
        _enableSessionLogging.value = enabled
    }

    private fun loadConversations() {
        viewModelScope.launch {
            val conversationsJson = settingsManager.getConversations()
            if (conversationsJson.isNotEmpty()) {
                try {
                    _conversations.value = json.decodeFromString(conversationsJson)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error decoding conversations", e)
                    _conversations.value = emptyList()
                }
            } else {
                _conversations.value = emptyList()
            }
        }
    }

    private fun saveConversations() {
        viewModelScope.launch {
            val conversationsJson = json.encodeToString(_conversations.value)
            settingsManager.saveConversations(conversationsJson)
        }
    }

    fun createConversation(): Conversation {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        val currentTime = sdf.format(Date())
        val logTag = "chat_$currentTime"
        val newConversation = Conversation(title = "Chat $currentTime", profileName = activeProfile.value?.name)
        _conversations.value = _conversations.value + newConversation
        saveConversations()
        requestLoggingInterceptor.logTag = logTag
        return newConversation
    }

    fun deleteConversation(conversationId: String) {
        deleteCachedImagesForConversation(conversationId)
        _conversations.value = _conversations.value.filter { it.id != conversationId }
        saveConversations()
    }

    fun deleteMessage(conversationId: String, message: ChatMessage) {
        viewModelScope.launch {
            // Delete associated file from internal storage if it exists
            message.fileUri?.let { uri ->
                if (uri.scheme == "file") {
                    uri.path?.let {
                        withContext(Dispatchers.IO) {
                            try {
                                val file = File(it)
                                if (file.exists()) {
                                    file.delete()
                                    Log.i("MainViewModel", "Deleted message file: $it")
                                }
                            } catch (e: Exception) {
                                Log.e("MainViewModel", "Error deleting message file", e)
                            }
                        }
                    }
                }
            }

            // Remove the message from the conversation
            val conversationIndex = _conversations.value.indexOfFirst { it.id == conversationId }
            if (conversationIndex != -1) {
                val updatedConversations = _conversations.value.toMutableList()
                val oldConversation = updatedConversations[conversationIndex]

                val newMessages = oldConversation.messages.toMutableList()
                val wasRemoved = newMessages.remove(message)

                if (wasRemoved) {
                    val updatedConversation = oldConversation.copy(messages = newMessages)
                    updatedConversations[conversationIndex] = updatedConversation
                    _conversations.value = updatedConversations
                    if (conversationId == _activeConversationId.value) {
                        _messages.value = newMessages
                    }
                    saveConversations()
                } else {
                    Log.w("MainViewModel", "Message to delete not found in conversation.")
                }
            }
        }
    }

    fun toggleMessageExpanded(conversationId: String, message: ChatMessage) {
        val conversationIndex = _conversations.value.indexOfFirst { it.id == conversationId }
        if (conversationIndex != -1) {
            val updatedConversations = _conversations.value.toMutableList()
            val oldConversation = updatedConversations[conversationIndex]
            val messageIndex = oldConversation.messages.indexOf(message)
            if (messageIndex != -1) {
                val newMessages = oldConversation.messages.toMutableList()
                val oldMessage = newMessages[messageIndex]
                val newMessage = oldMessage.copy(isExpanded = !oldMessage.isExpanded)
                newMessages[messageIndex] = newMessage
                val updatedConversation = oldConversation.copy(messages = newMessages)
                updatedConversations[conversationIndex] = updatedConversation
                _conversations.value = updatedConversations
                if (conversationId == _activeConversationId.value) {
                    _messages.value = newMessages
                }
                saveConversations()
            }
        }
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        val conversationIndex = _conversations.value.indexOfFirst { it.id == conversationId }
        if (conversationIndex != -1) {
            val updatedConversations = _conversations.value.toMutableList()
            val oldConversation = updatedConversations[conversationIndex]
            val updatedConversation = oldConversation.copy(title = newTitle)
            updatedConversations[conversationIndex] = updatedConversation
            _conversations.value = updatedConversations
            saveConversations()
        }
    }

    fun loadConversation(conversationId: String) {
        _activeConversationId.value = conversationId
        val conversation = _conversations.value.find { it.id == conversationId }
        if (conversation != null) {
            val title = conversation.title
            val timeStamp = if (title.startsWith("Chat ")) {
                title.substringAfter("Chat ").replace(":", "-")
            } else {
                conversationId // Fallback to id if title format is unexpected
            }
            requestLoggingInterceptor.logTag = "chat_$timeStamp"

            _messages.value = conversation.messages
            conversation.profileName?.let {
                settingsManager.setActiveProfile(it)
            }
        } else {
            // Handle case where conversation is not found, maybe create a new one or show an error
            requestLoggingInterceptor.logTag = null
            _messages.value = emptyList()
        }
    }

    fun setProfileForConversation(conversationId: String, profileName: String) {
        val conversationIndex = _conversations.value.indexOfFirst { it.id == conversationId }
        if (conversationIndex != -1) {
            val updatedConversations = _conversations.value.toMutableList()
            val oldConversation = updatedConversations[conversationIndex]
            val updatedConversation = oldConversation.copy(profileName = profileName)
            updatedConversations[conversationIndex] = updatedConversation
            _conversations.value = updatedConversations
            saveConversations()
            settingsManager.setActiveProfile(profileName)
        }
    }

    fun onFileSelected(uri: Uri) {
        viewModelScope.launch {
            _selectedFileUri.value = uri
        }
    }

    fun clearSelectedFile() {
        _selectedFileUri.value = null
    }

    fun stopPdfProcessing(conversationId: String) {
        pdfProcessingJobs[conversationId]?.cancel()
        pdfProcessingJobs.remove(conversationId)
        updateConversationPdfProcessingStatus(conversationId, null)
        updateConversationInferenceStatus(conversationId, "PDF processing stopped.")
    }

    private fun imageFileToBase64(uri: Uri, quality: Int): String? {
        return try {
            val contentResolver = getApplication<Application>().contentResolver
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error converting image to Base64", e)
            null
        }
    }

    private fun readFileContent(uri: Uri): String? {
        return try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error reading file content", e)
            null
        }
    }

    private suspend fun copyFileToInternalStorage(uri: Uri, conversationId: String): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                val application = getApplication<Application>()
                val contentResolver = application.contentResolver

                // Get original file name
                var fileName = "temp_file"
                val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            fileName = it.getString(nameIndex)
                        }
                    }
                }

                // Create a conversation-specific directory
                val conversationDir = File(application.filesDir, "attachments/$conversationId")
                if (!conversationDir.exists()) {
                    conversationDir.mkdirs()
                }

                // Create a new file in the conversation directory
                val newFile = File(conversationDir, fileName)

                // Copy the file content
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(newFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                // Return the URI of the new file
                newFile.toUri()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error copying file to internal storage", e)
                null
            }
        }
    }

    private fun deleteCachedImagesForConversation(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conversationDir = File(getApplication<Application>().filesDir, "attachments/$conversationId")
                if (conversationDir.exists()) {
                    conversationDir.deleteRecursively()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error deleting cached images", e)
            }
        }
    }

    fun sendMessage(prompt: String, conversationId: String) {
        viewModelScope.launch {
            if (prompt.isBlank() && _selectedFileUri.value == null) return@launch

            val conversation = _conversations.value.find { it.id == conversationId }
            if (conversation == null) return@launch

            var fileUri = _selectedFileUri.value
            var imagesBase64: List<String>? = null
            var finalPrompt = prompt

            val profile = profiles.value.find { it.name == conversation.profileName } ?: activeProfile.value

            fileUri?.let {
                val mimeType = getApplication<Application>().contentResolver.getType(it)
                if (profile != null) {
                    if (mimeType == "application/pdf") {
                        var isVisionModel = false
                        if (profile.checkImageProcessing) {
                            updateConversationInferenceStatus(conversationId, "Checking model...")
                            val modelDetails = try {
                                val url = profile.apiHost.removeSuffix("/") + "/api/show"
                                ollamaApi.show(url, ShowRequest(name = profile.model))
                            } catch (e: Exception) {
                                Log.e("MainViewModel", "Failed to get model details", e)
                                null
                            }

                            withContext(Dispatchers.IO) {
                                Log.i("MainViewModel", "Model Details: $modelDetails")
                                val logDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ollama")
                                // 如果目录不存在，则创建它
                                if (!logDir.exists()) {
                                    logDir.mkdirs()
                                }
                                val logFile = File(logDir, "log.txt")
                                logFile.appendText("${getCurrentTimestamp()} - Model Details: ${modelDetails?.let { json.encodeToString(it) } ?: "null"}\n")
                            }

                            val visionFamilies = profile.visionFamilies.split(",").map { it.trim() }
                            isVisionModel = modelDetails?.details?.families?.any { it in visionFamilies } == true
                        } else {
                            isVisionModel = true
                        }

                        if (!isVisionModel) {
                            addMessageToConversation(
                                conversationId,
                                ChatMessage(sender = "Error", content = "The active model does not support image input for PDF files.")
                            )
                            updateConversationInferenceStatus(conversationId, "Error")
                            return@launch
                        }

                        updateConversationInferenceStatus(conversationId, "Processing PDF...")
                        val copiedUri = copyFileToInternalStorage(it, conversationId)
                        if (copiedUri == null) {
                            addMessageToConversation(
                                conversationId,
                                ChatMessage(sender = "Error", content = "Failed to save the PDF file for processing.")
                            )
                            updateConversationInferenceStatus(conversationId, "Error")
                            return@launch
                        }

                        val userMessage = ChatMessage(sender = "You", content = finalPrompt, fileUri = copiedUri)
                        addMessageToConversation(conversationId, userMessage)
                        _selectedFileUri.value = null

                        pdfProcessingJobs[conversationId]?.cancel()

                        val pdfProcessor = PdfProcessor(
                            application = getApplication(),
                            ollamaApi = ollamaApi,
                            json = json,
                            activeProfile = profile,
                            coroutineScope = viewModelScope,
                            onStatusUpdate = { convId, status ->
                                updateConversationInferenceStatus(convId, status)
                            },
                            onPageProcessed = { convId, message -> addMessageToConversation(convId, message) },
                            onPdfProcessingStatus = { convId, status ->
                                updateConversationPdfProcessingStatus(convId, status)
                            }
                        )
                        pdfProcessingJobs[conversationId] = pdfProcessor.process(conversationId, copiedUri, finalPrompt, profile.imageQuality, profile.pdfScale)

                        return@launch

                    } else if (mimeType?.startsWith("image/") == true) {
                        fileUri = copyFileToInternalStorage(it, conversationId)
                        updateConversationInferenceStatus(conversationId, "Processing file...")
                        fileUri?.let {
                            imageFileToBase64(it, profile.imageQuality)?.let { base64 ->
                                imagesBase64 = listOf(base64)
                            }
                        }
                    } else if (mimeType != null && mimeType.startsWith("text/")) {
                        fileUri = copyFileToInternalStorage(it, conversationId)
                        updateConversationInferenceStatus(conversationId, "Processing file...")
                        readFileContent(it)?.let { content ->
                            finalPrompt = "$prompt\n\n--- Document Content ---\n$content"
                        }
                    }
                }
            }

            val userMessage = ChatMessage(sender = "You", content = finalPrompt, fileUri = fileUri)
            addMessageToConversation(conversationId, userMessage)

            _selectedFileUri.value = null

            withContext(Dispatchers.IO) {
                try {
                    if (profile == null) return@withContext
                    val url = profile.apiHost.removeSuffix("/") + "/" + profile.apiPath.removePrefix("/")
                    updateConversationInferenceStatus(conversationId, "Connecting...")

                    when (profile.apiMode) {
                        "Ollama" -> {
                            updateConversationInferenceStatus(conversationId, "Sending...")
                            val request = OllamaRequest(model = profile.model, prompt = finalPrompt, stream = true, images = imagesBase64)
                            val responseBody = ollamaApi.generateOllamaStream(url = url, request = request)
                            updateConversationInferenceStatus(conversationId, "Waiting for response...")
                            val responseStream = responseBody.byteStream().bufferedReader()

                            var ollamaMessage = ChatMessage(sender = "Ollama", content = "")
                            withContext(Dispatchers.Main) {
                                addMessageToConversation(conversationId, ollamaMessage)
                            }

                            var firstChunk = true
                            responseStream.use {
                                var line: String?
                                while (true) {
                                    line = it.readLine()
                                    if (line == null) break

                                    if (firstChunk) {
                                        updateConversationInferenceStatus(conversationId, "Receiving...")
                                        firstChunk = false
                                    }

                                    try {
                                        val ollamaResponse = json.decodeFromString<OllamaResponse>(line)
                                        ollamaMessage = ollamaMessage.copy(content = ollamaMessage.content + ollamaResponse.response)
                                        withContext(Dispatchers.Main) {
                                            updateLastMessageInConversation(conversationId, ollamaMessage)
                                        }

                                        if (ollamaResponse.done == true) {
                                            val performance = formatOllamaPerformance(ollamaResponse)
                                            ollamaMessage = ollamaMessage.copy(performance = performance)
                                            withContext(Dispatchers.Main) {
                                                updateLastMessageInConversation(conversationId, ollamaMessage)
                                                updateConversationInferenceStatus(conversationId, "Done")
                                            }
                                            break
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MainViewModel", "Error parsing JSON line: $line", e)
                                        updateConversationInferenceStatus(conversationId, "Error")
                                    }
                                }
                            }
                        }
                        "OpenAI API 兼容" -> {
                            val previousMessages = conversation.messages.mapNotNull { msg ->
                                val role = if (msg.sender == "You") "user" else "assistant"
                                val content = mutableListOf<OpenAIContent>()
                                content.add(OpenAITextContent(text = msg.content))
                                // Note: Previous images are not re-sent in this implementation
                                OpenAIRequestMessage(role = role, content = content)
                            }

                            val content = mutableListOf<OpenAIContent>()
                            content.add(OpenAITextContent(text = finalPrompt))
                            imagesBase64?.forEach {
                                val imageUrl = "data:image/jpeg;base64,$it"
                                content.add(OpenAIImageContent(image_url = OpenAIImageUrl(url = imageUrl)))
                            }
                            val messages = previousMessages + listOf(OpenAIRequestMessage(role = "user", content = content))
                            val request = OpenAIRequest(model = profile.model, messages = messages, stream = true)
                            updateConversationInferenceStatus(conversationId, "Sending...")
                            val responseBody = ollamaApi.generateOpenAIStream(url = url, request = request)
                            updateConversationInferenceStatus(conversationId, "Waiting for response...")
                            val responseStream = responseBody.byteStream().bufferedReader()

                            var ollamaMessage = ChatMessage(sender = "Ollama", content = "")
                            withContext(Dispatchers.Main) {
                                addMessageToConversation(conversationId, ollamaMessage)
                            }

                            var firstChunk = true
                            responseStream.use {
                                var line: String?
                                while (true) {
                                    line = it.readLine()
                                    if (line == null) break
                                    if (!line.startsWith("data:")) continue

                                    if (firstChunk) {
                                        updateConversationInferenceStatus(conversationId, "Receiving...")
                                        firstChunk = false
                                    }

                                    val data = line.substringAfter("data: ").trim()
                                    if (data == "[DONE]") {
                                        updateConversationInferenceStatus(conversationId, "Done")
                                        break
                                    }

                                    try {
                                        val openAIResponse = json.decodeFromString<OpenAIStreamResponse>(data)
                                        val delta = openAIResponse.choices.firstOrNull()?.delta?.content ?: ""
                                        ollamaMessage = ollamaMessage.copy(content = ollamaMessage.content + delta)
                                        withContext(Dispatchers.Main) {
                                            updateLastMessageInConversation(conversationId, ollamaMessage)
                                        }

                                    } catch (e: Exception) {
                                        Log.e("MainViewModel", "Error parsing JSON line: $line", e)
                                        updateConversationInferenceStatus(conversationId, "Error")
                                    }
                                }
                            }
                        }
                        else -> {
                            withContext(Dispatchers.Main) {
                                addMessageToConversation(conversationId, ChatMessage(sender = "Error", content = "Unsupported API mode"))
                                updateConversationInferenceStatus(conversationId, "Error")
                            }
                        }
                    }
                    // Auto-generate title for new conversations
                    val currentConversation = _conversations.value.find { it.id == conversationId }
                    if (currentConversation != null && currentConversation.title.startsWith("Chat ") && currentConversation.messages.size > 1) {
                        generateConversationTitle(conversationId)
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        addMessageToConversation(
                            conversationId,
                            ChatMessage(sender = "Error", content = e.message ?: "Unknown error")
                        )
                        updateConversationInferenceStatus(conversationId, "Error: Connection failed")
                    }
                    Log.e("MainViewModel", "Error sending message", e)
                } finally {
                    delay(2000)
                    updateConversationInferenceStatus(conversationId, "")
                }
            }
        }
    }

    private fun addMessageToConversation(conversationId: String, message: ChatMessage) {
        val conversationIndex = _conversations.value.indexOfFirst { it.id == conversationId }
        if (conversationIndex != -1) {
            val updatedConversations = _conversations.value.toMutableList()
            val oldConversation = updatedConversations[conversationIndex]
            val newMessages = oldConversation.messages.toMutableList().apply { add(message) }
            val updatedConversation = oldConversation.copy(messages = newMessages)
            updatedConversations[conversationIndex] = updatedConversation
            _conversations.value = updatedConversations
            if (conversationId == _activeConversationId.value) {
                _messages.value = newMessages
            }
            saveConversations()
        }
    }

    private fun updateLastMessageInConversation(conversationId: String, message: ChatMessage) {
        val conversationIndex = _conversations.value.indexOfFirst { it.id == conversationId }
        if (conversationIndex != -1) {
            val updatedConversations = _conversations.value.toMutableList()
            val oldConversation = updatedConversations[conversationIndex]
            val newMessages = oldConversation.messages.toMutableList().apply {
                if (isNotEmpty()) {
                    removeAt(lastIndex)
                }
                add(message)
            }
            val updatedConversation = oldConversation.copy(messages = newMessages)
            updatedConversations[conversationIndex] = updatedConversation
            _conversations.value = updatedConversations
            if (conversationId == _activeConversationId.value) {
                _messages.value = newMessages
            }
            saveConversations()
        }
    }

    private fun updateConversationInferenceStatus(conversationId: String, status: String) {
        val conversationIndex = _conversations.value.indexOfFirst { it.id == conversationId }
        if (conversationIndex != -1) {
            val updatedConversations = _conversations.value.toMutableList()
            val oldConversation = updatedConversations[conversationIndex]
            val updatedConversation = oldConversation.copy(inferenceStatus = status)
            updatedConversations[conversationIndex] = updatedConversation
            _conversations.value = updatedConversations
        }
    }

    private fun updateConversationPdfProcessingStatus(conversationId: String, status: PdfProcessingStatus?) {
        val conversationIndex = _conversations.value.indexOfFirst { it.id == conversationId }
        if (conversationIndex != -1) {
            val updatedConversations = _conversations.value.toMutableList()
            val oldConversation = updatedConversations[conversationIndex]
            val updatedConversation = oldConversation.copy(pdfProcessingStatus = status)
            updatedConversations[conversationIndex] = updatedConversation
            _conversations.value = updatedConversations
        }
    }

    private fun generateConversationTitle(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val conversation = _conversations.value.find { it.id == conversationId } ?: return@launch
            val userPrompt = conversation.messages.firstOrNull()?.content ?: return@launch

            try {
                val profile = activeProfile.value ?: return@launch
                val url = profile.apiHost.removeSuffix("/") + "/" + profile.apiPath.removePrefix("/")
                val titlePrompt = "Summarize the following text in 5 words or less: \"$userPrompt\""

                when (profile.apiMode) {
                    "Ollama" -> {
                        val request = OllamaRequest(model = profile.model, prompt = titlePrompt, stream = false)
                        val response = ollamaApi.generateOllama(url = url, request = request)
                        withContext(Dispatchers.Main) {
                            updateConversationTitle(conversationId, response.response.trim().replace("\"", ""))
                        }
                    }
                    "OpenAI API 兼容" -> {
                        val messages = listOf(OpenAIRequestMessage(role = "user", content = listOf(OpenAITextContent(text = titlePrompt))))
                        val request = OpenAIRequest(model = profile.model, messages = messages)
                        val response = ollamaApi.generateOpenAI(url = url, request = request)
                        val title = response.choices.firstOrNull()?.message?.content?.trim()?.replace("\"", "") ?: "Untitled"
                        withContext(Dispatchers.Main) {
                            updateConversationTitle(conversationId, title)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error generating title", e)
            }
        }
    }

    fun fetchRunningModels() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _runningModels.value = emptyList()
                _runningModelsError.value = "Loading..."
            }
            try {
                val profile = activeProfile.value ?: return@launch
                val psPath = profile.psPath
                val url = if (psPath.startsWith("http://") || psPath.startsWith("https://")) {
                    psPath
                } else {
                    "http://$psPath"
                }
                val logDir = getApplication<Application>().filesDir
                val logFile = File(logDir, "ollama_log.txt")
                logFile.appendText("${getCurrentTimestamp()} - Request: GET $url\n")
                val response = ollamaApiPs.getRunningModels(url = url)
                logFile.appendText("${getCurrentTimestamp()} - Response: ${json.encodeToString(response)}\n")

                val displayInfo = response.models.map { model ->
                    val expirationTime = model.expiresAt?.let {
                        try {
                            val odt = OffsetDateTime.parse(it, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                            val now = OffsetDateTime.now(odt.offset)
                            val minutesRemaining = java.time.Duration.between(now, odt).toMinutes()
                            if (minutesRemaining > 0) "${minutesRemaining}min" else "Expired"
                        } catch (e: Exception) {
                            "Invalid Date"
                        }
                    } ?: "N/A"
                    RunningModelDisplayInfo(name = model.name, expirationTime = expirationTime)
                }

                withContext(Dispatchers.Main) {
                    _runningModels.value = displayInfo
                    _runningModelsError.value = null
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching running models", e)
                val errorMessage = e.message ?: "Unknown error fetching running models"
                withContext(Dispatchers.Main) {
                    _runningModelsError.value = errorMessage
                }
                val logDir = getApplication<Application>().filesDir
                val logFile = File(logDir, "ollama_log.txt")
                logFile.appendText("${getCurrentTimestamp()} - Error fetching running models: $errorMessage\n")
            }
        }
    }

    fun clearRunningModelsError() {
        _runningModelsError.value = null
    }

    fun listLogFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val internalLogDir = getApplication<Application>().filesDir
            val internalLogFile = File(internalLogDir, "ollama_log.txt")

            val externalLogDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ollama")

            val allLogFiles = mutableListOf<LogFileInfo>()
            if (internalLogFile.exists()) {
                allLogFiles.add(LogFileInfo(internalLogFile, internalLogFile.length()))
            }

            if (externalLogDir.exists() && externalLogDir.isDirectory) {
                externalLogDir.listFiles { _, name -> name.endsWith(".txt") || name.endsWith(".log") }?.let {
                    files ->
                    files.forEach {
                        allLogFiles.add(LogFileInfo(it, it.length()))
                    }
                }
            }
            _logFiles.value = allLogFiles
        }
    }

    fun readLogFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (file.exists()) {
                    val content = file.readText()
                    withContext(Dispatchers.Main) {
                        _logContent.value = content
                        _selectedLogFile.value = file
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _logContent.value = "Log file not found."
                        _selectedLogFile.value = null
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error reading log file", e)
                withContext(Dispatchers.Main) {
                    _logContent.value = "Error reading log file: ${e.message}"
                    _selectedLogFile.value = null
                }
            }
        }
    }

    fun deleteLogFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (file.exists()) {
                    file.delete()
                    listLogFiles()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error deleting log file", e)
            }
        }
    }

    fun clearLogFile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _selectedLogFile.value?.let { logFile ->
                    if (logFile.exists()) {
                        logFile.writeText("")
                        withContext(Dispatchers.Main) {
                            _logContent.value = ""
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            _logContent.value = "Log file not found."
                        }
                    }
                } ?: withContext(Dispatchers.Main) {
                    _logContent.value = "No log file selected."
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error clearing log file", e)
                withContext(Dispatchers.Main) {
                    _logContent.value = "Error clearing log file: ${e.message}"
                }
            }
        }
    }

    private fun updateConversationTitle(conversationId: String, title: String) {
        val conversationIndex = _conversations.value.indexOfFirst { it.id == conversationId }
        if (conversationIndex != -1) {
            val updatedConversations = _conversations.value.toMutableList()
            val oldConversation = updatedConversations[conversationIndex]
            val updatedConversation = oldConversation.copy(title = title)
            updatedConversations[conversationIndex] = updatedConversation
            _conversations.value = updatedConversations
            saveConversations()
        }
    }

    private fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun formatOllamaPerformance(response: OllamaResponse): String? {
        return response.eval_count?.let {
            if (response.eval_duration != null && response.eval_duration > 0) {
                "%.2f t/s".format(it / (response.eval_duration / 1_000_000_000.0))
            } else null
        }
    }

    private fun formatOpenAIPerformance(response: OpenAIResponse): String? {
        return null
    }
}
