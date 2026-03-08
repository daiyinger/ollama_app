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
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit

data class RunningModelDisplayInfo(val name: String, val expirationTime: String)
data class LogFileInfo(val file: File, val size: Long)
data class CopiedFile(val uri: Uri, val fileName: String)

@kotlinx.serialization.Serializable
data class OllamaModel(
    val name: String,
    val modified_at: String,
    val size: Long
)

@kotlinx.serialization.Serializable
data class OllamaModelsList(
    val models: List<OllamaModel>
)

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

    private val _ollamaModels = MutableStateFlow<List<OllamaModel>>(emptyList())
    val ollamaModels: StateFlow<List<OllamaModel>> = _ollamaModels.asStateFlow()

    private val _ollamaModelsError = MutableStateFlow<String?>(null)
    val ollamaModelsError: StateFlow<String?> = _ollamaModelsError.asStateFlow()

    private val _logContent = MutableStateFlow("")
    val logContent: StateFlow<String> = _logContent.asStateFlow()

    private val _logFiles = MutableStateFlow<List<LogFileInfo>>(emptyList())
    val logFiles: StateFlow<List<LogFileInfo>> = _logFiles.asStateFlow()

    private val _selectedLogFile = MutableStateFlow<File?>(null)
    val selectedLogFile: StateFlow<File?> = _selectedLogFile.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)

    val profiles: StateFlow<List<OllamaProfile>> = settingsManager.getProfilesFlow()
    val activeProfile: StateFlow<OllamaProfile?> = settingsManager.getActiveProfileFlow()
    val systemPrompts: StateFlow<List<SystemPrompt>> = settingsManager.getSystemPromptsFlow()

    private val _enableSessionLogging = MutableStateFlow(false)
    val enableSessionLogging: StateFlow<Boolean> = _enableSessionLogging.asStateFlow()

    private val _expandedGroups = MutableStateFlow(setOf<String>())
    val expandedGroups: StateFlow<Set<String>> = _expandedGroups.asStateFlow()

    private lateinit var ollamaApi: OllamaApiService
    private lateinit var ollamaApiPs: OllamaApiService
    private val pdfProcessingJobs = mutableMapOf<String, Job>()
    private val requestLoggingInterceptor: RequestLoggingInterceptor
    private val _selectedModelDetails = MutableStateFlow<ShowResponse?>(null)
    val selectedModelDetails: StateFlow<ShowResponse?> = _selectedModelDetails.asStateFlow()

    private val _showModelDetailsDialog = MutableStateFlow(false)
    val showModelDetailsDialog: StateFlow<Boolean> = _showModelDetailsDialog.asStateFlow()

    init {
        requestLoggingInterceptor = RequestLoggingInterceptor(application)
        _enableSessionLogging.value = settingsManager.getEnableSessionLogging()
        _expandedGroups.value = settingsManager.getExpandedGroups()
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

    fun setPagePrompt(pagePrompt: String) {
        activeProfile.value?.let {
            val updatedProfile = it.copy(pagePrompt = pagePrompt)
            settingsManager.updateProfile(updatedProfile)
        }
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

    fun addSystemPrompt(systemPrompt: SystemPrompt) {
        settingsManager.addSystemPrompt(systemPrompt)
    }

    fun updateSystemPrompt(systemPrompt: SystemPrompt) {
        settingsManager.updateSystemPrompt(systemPrompt)
    }

    fun deleteSystemPrompt(id: String) {
        settingsManager.deleteSystemPrompt(id)
    }

    fun setSystemPromptForConversation(conversationId: String, systemPromptId: String?) {
        val conversationIndex = _conversations.value.indexOfFirst { it.id == conversationId }
        if (conversationIndex != -1) {
            val updatedConversations = _conversations.value.toMutableList()
            val updatedConversation = updatedConversations[conversationIndex].copy(systemPromptId = systemPromptId)
            updatedConversations[conversationIndex] = updatedConversation
            _conversations.value = updatedConversations
            saveConversations()
        }
    }

    fun setExpandedGroups(groups: Set<String>) {
        _expandedGroups.value = groups
        settingsManager.saveExpandedGroups(groups)
    }

    fun exportSettings(): String {
        return settingsManager.exportSettings()
    }

    fun importSettings(settingsJson: String) {
        settingsManager.importSettings(settingsJson)
        _enableSessionLogging.value = settingsManager.getEnableSessionLogging()
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
        val newConversation = Conversation(title = "Chat $currentTime", profileName = activeProfile.value?.name, createdAt = System.currentTimeMillis())
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

                // 从消息列表中根据 id 过滤掉要删除的消息
                val originalSize = oldConversation.messages.size
                val newMessages = oldConversation.messages.filter { it.id != message.id }

                // 检查消息是否真的被移除了
                if (newMessages.size < originalSize) {
                    val updatedConversation = oldConversation.copy(messages = newMessages)
                    updatedConversations[conversationIndex] = updatedConversation
                    _conversations.value = updatedConversations

                    // 如果删除的是当前活动对话的消息，则同时更新 _messages StateFlow
                    if (conversationId == _activeConversationId.value) {
                        _messages.value = newMessages
                    }
                    saveConversations()
                    Log.i("MainViewModel", "Message with id ${message.id} deleted successfully.")
                } else {
                    Log.w("MainViewModel", "Message with id ${message.id} to delete not found in conversation.")
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

    fun toggleThinkingExpanded(conversationId: String, message: ChatMessage) {
        val conversationIndex = _conversations.value.indexOfFirst { it.id == conversationId }
        if (conversationIndex != -1) {
            val updatedConversations = _conversations.value.toMutableList()
            val oldConversation = updatedConversations[conversationIndex]
            val messageIndex = oldConversation.messages.indexOf(message)
            if (messageIndex != -1) {
                val newMessages = oldConversation.messages.toMutableList()
                val oldMessage = newMessages[messageIndex]
                val newMessage = oldMessage.copy(isThinkingExpanded = !oldMessage.isThinkingExpanded)
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

            _messages.value = conversation.messages.map { message ->
                // Check if the file for the message's URI is accessible
                message.fileUri?.let {
                    if (!isUriAccessible(it)) {
                        // If the URI is not accessible, create a new message with a null fileUri
                        return@map message.copy(fileUri = null)
                    }
                }
                message
            }
            conversation.profileName?.let {
                settingsManager.setActiveProfile(it)
            }
        } else {
            // Handle case where conversation is not found, maybe create a new one or show an error
            requestLoggingInterceptor.logTag = null
            _messages.value = emptyList()
        }
    }

    private fun isUriAccessible(uri: Uri): Boolean {
        return try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.close() }
            true
        } catch (e: Exception) {
            false
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

    private fun getFileName(uri: Uri): String {
        var fileName = "temp_file"
        val cursor: Cursor? = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
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

    private suspend fun copyFileToInternalStorage(uri: Uri, conversationId: String): CopiedFile? {
        return withContext(Dispatchers.IO) {
            try {
                val application = getApplication<Application>()
                val contentResolver = application.contentResolver

                // Get original file name
                val fileName = getFileName(uri)

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

                // Return the URI and file name of the new file
                CopiedFile(newFile.toUri(), fileName)
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

            var userFileUri = _selectedFileUri.value
            var userFileName: String? = null
            var userFileMimeType: String? = null
            var imagesBase64: List<String>? = null
            var finalPrompt = prompt

            val profile = profiles.value.find { it.name == conversation.profileName } ?: activeProfile.value

            userFileUri?.let { uri ->
                userFileMimeType = getApplication<Application>().contentResolver.getType(uri)
                userFileName = getFileName(uri)

                if (profile != null) {
                    if (userFileMimeType == "application/pdf") {
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
                                val logDir = File(getApplication<Application>().getExternalFilesDir(null), "logs")
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
                        val copiedFile = copyFileToInternalStorage(uri, conversationId)
                        if (copiedFile == null) {
                            addMessageToConversation(
                                conversationId,
                                ChatMessage(sender = "Error", content = "Failed to save the PDF file for processing.")
                            )
                            updateConversationInferenceStatus(conversationId, "Error")
                            return@launch
                        }
                        userFileUri = copiedFile.uri
                        userFileName = copiedFile.fileName

                        val userMessage = ChatMessage(
                            sender = "You",
                            content = finalPrompt,
                            fileUri = userFileUri,
                            fileName = userFileName,
                            fileMimeType = userFileMimeType
                        )
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
                        pdfProcessingJobs[conversationId] = pdfProcessor.process(conversationId, userFileUri!!, finalPrompt, profile.imageQuality, profile.pdfScale)

                        return@launch
                    } else if (userFileMimeType?.startsWith("image/") == true) {
                        val copiedFile = copyFileToInternalStorage(uri, conversationId)
                        if (copiedFile != null) {
                            userFileUri = copiedFile.uri
                            userFileName = copiedFile.fileName
                            imageFileToBase64(copiedFile.uri, profile.imageQuality)?.let { base64 ->
                                imagesBase64 = listOf(base64)
                            }
                        } else {
                            userFileUri = null
                        }
                    } else {
                        userFileName?.let {
                            finalPrompt = "$prompt\n\n--- Attached File ---\n$it"
                        }
                        if (userFileMimeType?.startsWith("text/") == true) {
                            readFileContent(uri)?.let { content ->
                                finalPrompt = "$prompt\n\n--- Document Content ---\n$content"
                            }
                        }
                    }
                }
            }

            val userMessage = ChatMessage(
                sender = "You",
                content = finalPrompt,
                fileUri = userFileUri,
                fileName = userFileName,
                fileMimeType = userFileMimeType
            )
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
                            val systemPromptContent = conversation.systemPromptId?.let { id ->
                                systemPrompts.value.find { it.id == id }?.content
                            }
                            val request = OllamaRequest(
                                model = profile.model,
                                prompt = finalPrompt,
                                stream = true,
                                images = imagesBase64,
                                system = systemPromptContent
                            )
                            val responseBody = ollamaApi.generateOllamaStream(url = url, request = request.copy(options = mapOf("num_ctx" to profile.contextLength)))
                            updateConversationInferenceStatus(conversationId, "Waiting for response...")
                            val responseStream = responseBody.byteStream().bufferedReader()

                            var ollamaMessage = ChatMessage(sender = "Ollama", content = "")
                            withContext(Dispatchers.Main) {
                                addMessageToConversation(conversationId, ollamaMessage)
                            }

                            var firstChunk = true
                            var rawBuffer = ""
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
                                        rawBuffer += ollamaResponse.response
                                        val (thinking, mainContent, thinkingDone) = extractThinkingContent(rawBuffer)
                                        ollamaMessage = ollamaMessage.copy(
                                            content = mainContent,
                                            thinkingContent = thinking,
                                            isThinkingDone = thinkingDone,
                                            // Auto-collapse when thinking is done, keep expanded while streaming
                                            isThinkingExpanded = !thinkingDone
                                        )
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

                            val systemPromptContent = conversation.systemPromptId?.let { id ->
                                systemPrompts.value.find { it.id == id }?.content
                            }
                            val systemMessages = if (!systemPromptContent.isNullOrBlank()) {
                                listOf(OpenAIRequestMessage(role = "system", content = listOf(OpenAITextContent(text = systemPromptContent))))
                            } else {
                                emptyList()
                            }

                            val content = mutableListOf<OpenAIContent>()
                            content.add(OpenAITextContent(text = finalPrompt))
                            imagesBase64?.forEach {
                                val imageUrl = "data:image/jpeg;base64,$it"
                                content.add(OpenAIImageContent(image_url = OpenAIImageUrl(url = imageUrl)))
                            }
                            val messages = systemMessages + previousMessages + listOf(OpenAIRequestMessage(role = "user", content = content))
                            val request = OpenAIRequest(
                                model = profile.model,
                                messages = messages,
                                stream = true,
                                stream_options = OpenAIStreamOptions(include_usage = true)
                            )
                            updateConversationInferenceStatus(conversationId, "Sending...")
                            val responseBody = ollamaApi.generateOpenAIStream(url = url, request = request.copy(max_tokens = profile.contextLength))
                            updateConversationInferenceStatus(conversationId, "Waiting for response...")
                            val responseStream = responseBody.byteStream().bufferedReader()

                            var ollamaMessage = ChatMessage(sender = "Ollama", content = "")
                            withContext(Dispatchers.Main) {
                                addMessageToConversation(conversationId, ollamaMessage)
                            }

                            var firstChunk = true
                            var rawBuffer = ""
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
                                        val deltaObj = openAIResponse.choices?.firstOrNull()?.delta
                                        val deltaContent = deltaObj?.content ?: ""
                                        val deltaReasoning = deltaObj?.getReasoningOrThinking() ?: ""

                                        if (deltaReasoning.isNotEmpty()) {
                                            // Backend provides reasoning_content separately (e.g. DeepSeek API)
                                            // Accumulate reasoning and content independently
                                            val currentThinking = (ollamaMessage.thinkingContent ?: "") + deltaReasoning
                                            rawBuffer += deltaContent
                                            ollamaMessage = ollamaMessage.copy(
                                                content = rawBuffer,
                                                thinkingContent = currentThinking,
                                                isThinkingDone = false, // will be set done when content starts flowing
                                                isThinkingExpanded = rawBuffer.isBlank() // expanded while no content yet
                                            )
                                            Log.d("MainViewModel", "Updated thinkingContent: '${currentThinking.take(50)}...'")
                                        } else if (ollamaMessage.thinkingContent != null && deltaContent.isNotEmpty()) {
                                            // Thinking was in progress but now content started flowing
                                            // Mark thinking as done
                                            rawBuffer += deltaContent
                                            ollamaMessage = ollamaMessage.copy(
                                                content = rawBuffer,
                                                isThinkingDone = true,
                                                isThinkingExpanded = false
                                            )
                                            Log.d("MainViewModel", "Thinking done, content started: '${rawBuffer.take(50)}...'")
                                        } else {
                                            // Backend embeds <think> tags inside content (e.g. Ollama /v1)
                                            rawBuffer += deltaContent
                                            val (thinking, mainContent, thinkingDone) = extractThinkingContent(rawBuffer)
                                            ollamaMessage = ollamaMessage.copy(
                                                content = mainContent,
                                                thinkingContent = thinking,
                                                isThinkingDone = thinkingDone,
                                                // Auto-collapse when thinking is done, keep expanded while streaming
                                                isThinkingExpanded = !thinkingDone
                                            )
                                        }

                                        if (openAIResponse.usage != null) {
                                            ollamaMessage = ollamaMessage.copy(performance = formatOpenAIUsage(openAIResponse.usage))
                                        }

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
                    // Preserve user-controlled UI state from the existing last message
                    val lastMsg = last()
                    val preserved = message.copy(
                        isExpanded = lastMsg.isExpanded,
                        // Preserve isThinkingExpanded if user has manually toggled it.
                        // User toggle happens when lastMsg.isThinkingExpanded != message.isThinkingExpanded
                        // (message's value is computed from streaming logic, lastMsg has user's preference)
                        isThinkingExpanded = if (lastMsg.isThinkingExpanded != message.isThinkingExpanded) {
                            lastMsg.isThinkingExpanded  // User manually toggled, preserve their choice
                        } else {
                            message.isThinkingExpanded  // No user intervention, use computed value
                        }
                    )
                    removeAt(lastIndex)
                    add(preserved)
                } else {
                    add(message)
                }
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
                        val request = OpenAIRequest(model = profile.model, messages = messages, stream = true)
                        val responseBody = ollamaApi.generateOpenAIStream(url = url, request = request)
                        val responseStream = responseBody.byteStream().bufferedReader()
                        val titleBuilder = StringBuilder()
                        responseStream.use {
                            var line: String?
                            while (true) {
                                line = it.readLine()
                                if (line == null) break
                                if (!line.startsWith("data:")) continue
                                val data = line.substringAfter("data: ").trim()
                                if (data == "[DONE]") break
                                try {
                                    val openAIResponse = json.decodeFromString<OpenAIStreamResponse>(data)
                                    val deltaContent = openAIResponse.choices?.firstOrNull()?.delta?.content ?: ""
                                    titleBuilder.append(deltaContent)
                                } catch (e: Exception) {
                                    Log.e("MainViewModel", "Error parsing title stream line: $line", e)
                                }
                            }
                        }
                        val title = titleBuilder.toString().trim().replace("\"", "").ifBlank { "Untitled" }
                        withContext(Dispatchers.Main) {
                            updateConversationTitle(conversationId, title)
                        }
                    }
                    else -> {}
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
                val logDir = File(getApplication<Application>().getExternalFilesDir(null), "logs")
                if (!logDir.exists()) { logDir.mkdirs() }
                val logFile = File(logDir, "ollama_ps_log.txt")
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
                val logDir = File(getApplication<Application>().getExternalFilesDir(null), "logs")
                if (!logDir.exists()) { logDir.mkdirs() }
                val logFile = File(logDir, "ollama_ps_log.txt")
                logFile.appendText("${getCurrentTimestamp()} - Error fetching running models: $errorMessage\n")
            }
        }
    }

    fun clearRunningModelsError() {
        _runningModelsError.value = null
    }

    fun listOllamaModels() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _ollamaModels.value = emptyList()
                _ollamaModelsError.value = "Loading..."
            }
            try {
                val profile = activeProfile.value ?: return@launch
                val url = if (profile.tagsPath.isNotBlank()) {
                    profile.tagsPath
                } else {
                    profile.apiHost.removeSuffix("/") + "/api/tags"
                }
                val response = ollamaApi.listOllamaModels(url = url)

                withContext(Dispatchers.Main) {
                    _ollamaModels.value = response.models
                    _ollamaModelsError.value = null
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error listing ollama models", e)
                val errorMessage = e.message ?: "Unknown error listing ollama models"
                withContext(Dispatchers.Main) {
                    _ollamaModelsError.value = errorMessage
                }
            }
        }
    }

    fun showOllamaModel(modelName: String) {
        viewModelScope.launch {
            try {
                val profile = activeProfile.value ?: return@launch
                val url = profile.apiHost.removeSuffix("/") + "/api/show"
                val response = ollamaApi.show(url, ShowRequest(name = modelName))
                _selectedModelDetails.value = response
                _showModelDetailsDialog.value = true
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error showing ollama model", e)
                // Handle error, maybe show a toast
            }
        }
    }

    fun dismissOllamaModelDetailsDialog() {
        _showModelDetailsDialog.value = false
        _selectedModelDetails.value = null
    }


    fun clearOllamaModelsError() {
        _ollamaModelsError.value = null
    }

    fun listLogFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val externalLogDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ollama")
            val logDir = File(getApplication<Application>().getExternalFilesDir(null), "logs")
            val allLogFiles = mutableListOf<LogFileInfo>()
            if (logDir.exists() && logDir.isDirectory) {
                logDir.listFiles { _, name -> name.endsWith(".txt") || name.endsWith(".log") }?.let { files ->
                    files.forEach {
                        allLogFiles.add(LogFileInfo(it, it.length()))
                    }
                }
            }
            if (externalLogDir.exists() && externalLogDir.isDirectory) {
                externalLogDir.listFiles { _, name -> name.endsWith(".txt") || name.endsWith(".log") }?.let { files ->
                    files.forEach {
                        allLogFiles.add(LogFileInfo(it, it.length()))
                    }
                }
            }
            _logFiles.value = allLogFiles; //.sortedByDescending { it.file.lastModified() }
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

    private fun formatOllamaPerformance(response: OllamaResponse): String? {
        val tokens = "Prompt: ${response.prompt_eval_count ?: 0} tokens, Response: ${response.eval_count ?: 0} tokens"
        val speed = response.eval_count?.let {
            if (response.eval_duration != null && response.eval_duration > 0) {
                "%.2f t/s".format(it / (response.eval_duration / 1_000_000_000.0))
            } else null
        }
        return if (speed != null) "$tokens ($speed)" else tokens
    }

    private fun formatOpenAIUsage(usage: OpenAIUsage): String {
        return "Prompt: ${usage.prompt_tokens ?: 0} tokens, Response: ${usage.completion_tokens ?: 0} tokens, Total: ${usage.total_tokens ?: 0} tokens"
    }

    /**
     * Extracts thinking content from <think>...</think> or <thinking>...</thinking> tags.
     * Returns a Triple of (thinkingContent, mainContent, isThinkingDone).
     * - isThinkingDone = false: still streaming inside <think> block
     * - isThinkingDone = true: closing tag received, thinking complete
     * Handles incomplete tags during streaming gracefully.
     * Case-insensitive matching for broader model compatibility.
     */
    private fun extractThinkingContent(raw: String): Triple<String?, String, Boolean> {
        // Support both <think> and <thinking> variants, case-insensitive
        val openRegex = Regex("<think(?:ing)?>")
        val openMatch = openRegex.find(raw)
        if (openMatch == null) {
            // No thinking tag at all
            return Triple(null, raw, false)
        }
        val contentStart = openMatch.range.last + 1
        val closeTag = "</think"
        val closeIdx = raw.indexOf(closeTag, contentStart)
        return if (closeIdx == -1) {
            // Opening tag found but closing tag not yet received (still streaming)
            val thinkingInProgress = raw.substring(contentStart)
            Triple(thinkingInProgress, "", false)
        } else {
            // Find the end of the closing tag (handle </think> and </thinking>)
            val closeEnd = raw.indexOf('>', closeIdx)
            val endIdx = if (closeEnd != -1) closeEnd + 1 else closeIdx + 8
            val thinking = raw.substring(contentStart, closeIdx).trim()
            val main = raw.substring(endIdx).trimStart()
            Triple(thinking.ifBlank { null }, main, true)
        }
    }

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
    }

    fun exportToMarkdown(conversationId: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val conversation = _conversations.value.find { it.id == conversationId }
            if (conversation == null) {
                // Handle conversation not found
                return@launch
            }

            try {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    OutputStreamWriter(outputStream).use { writer ->
                        writer.write("# ${conversation.title}\n\n")
                        conversation.messages.forEach { message ->
                            writer.write("## ${message.sender}\n\n")
                            writer.write("${message.content}\n\n")
                            if (message.fileUri != null) {
                                val fileName = message.fileName ?: "Attached File"
                                writer.write("![${fileName}](${message.fileUri})\n\n")
                            }
                        }
                    }
                }
                // Optionally, show a success message to the user
            } catch (e: IOException) {
                // Handle error
                Log.e("MainViewModel", "Error exporting to Markdown", e)
            }
        }
    }
}
