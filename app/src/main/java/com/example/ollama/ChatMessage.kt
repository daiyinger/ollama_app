package com.example.ollama

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val sender: String,
    val content: String,
    @Serializable(with = UriSerializer::class)
    val fileUri: Uri? = null,
    val performance: String? = null
)
