package com.example.ollama

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ChatMessage(
    val sender: String,
    val content: String,
    @Serializable(with = UriSerializer::class)
    val fileUri: Uri? = null,
    val fileMimeType: String? = null,
    val performance: String? = null,
    val isExpanded: Boolean = true,
    @Transient
    val images: List<String>? = null
)
