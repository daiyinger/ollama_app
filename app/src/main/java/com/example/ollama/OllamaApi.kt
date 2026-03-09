package com.example.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

// --- Data classes for different API formats ---

/** Request for the standard Ollama API (`/api/generate`) */
@Serializable
data class OllamaRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false,
    val images: List<String>? = null,
    val options: Map<String, Int>? = null,
    val system: String? = null
)

/** Response from the standard Ollama API */
@Serializable
data class OllamaResponse(
    val response: String,
    val total_duration: Long? = null,
    val load_duration: Long? = null,
    val prompt_eval_count: Int? = null,
    val prompt_eval_duration: Long? = null,
    val eval_count: Int? = null,
    val eval_duration: Long? = null,
    val done: Boolean? = null
)

// --- OpenAI Compatible API ---

// -- Common --
/** Base class for content in a message */
@JsonClassDiscriminator("type")
@Serializable
sealed class OpenAIContent

/** Text content in a message */
@Serializable
@SerialName("text")
data class OpenAITextContent(val text: String) : OpenAIContent()

/** Image content in a message */
@Serializable
@SerialName("image_url")
data class OpenAIImageContent(val image_url: OpenAIImageUrl) : OpenAIContent()

/** Image URL in a message */
@Serializable
data class OpenAIImageUrl(val url: String)


// -- Request --
/** A single message in the OpenAI request */
@Serializable
data class OpenAIRequestMessage(val role: String, val content: List<OpenAIContent>)

/** Request for the OpenAI compatible API (`/v1/chat/completions`) */
@Serializable
data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIRequestMessage>,
    val stream: Boolean = false,
    val max_tokens: Int? = null,
    val stream_options: OpenAIStreamOptions? = null
)

@Serializable
data class OpenAIStreamOptions(
    val include_usage: Boolean
)

// -- Response --
/** A single message in the OpenAI response */
@Serializable
data class OpenAIMessage(val role: String, val content: String?)

/** A single choice in the OpenAI response */
@Serializable
data class OpenAIChoice(val message: OpenAIMessage)

/** The usage stats for an OpenAI response */
@Serializable
data class OpenAIUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)

/** Response from the OpenAI compatible API */
@Serializable
data class OpenAIResponse(val choices: List<OpenAIChoice>, val usage: OpenAIUsage? = null)

// -- Streaming Response --
@Serializable
data class OpenAIStreamChoice(
    val delta: OpenAIStreamDelta,
    val finish_reason: String? = null
)

@Serializable
data class OpenAIStreamDelta(
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("thinking")
    val thinking: String? = null,
    @SerialName("reasoning")
    val reasoning: String? = null
) {
    /** Get reasoning/thinking content from any field */
    fun getReasoningOrThinking(): String? = reasoningContent ?: thinking ?: reasoning
}

@Serializable
data class OpenAIStreamResponse(
    val choices: List<OpenAIStreamChoice>? = null,
    val usage: OpenAIUsage? = null
)

@Serializable
data class ShowRequest(
    val name: String
)

@Serializable
data class ShowResponse(
    val details: OllamaPsModelDetails,
    val parameters: Map<String, Int>? = null,
    val capabilities: List<String>? = null
)


/** Response from the ps API */
@Serializable
data class OllamaPsResponse(
    val models: List<OllamaPsModel>
)

@Serializable
data class OllamaPsModel(
    val name: String,
    val model: String,
    val size: Long,
    @SerialName("quantization_level")
    val quantizationLevel: String? = null,
    val details: OllamaPsModelDetails,
    @SerialName("expires_at")
    val expiresAt: String? = null
)

@Serializable
data class OllamaPsModelDetails(
    @SerialName("parent_model")
    val parentModel: String,
    val format: String,
    val family: String,
    val families: List<String>?,
    @SerialName("parameter_size")
    val parameterSize: String,
    @SerialName("quantization_level")
    val quantizationLevel: String? = null
)


/** Retrofit Service Interface */
interface OllamaApiService {

    /** Call the standard Ollama endpoint */
    @POST
    suspend fun generateOllama(@Url url: String, @Body request: OllamaRequest): OllamaResponse

    /** Call the OpenAI compatible endpoint */
    @POST
    suspend fun generateOpenAI(@Url url: String, @Body request: OpenAIRequest): OpenAIResponse

    /** Call the standard Ollama endpoint for streaming */
    @POST
    @Streaming
    suspend fun generateOllamaStream(@Url url: String, @Body request: OllamaRequest): ResponseBody

    /** Call the OpenAI compatible endpoint for streaming */
    @POST
    @Streaming
    suspend fun generateOpenAIStream(@Url url: String, @Body request: OpenAIRequest): ResponseBody

    /** Get running models */
    @GET
    suspend fun getRunningModels(@Url url: String): OllamaPsResponse

    /** Show model information */
    @POST
    suspend fun show(@Url url: String, @Body request: ShowRequest): ShowResponse

    /** List ollama models */
    @GET
    suspend fun listOllamaModels(@Url url: String): OllamaModelsList
}
