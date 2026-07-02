package com.chatgemma.app.ui.screens.imagegen

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatgemma.app.ai.ImageGenerationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.random.Random

data class ImageGenUiState(
    val prompt: String = "",
    val iterations: Int = 20,
    val seed: Int = 0,
    val useRandomSeed: Boolean = true,
    val condition: ImageGenerationEngine.Condition = ImageGenerationEngine.Condition.NONE,
    val conditionImageUri: Uri? = null,
    val result: Bitmap? = null,
    val isGenerating: Boolean = false,
    val error: String? = null,
    val savedMessage: String? = null,
    val modelStatus: ImageGenerationEngine.ModelStatus? = null
)

@HiltViewModel
class ImageGenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: ImageGenerationEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImageGenUiState())
    val uiState: StateFlow<ImageGenUiState> = _uiState.asStateFlow()

    init {
        refreshModelStatus()
    }

    fun refreshModelStatus() {
        _uiState.update { it.copy(modelStatus = engine.modelStatus()) }
    }

    fun setPrompt(value: String) = _uiState.update { it.copy(prompt = value) }
    fun setIterations(value: Int) = _uiState.update { it.copy(iterations = value.coerceIn(5, 50)) }
    fun setSeed(value: Int) = _uiState.update { it.copy(seed = value, useRandomSeed = false) }
    fun setUseRandomSeed(value: Boolean) = _uiState.update { it.copy(useRandomSeed = value) }
    fun dismissError() = _uiState.update { it.copy(error = null) }
    fun dismissSavedMessage() = _uiState.update { it.copy(savedMessage = null) }

    fun setCondition(condition: ImageGenerationEngine.Condition) =
        _uiState.update {
            it.copy(
                condition = condition,
                conditionImageUri = if (condition == ImageGenerationEngine.Condition.NONE) null
                                    else it.conditionImageUri
            )
        }

    fun setConditionImage(uri: Uri?) = _uiState.update { it.copy(conditionImageUri = uri) }

    fun generate() {
        val state = _uiState.value
        if (state.prompt.isBlank() || state.isGenerating) return

        viewModelScope.launch {
            val seed = if (state.useRandomSeed) Random.nextInt(0, Int.MAX_VALUE) else state.seed
            _uiState.update { it.copy(isGenerating = true, error = null, seed = seed) }
            try {
                val useCondition = state.condition != ImageGenerationEngine.Condition.NONE &&
                    state.conditionImageUri != null
                val conditionBitmap = if (useCondition) {
                    uriToBitmap(state.conditionImageUri!!)
                        ?: error("Could not read the condition image")
                } else null

                val bitmap = engine.generate(
                    prompt = state.prompt.trim(),
                    iterations = state.iterations,
                    seed = seed,
                    condition = if (useCondition) state.condition else ImageGenerationEngine.Condition.NONE,
                    conditionSource = conditionBitmap
                )
                _uiState.update { it.copy(isGenerating = false, result = bitmap) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isGenerating = false, error = e.message ?: "Image generation failed")
                }
            }
        }
    }

    fun saveToGallery() {
        val bitmap = _uiState.value.result ?: return
        viewModelScope.launch {
            try {
                val location = withContext(Dispatchers.IO) { saveBitmap(bitmap) }
                _uiState.update { it.copy(savedMessage = "Saved to $location") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Save failed: ${e.message}") }
            }
        }
    }

    private fun saveBitmap(bitmap: Bitmap): String {
        val fileName = "chatgemma_${System.currentTimeMillis()}.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/ChatGemma")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: error("Could not open output stream")
            "Pictures/ChatGemma/$fileName"
        } else {
            // Pre-Q: writing to shared Pictures needs a runtime permission, so
            // save into the app's external pictures dir instead.
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: context.filesDir
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) { null }
    }
}
