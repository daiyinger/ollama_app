package com.example.ollama

import android.content.Context
import android.os.Environment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.io.IOException

class RequestLoggingInterceptor(private val context: Context) : Interceptor {

    @Volatile
    var logTag: String? = null

    private val settingsManager = SettingsManager(context)
    private val json = Json { ignoreUnknownKeys = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!settingsManager.getEnableSessionLogging()) {
            return chain.proceed(chain.request())
        }

        val currentLogTag = logTag
        if (currentLogTag == null) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val requestBody = request.body
        val requestBodyString = if (requestBody != null) {
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            buffer.readUtf8()
        } else {
            ""
        }

        val sanitizedRequestBodyString = sanitizeBody(requestBodyString)

        val logMessage = """
        Request:
        URL: ${request.url}
        Method: ${request.method}
        Headers:
${request.headers.toString().prependIndent("  ")}
        Body:
$sanitizedRequestBodyString
        """.trimIndent()

        try {
            val logDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ollama")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            val logFile = File(logDir, "${currentLogTag}.log")
            logFile.appendText(logMessage + "\n----------------------------------------\n")
        } catch (e: IOException) {
            // Log to logcat or handle error
            e.printStackTrace()
        }

        return chain.proceed(request)
    }

    private fun sanitizeBody(body: String): String {
        return try {
            val jsonElement = json.parseToJsonElement(body)
            if (jsonElement !is JsonObject) return body

            val originalObject = jsonElement.jsonObject

            // Ollama: "images": ["...", "..."]
            if (originalObject.containsKey("images")) {
                val newObject = buildJsonObject {
                    originalObject.forEach { (key, value) ->
                        if (key == "images") {
                            put(key, JsonPrimitive("[base64 data omitted]"))
                        } else {
                            put(key, value)
                        }
                    }
                }
                return newObject.toString()
            }

            // OpenAI: "messages": [ ..., "content": [ ..., { "type": "image_url", ...}]]
            if (originalObject.containsKey("messages")) {
                val newMessages = buildJsonArray {
                    originalObject["messages"]?.jsonArray?.forEach { messageElement ->
                        val messageObject = messageElement.jsonObject
                        if (messageObject.containsKey("content")) {
                            val newContent = buildJsonArray {
                                messageObject["content"]?.jsonArray?.forEach { contentElement ->
                                    val contentObject = contentElement.jsonObject
                                    if (contentObject["type"]?.toString()?.contains("image_url") == true) {
                                        add(buildJsonObject {
                                            put("type", "image_url")
                                            put("image_url", JsonPrimitive("data:image/jpeg;base64,...base64 data omitted..."))
                                        })
                                    } else {
                                        add(contentElement)
                                    }
                                }
                            }
                            val newMsg = buildJsonObject {
                                messageObject.forEach { (key, value) ->
                                    if (key == "content") {
                                        put(key, newContent)
                                    } else {
                                        put(key, value)
                                    }
                                }
                            }
                            add(newMsg)
                        } else {
                            add(messageElement)
                        }
                    }
                }
                val newObject = buildJsonObject {
                    originalObject.forEach { (key, value) ->
                        if (key == "messages") {
                            put(key, newMessages)
                        } else {
                            put(key, value)
                        }
                    }
                }
                return newObject.toString()
            }

            body
        } catch (e: Exception) {
            body // Not a valid JSON or other parsing error, return original string
        }
    }
}
