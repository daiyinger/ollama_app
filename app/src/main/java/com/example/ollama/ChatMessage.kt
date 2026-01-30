package com.example.ollama

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

@Serializable
data class ChatMessage(
    // 添加一个 id 字段，并提供一个默认值
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val content: String,
    @Serializable(with = UriSerializer::class)
    val fileUri: Uri? = null,
    val fileMimeType: String? = null,
    val fileName: String? = null,
    val performance: String? = null,
    val isExpanded: Boolean = true,
    @Transient
    val images: List<String>? = null
)
