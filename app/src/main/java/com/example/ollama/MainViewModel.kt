package com.example.ollama

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(application)
    private val json = Json { ignoreUnknownKeys = true }

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _selectedFileUri = MutableStateFlow<Uri?>(null)
    val selectedFileUri: StateFlow<Uri?> = _selectedFileUri.asStateFlow()

    private val _connectionStatus = MutableStateFlow("")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _inferenceStatus = MutableStateFlow("")
    val inferenceStatus: StateFlow<String> = _inferenceStatus.asStateFlow()

    private val _runningModels = MutableStateFlow<List<OllamaPsModel>>(emptyList())
    val runningModels: StateFlow<List<OllamaPsModel>> = _runningModels.asStateFlow()

    private val _runningModelsError = MutableStateFlow<String?>(null)
    val runningModelsError: StateFlow<String?> = _runningModelsError.asStateFlow()

    private val _logContent = MutableStateFlow("")
    val logContent: StateFlow<String> = _logContent.asStateFlow()

    val profiles: StateFlow<List<OllamaProfile>> = settingsManager.getProfilesFlow()
    val activeProfile: StateFlow<OllamaProfile?> = settingsManager.getActiveProfileFlow()

    private lateinit var ollamaApi: OllamaApiService
    private lateinit var ollamaApiPs: OllamaApiService

    init {
        viewModelScope.launch {
            settingsManager.getActiveProfileFlow().collect { createOllamaService() }
        }
        loadConversations()
        createOllamaService()
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
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        val psOkHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
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
        val newConversation = Conversation(title = "New Conversation")
        _conversations.value = _conversations.value + newConversation
        saveConversations()
        return newConversation
    }

    fun deleteConversation(conversationId: String) {
        _conversations.value = _conversations.value.filter { it.id != conversationId }
        saveConversations()
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
        val conversation = _conversations.value.find { it.id == conversationId }
        if (conversation != null) {
            _messages.value = conversation.messages
        } else {
            // Handle case where conversation is not found, maybe create a new one or show an error
            _messages.value = emptyList()
        }
    }

    fun onFileSelected(uri: Uri) {
        _selectedFileUri.value = uri
    }

    fun clearSelectedFile() {
        _selectedFileUri.value = null
    }

    private fun fileToBase64(uri: Uri): String? {
        return try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (e: Exception)
        {
            Log.e("MainViewModel", "Error converting file to Base64", e)
            null
        }
    }

    fun sendMessage(prompt: String, conversationId: String) {
        if (prompt.isBlank() && _selectedFileUri.value == null) return

        val conversation = _conversations.value.find { it.id == conversationId }
        if (conversation == null) return

        val userMessage = ChatMessage(sender = "You", content = prompt, fileUri = _selectedFileUri.value)
        addMessageToConversation(conversationId, userMessage)

        val fileUri = _selectedFileUri.value
        _selectedFileUri.value = null

        viewModelScope.launch(Dispatchers.IO) {
            val fileBase64 = fileUri?.let { fileToBase64(it) }

            try {
                val profile = activeProfile.value ?: return@launch
                val url = profile.apiHost.removeSuffix("/") + "/" + profile.apiPath.removePrefix("/")
                withContext(Dispatchers.Main) {
                    _connectionStatus.value = "Connecting..."
                }

                when (profile.apiMode) {
                    "Ollama" -> {
                        val request = OllamaRequest(model = profile.model, prompt = prompt, stream = true, images = fileBase64?.let { listOf(it) })
                        val responseBody = ollamaApi.generateOllamaStream(url = url, request = request)
                        withContext(Dispatchers.Main) {
                            _connectionStatus.value = ""
                        }
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
                                    withContext(Dispatchers.Main) {
                                        _inferenceStatus.value = "Inferencing..."
                                    }
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
                                            _inferenceStatus.value = "Done"
                                        }
                                        break
                                    }
                                } catch (e: Exception) {
                                    Log.e("MainViewModel", "Error parsing JSON line: $line", e)
                                    withContext(Dispatchers.Main) {
                                        _inferenceStatus.value = "Error"
                                    }
                                }
                            }
                        }
                    }
                    "OpenAI API 兼容" -> {
                        val previousMessages = conversation.messages.mapNotNull { msg ->
                            val role = if (msg.sender == "You") "user" else "assistant"
                            val content = mutableListOf<OpenAIContent>()
                            content.add(OpenAITextContent(text = msg.content))
                            msg.fileUri?.let { uri ->
                                fileToBase64(uri)?.let {
                                    val imageUrl = "data:image/jpeg;base64,$it"
                                    content.add(OpenAIImageContent(image_url = OpenAIImageUrl(url = imageUrl)))
                                }
                            }
                            OpenAIRequestMessage(role = role, content = content)
                        }

                        val content = mutableListOf<OpenAIContent>()
                        content.add(OpenAITextContent(text = prompt))
                        fileBase64?.let {
                            val imageUrl = "data:image/jpeg;base64,$it"
                            content.add(OpenAIImageContent(image_url = OpenAIImageUrl(url = imageUrl)))
                        }
                        val messages = previousMessages + listOf(OpenAIRequestMessage(role = "user", content = content))
                        val request = OpenAIRequest(model = profile.model, messages = messages, stream = true)
                        val responseBody = ollamaApi.generateOpenAIStream(url = url, request = request)
                        withContext(Dispatchers.Main) {
                            _connectionStatus.value = ""
                        }
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
                                    withContext(Dispatchers.Main) {
                                        _inferenceStatus.value = "Inferencing..."
                                    }
                                    firstChunk = false
                                }

                                val data = line.substringAfter("data: ").trim()
                                if (data == "[DONE]") {
                                    withContext(Dispatchers.Main) {
                                        _inferenceStatus.value = "Done"
                                    }
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
                                    withContext(Dispatchers.Main) {
                                        _inferenceStatus.value = "Error"
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        withContext(Dispatchers.Main) {
                            addMessageToConversation(conversationId, ChatMessage(sender = "Error", content = "Unsupported API mode"))
                            _connectionStatus.value = "Error"
                        }
                    }
                }
                // Auto-generate title for new conversations
                val currentConversation = _conversations.value.find { it.id == conversationId }
                if (currentConversation != null && currentConversation.title == "New Conversation" && currentConversation.messages.size > 1) {
                    generateConversationTitle(conversationId)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addMessageToConversation(
                        conversationId,
                        ChatMessage(sender = "Error", content = e.message ?: "Unknown error")
                    )
                }
                Log.e("MainViewModel", "Error sending message", e)
                withContext(Dispatchers.Main) {
                    _connectionStatus.value = "Error: Connection failed"
                }
            } finally {
                delay(2000)
                withContext(Dispatchers.Main) {
                    _connectionStatus.value = ""
                    _inferenceStatus.value = ""
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
            _messages.value = newMessages
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
            _messages.value = newMessages
            saveConversations()
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
                withContext(Dispatchers.Main) {
                    _runningModels.value = response.models
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

    fun readLogFile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val logDir = getApplication<Application>().filesDir
                val logFile = File(logDir, "ollama_log.txt")
                if (logFile.exists()) {
                    val content = logFile.readText()
                    withContext(Dispatchers.Main) {
                        _logContent.value = content
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _logContent.value = "Log file not found."
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error reading log file", e)
                withContext(Dispatchers.Main) {
                    _logContent.value = "Error reading log file: ${e.message}"
                }
            }
        }
    }

    fun clearLogFile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val logDir = getApplication<Application>().filesDir
                val logFile = File(logDir, "ollama_log.txt")
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