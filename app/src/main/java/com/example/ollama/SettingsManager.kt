package com.example.ollama

import android.content.Context
import android.content.SharedPreferences
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
    val model: String,
    val apiKey: String,
    val apiMode: String
)

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ollama_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE_PROFILE_NAME = "active_profile_name"
        const val KEY_CONVERSATIONS = "conversations"

        val defaultProfile = OllamaProfile(
            name = "Default",
            apiHost = "http://192.168.10.8:11434",
            apiPath = "/api/generate",
            psPath = "http://192.168.10.8:11434/api/ps",
            model = "llama2",
            apiKey = "",
            apiMode = "Ollama"
        )
    }

    private val _profilesFlow = MutableStateFlow<List<OllamaProfile>>(emptyList())
    private val _activeProfileFlow = MutableStateFlow<OllamaProfile?>(null)

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
        if (_activeProfileFlow.value == null && profiles.isEmpty()){
            val newProfiles = listOf(defaultProfile)
            saveProfiles(newProfiles)
            setActiveProfile(defaultProfile.name)
        }
    }

    fun getProfilesFlow(): StateFlow<List<OllamaProfile>> = _profilesFlow.asStateFlow()
    fun getActiveProfileFlow(): StateFlow<OllamaProfile?> = _activeProfileFlow.asStateFlow()

    fun getProfiles(): List<OllamaProfile> {
        val json = prefs.getString(KEY_PROFILES, null)
        return if (json != null) {
            try {
                Json.decodeFromString<List<OllamaProfile>>(json)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun saveProfiles(profiles: List<OllamaProfile>) {
        val json = Json.encodeToString(profiles)
        prefs.edit().putString(KEY_PROFILES, json).apply()
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

    fun saveConversations(conversationsJson: String) {
        prefs.edit().putString(KEY_CONVERSATIONS, conversationsJson).apply()
    }

    fun getConversations(): String {
        return prefs.getString(KEY_CONVERSATIONS, "") ?: ""
    }
}