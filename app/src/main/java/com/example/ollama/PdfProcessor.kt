package com.example.ollama

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class PdfProcessor(
    private val application: Application,
    private val ollamaApi: OllamaApiService,
    private val json: Json,
    private val activeProfile: StateFlow<OllamaProfile?>,
    private val coroutineScope: CoroutineScope,
    private val onStatusUpdate: (String, String) -> Unit,
    private val onPageProcessed: (String, ChatMessage) -> Unit,
    private val onPdfProcessingStatus: (String, PdfProcessingStatus?) -> Unit
) {

    fun process(conversationId: String, uri: Uri, prompt: String): Job {
        return coroutineScope.launch(Dispatchers.IO) {
            processPdfPageByPage(conversationId, uri, prompt)
        }
    }

    private suspend fun saveBitmapToFile(bitmap: Bitmap, fileName: String): Uri? {
        return withContext(Dispatchers.IO) {
            val imageDir = File(application.filesDir, "images")
            if (!imageDir.exists()) {
                imageDir.mkdirs()
            }
            val imageFile = File(imageDir, fileName)
            try {
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
                imageFile.toUri()
            } catch (e: IOException) {
                Log.e("PdfProcessor", "Error saving bitmap to file", e)
                null
            }
        }
    }

    private suspend fun processPdfPageByPage(conversationId: String, uri: Uri, prompt: String) {
        withContext(Dispatchers.IO) {
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null

            try {
                pfd = application.contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    withContext(Dispatchers.Main) {
                        onStatusUpdate(conversationId, "Error: Could not open file")
                        onPageProcessed(conversationId, ChatMessage(sender = "Error", content = "Could not open PDF file."))
                    }
                    return@withContext
                }
                renderer = PdfRenderer(pfd)
                val pageCount = renderer.pageCount

                for (i in 0 until pageCount) {
                    val currentPage = i + 1
                    withContext(Dispatchers.Main) {
                        onStatusUpdate(conversationId, "Processing page $currentPage/$pageCount...")
                    }

                    var imageBase64: String? = null
                    var imageSize: Long = 0
                    var pageBitmap: Bitmap? = null
                    var cachedImageUri: Uri? = null

                    renderer.openPage(i)?.use { page ->
                        val bitmap = Bitmap.createBitmap(
                            page.width,
                            page.height,
                            Bitmap.Config.ARGB_8888
                        )
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val newBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(newBitmap)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(bitmap, 0f, 0f, null)
                        pageBitmap = newBitmap

                        cachedImageUri = saveBitmapToFile(newBitmap, "pdf_page_${System.currentTimeMillis()}.jpg")

                        val outputStream = ByteArrayOutputStream()
                        newBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                        val byteArray = outputStream.toByteArray()
                        imageSize = byteArray.size.toLong()
                        imageBase64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                    }

                    withContext(Dispatchers.Main) {
                        onPdfProcessingStatus(conversationId, PdfProcessingStatus(currentPage, pageCount, imageSize))
                    }

                    cachedImageUri?.let {
                        val imageMessage = ChatMessage(
                            sender = "You",
                            content = "Page $currentPage/$pageCount",
                            fileUri = it
                        )
                        withContext(Dispatchers.Main) {
                            onPageProcessed(conversationId, imageMessage)
                        }
                    }

                    if (imageBase64 == null) {
                        val errorMessage = "Error processing page $currentPage: Could not convert to image."
                        withContext(Dispatchers.Main) {
                            onPageProcessed(conversationId, ChatMessage(sender = "Error", content = errorMessage))
                        }
                        continue
                    }

                    val pagePrompt = "The following image is a page from a document. Please identify the text on this page and return the recognized result. User prompt: '$prompt'"
                    try {
                        val profile = activeProfile.value ?: return@withContext
                        val url = profile.apiHost.removeSuffix("/") + "/" + profile.apiPath.removePrefix("/")
                        var analysis = ""

                        when (profile.apiMode) {
                            "Ollama" -> {
                                val request = OllamaRequest(model = profile.model, prompt = pagePrompt, stream = false, images = listOf(imageBase64!!))
                                val response = ollamaApi.generateOllama(url = url, request = request)
                                analysis = response.response
                            }
                            "OpenAI API 兼容" -> {
                                val content = mutableListOf<OpenAIContent>()
                                content.add(OpenAITextContent(text = pagePrompt))
                                val imageUrl = "data:image/jpeg;base64,$imageBase64"
                                content.add(OpenAIImageContent(image_url = OpenAIImageUrl(url = imageUrl)))
                                val messages = listOf(OpenAIRequestMessage(role = "user", content = content))
                                val request = OpenAIRequest(model = profile.model, messages = messages, stream = false)
                                val response = ollamaApi.generateOpenAI(url = url, request = request)
                                analysis = response.choices.firstOrNull()?.message?.content ?: ""
                            }
                        }

                        val analysisMessage = ChatMessage(sender = "Ollama", content = "**Page $currentPage:** $analysis")
                        withContext(Dispatchers.Main) {
                            onPageProcessed(conversationId, analysisMessage)
                        }

                    } catch (e: Exception) {
                        Log.e("PdfProcessor", "Error processing page $currentPage", e)
                        val errorMessage = "Error processing page $currentPage: ${e.message}"
                        withContext(Dispatchers.Main) {
                            onPageProcessed(conversationId, ChatMessage(sender = "Error", content = errorMessage))
                        }
                    } finally {
                        pageBitmap?.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfProcessor", "Error processing PDF", e)
                val errorMessage = "Error processing PDF: ${e.message}"
                withContext(Dispatchers.Main) {
                    onPageProcessed(conversationId, ChatMessage(sender = "Error", content = errorMessage))
                    onStatusUpdate(conversationId, "Error: Failed to process PDF")
                }
            } finally {
                renderer?.close()
                pfd?.close()
                withContext(Dispatchers.Main) {
                    onPdfProcessingStatus(conversationId, null)
                    onStatusUpdate(conversationId, "Done")
                }
            }
        }
    }
}
