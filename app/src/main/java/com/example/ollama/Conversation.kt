package com.example.ollama

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val inferenceStatus: String = "",
    val pdfProcessingStatus: PdfProcessingStatus? = null,
    val profileName: String? = null,
    val createdAt: Long = 0
)
