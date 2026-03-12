package com.example.ollama

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class OllamaProfile(
    val name: String,
    val apiHost: String,
    val apiPath: String,
    val psPath: String,
    val tagsPath: String = "",
    val model: String,
    val apiKey: String,
    val apiMode: String,
    val visionFamilies: String = "clip,vision",
    val checkImageProcessing: Boolean = true,
    val imageQuality: Int = 90,
    val pdfScale: Float = 2.0f,
    val contextLength: Int = 2048,
    val pagePrompt: String = "The following image is a page from a document. Please identify the text on this page and return the recognized result.",
    val temperature: Float? = null,
    val topP: Float? = null,
    val presencePenalty: Float? = null
)

@Serializable
data class SystemPrompt(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val content: String
)

@Serializable
data class AppSettings(
    val profiles: List<OllamaProfile>,
    val activeProfileName: String?,
    val enableSessionLogging: Boolean,
    val systemPrompts: List<SystemPrompt> = emptyList(),
    val conversations: List<Conversation> = emptyList()
)

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ollama_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE_PROFILE_NAME = "active_profile_name"
        const val KEY_CONVERSATIONS = "conversations"
        const val KEY_ENABLE_SESSION_LOGGING = "enable_session_logging"
        const val KEY_EXPANDED_GROUPS = "expanded_groups"
        const val KEY_EXPANDED_DAYS = "expanded_days"
        const val KEY_SYSTEM_PROMPTS = "system_prompts"

        val defaultProfile = OllamaProfile(
            name = "Default",
            apiHost = "http://192.168.10.8:11434",
            apiPath = "/api/generate",
            psPath = "http://1.1.1.1:11434/api/ps",
            tagsPath = "http://192.168.10.8:11434/api/tags",
            model = "llama2",
            apiKey = "",
            apiMode = "Ollama",
            visionFamilies = "clip,vision,qwen2vl",
            checkImageProcessing = true,
            imageQuality = 90,
            pdfScale = 2.0f,
            contextLength = 2048,
            pagePrompt = "The following image is a page from a document. Please identify the text on this page and return the recognized result."
        )
    }

    private val _profilesFlow = MutableStateFlow<List<OllamaProfile>>(emptyList())
    private val _activeProfileFlow = MutableStateFlow<OllamaProfile?>(null)
    private val _systemPromptsFlow = MutableStateFlow<List<SystemPrompt>>(emptyList())

    init {
        val profiles = getProfiles()
        _profilesFlow.value = profiles
        val activeProfileName = prefs.getString(KEY_ACTIVE_PROFILE_NAME, null)
        _activeProfileFlow.value = if (activeProfileName != null) {
            profiles.find { it.name == activeProfileName }
        } else if (profiles.isNotEmpty()) {
            profiles.first()
        } else {
            null
        }
        if (_activeProfileFlow.value == null && profiles.isEmpty()) {
            val newProfiles = listOf(defaultProfile)
            saveProfiles(newProfiles)
            setActiveProfile(defaultProfile.name)
        }
        _systemPromptsFlow.value = getSystemPrompts()
    }

    fun getProfilesFlow(): StateFlow<List<OllamaProfile>> = _profilesFlow.asStateFlow()
    fun getActiveProfileFlow(): StateFlow<OllamaProfile?> = _activeProfileFlow.asStateFlow()
    fun getSystemPromptsFlow(): StateFlow<List<SystemPrompt>> = _systemPromptsFlow.asStateFlow()

    fun getProfiles(): List<OllamaProfile> {
        val jsonString = prefs.getString(KEY_PROFILES, null)
        return if (jsonString != null) {
            try {
                json.decodeFromString<List<OllamaProfile>>(jsonString)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun saveProfiles(profiles: List<OllamaProfile>) {
        val jsonString = json.encodeToString(profiles)
        prefs.edit().putString(KEY_PROFILES, jsonString).apply()
        _profilesFlow.value = profiles
    }

    fun addProfile(profile: OllamaProfile) {
        val currentProfiles = getProfiles().toMutableList()
        currentProfiles.add(profile)
        saveProfiles(currentProfiles)
    }

    fun updateProfile(profile: OllamaProfile) {
        val currentProfiles = getProfiles().toMutableList()
        val index = currentProfiles.indexOfFirst { it.name == profile.name }
        if (index != -1) {
            currentProfiles[index] = profile
            saveProfiles(currentProfiles)
            if (_activeProfileFlow.value?.name == profile.name) {
                _activeProfileFlow.value = profile
            }
        }
    }

    fun deleteProfile(profileName: String) {
        val currentProfiles = getProfiles().toMutableList()
        currentProfiles.removeAll { it.name == profileName }
        saveProfiles(currentProfiles)
        if (_activeProfileFlow.value?.name == profileName) {
            val newActiveProfile = if (currentProfiles.isNotEmpty()) currentProfiles.first() else null
            setActiveProfile(newActiveProfile?.name)
        }
    }

    fun setActiveProfile(profileName: String?) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE_NAME, profileName).apply()
        _activeProfileFlow.value = profileName?.let { name ->
            getProfiles().find { it.name == name }
        }
    }

    fun getApiHost(): String = _activeProfileFlow.value?.apiHost ?: defaultProfile.apiHost
    fun getApiPath(): String = _activeProfileFlow.value?.apiPath ?: defaultProfile.apiPath
    fun getPsPath(): String = _activeProfileFlow.value?.psPath ?: defaultProfile.psPath
    fun getModel(): String = _activeProfileFlow.value?.model ?: defaultProfile.model
    fun getApiKey(): String = _activeProfileFlow.value?.apiKey ?: defaultProfile.apiKey
    fun getApiMode(): String = _activeProfileFlow.value?.apiMode ?: defaultProfile.apiMode
    fun getContextLength(): Int = _activeProfileFlow.value?.contextLength ?: defaultProfile.contextLength
    
    fun getPagePrompt(): String = _activeProfileFlow.value?.pagePrompt ?: defaultProfile.pagePrompt

    fun saveConversations(conversationsJson: String) {
        prefs.edit().putString(KEY_CONVERSATIONS, conversationsJson).apply()
    }

    fun getConversations(): String {
        return prefs.getString(KEY_CONVERSATIONS, "") ?: ""
    }

    fun setEnableSessionLogging(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLE_SESSION_LOGGING, enabled).apply()
    }

    fun getEnableSessionLogging(): Boolean {
        return prefs.getBoolean(KEY_ENABLE_SESSION_LOGGING, true)
    }

    fun getSystemPrompts(): List<SystemPrompt> {
        val jsonString = prefs.getString(KEY_SYSTEM_PROMPTS, null)
        return if (jsonString != null) {
            try {
                json.decodeFromString<List<SystemPrompt>>(jsonString)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun saveSystemPrompts(systemPrompts: List<SystemPrompt>) {
        val jsonString = json.encodeToString(systemPrompts)
        prefs.edit().putString(KEY_SYSTEM_PROMPTS, jsonString).apply()
        _systemPromptsFlow.value = systemPrompts
    }

    fun addSystemPrompt(systemPrompt: SystemPrompt) {
        val current = getSystemPrompts().toMutableList()
        current.add(systemPrompt)
        saveSystemPrompts(current)
    }

    fun updateSystemPrompt(systemPrompt: SystemPrompt) {
        val current = getSystemPrompts().toMutableList()
        val index = current.indexOfFirst { it.id == systemPrompt.id }
        if (index != -1) {
            current[index] = systemPrompt
            saveSystemPrompts(current)
        }
    }

    fun deleteSystemPrompt(id: String) {
        val current = getSystemPrompts().toMutableList()
        current.removeAll { it.id == id }
        saveSystemPrompts(current)
    }

    fun saveExpandedGroups(groups: Set<String>) {
        // SharedPreferences.putStringSet is known to have issues when the same set is modified and saved.
        // It's safer to create a new HashSet or use a comma-separated string if simple set doesn't work.
        prefs.edit().putStringSet(KEY_EXPANDED_GROUPS, HashSet(groups)).apply()
    }

    fun getExpandedGroups(): Set<String> {
        // Return a copy to ensure any modifications don't affect the original set stored in SharedPreferences
        return prefs.getStringSet(KEY_EXPANDED_GROUPS, null)?.toSet() ?: setOf("今天", "昨天")
    }

    fun saveExpandedDays(days: Set<String>) {
        prefs.edit().putStringSet(KEY_EXPANDED_DAYS, HashSet(days)).apply()
    }

    fun getExpandedDays(): Set<String> {
        return prefs.getStringSet(KEY_EXPANDED_DAYS, null)?.toSet() ?: setOf("今天", "昨天")
    }

    fun exportSettings(includeConversations: Boolean = true): String {
        val profiles = getProfiles()
        val activeProfileName = prefs.getString(KEY_ACTIVE_PROFILE_NAME, null)
        val enableSessionLogging = getEnableSessionLogging()

        val conversations = if (includeConversations) {
            val conversationsJson = getConversations()
            if (conversationsJson.isNotEmpty()) {
                try {
                    json.decodeFromString<List<Conversation>>(conversationsJson)
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        val appSettings = AppSettings(
            profiles = profiles,
            activeProfileName = activeProfileName,
            enableSessionLogging = enableSessionLogging,
            systemPrompts = getSystemPrompts(),
            conversations = conversations
        )
        return json.encodeToString(appSettings)
    }

    fun importSettings(settingsJson: String) {
        try {
            val appSettings = json.decodeFromString<AppSettings>(settingsJson)
            saveProfiles(appSettings.profiles)
            setActiveProfile(appSettings.activeProfileName)
            setEnableSessionLogging(appSettings.enableSessionLogging)
            saveSystemPrompts(appSettings.systemPrompts)

            // Only update conversations if they are present in the import data
            if (appSettings.conversations.isNotEmpty()) {
                val conversationsJson = json.encodeToString(appSettings.conversations)
                saveConversations(conversationsJson)
            }

            // After import, we need to reload the profiles and active profile.
            val profiles = getProfiles()
            _profilesFlow.value = profiles
            val activeProfileName = prefs.getString(KEY_ACTIVE_PROFILE_NAME, null)
            _activeProfileFlow.value = if (activeProfileName != null) {
                profiles.find { it.name == activeProfileName }
            } else if (profiles.isNotEmpty()) {
                profiles.first()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("SettingsManager", "Error importing settings", e)
        }
    }
}
