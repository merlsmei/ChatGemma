package com.chatgemma.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator.ConditionOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device text→image generation via MediaPipe's Image Generator task
 * (a converted Stable Diffusion model directory on device storage).
 *
 * An optional condition image (edge / depth / face landmarks, extracted from a
 * user-supplied source photo by the matching ControlNet-style plugin model)
 * guides the diffusion alongside the text prompt.
 *
 * Expected file layout under [baseDir] (see [modelStatus]):
 *   model/                          — converted SD 1.5 weight files
 *   plugins/edge_plugin.tflite      — Canny edge plugin (optional)
 *   plugins/depth_plugin.tflite     — depth plugin (optional)
 *   plugins/face_plugin.tflite      — face landmark plugin (optional)
 *   aux/depth_model.tflite          — depth estimation model (needed by DEPTH)
 *   aux/face_landmarker.task        — face landmarker (needed by FACE)
 */
@Singleton
class ImageGenerationEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class Condition { NONE, EDGE, DEPTH, FACE }

    data class ModelStatus(
        val baseDir: String,
        val modelPresent: Boolean,
        val availableConditions: Set<Condition>
    )

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val generationMutex = Mutex()

    fun baseDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "imagegen")

    private fun modelDir()  = File(baseDir(), "model")
    private fun pluginFile(c: Condition) = File(baseDir(), "plugins/${c.name.lowercase()}_plugin.tflite")
    private fun depthModelFile() = File(baseDir(), "aux/depth_model.tflite")
    private fun faceModelFile()  = File(baseDir(), "aux/face_landmarker.task")

    /** Which files are in place; drives the UI's guidance text. */
    fun modelStatus(): ModelStatus {
        val model = modelDir()
        val conditions = buildSet {
            add(Condition.NONE)
            if (pluginFile(Condition.EDGE).exists()) add(Condition.EDGE)
            if (pluginFile(Condition.DEPTH).exists() && depthModelFile().exists()) add(Condition.DEPTH)
            if (pluginFile(Condition.FACE).exists() && faceModelFile().exists()) add(Condition.FACE)
        }
        return ModelStatus(
            baseDir = baseDir().absolutePath,
            modelPresent = model.isDirectory && (model.listFiles()?.isNotEmpty() == true),
            availableConditions = conditions
        )
    }

    /**
     * Runs one generation. Creation + generation take tens of seconds on-device;
     * the generator is created per call and closed afterwards so the ~1-2 GB
     * weights don't stay resident next to the chat LLM.
     */
    suspend fun generate(
        prompt: String,
        iterations: Int,
        seed: Int,
        condition: Condition = Condition.NONE,
        conditionSource: Bitmap? = null
    ): Bitmap = generationMutex.withLock {
        _isGenerating.value = true
        try {
            withContext(Dispatchers.Default) {
                val status = modelStatus()
                check(status.modelPresent) {
                    "No image generation model found. Copy a converted Stable Diffusion " +
                        "model into ${modelDir().absolutePath}"
                }
                val options = ImageGenerator.ImageGeneratorOptions.builder()
                    .setImageGeneratorModelDirectory(modelDir().absolutePath)
                    .build()

                val useCondition = condition != Condition.NONE && conditionSource != null
                val generator = if (useCondition) {
                    check(condition in status.availableConditions) {
                        "Missing plugin/aux model files for $condition conditioning " +
                            "(expected under ${baseDir().absolutePath})"
                    }
                    ImageGenerator.createFromOptions(context, options, buildConditionOptions(condition))
                } else {
                    ImageGenerator.createFromOptions(context, options)
                }

                try {
                    val start = System.currentTimeMillis()
                    val result = if (useCondition) {
                        val type = condition.toMediaPipe()
                        val conditionImage = generator.createConditionImage(
                            BitmapImageBuilder(conditionSource).build(), type
                        )
                        generator.generate(prompt, conditionImage, type, iterations, seed)
                    } else {
                        generator.generate(prompt, iterations, seed)
                    }
                    Log.i(TAG, "Image generated in ${System.currentTimeMillis() - start}ms " +
                        "(iterations=$iterations, seed=$seed, condition=$condition)")
                    BitmapExtractor.extract(result.generatedImage())
                } finally {
                    runCatching { generator.close() }
                }
            }
        } finally {
            _isGenerating.value = false
        }
    }

    private fun Condition.toMediaPipe(): ConditionOptions.ConditionType = when (this) {
        Condition.EDGE -> ConditionOptions.ConditionType.EDGE
        Condition.DEPTH -> ConditionOptions.ConditionType.DEPTH
        Condition.FACE -> ConditionOptions.ConditionType.FACE
        Condition.NONE -> error("NONE has no MediaPipe condition type")
    }

    private fun buildConditionOptions(condition: Condition): ConditionOptions {
        val builder = ConditionOptions.builder()
        when (condition) {
            Condition.EDGE -> builder.setEdgeConditionOptions(
                ConditionOptions.EdgeConditionOptions.builder()
                    .setPluginModelBaseOptions(baseOptions(pluginFile(condition)))
                    .build()
            )
            Condition.DEPTH -> builder.setDepthConditionOptions(
                ConditionOptions.DepthConditionOptions.builder()
                    .setPluginModelBaseOptions(baseOptions(pluginFile(condition)))
                    .setDepthModelBaseOptions(baseOptions(depthModelFile()))
                    .build()
            )
            Condition.FACE -> builder.setFaceConditionOptions(
                ConditionOptions.FaceConditionOptions.builder()
                    .setPluginModelBaseOptions(baseOptions(pluginFile(condition)))
                    .setFaceModelBaseOptions(baseOptions(faceModelFile()))
                    .build()
            )
            Condition.NONE -> {}
        }
        return builder.build()
    }

    private fun baseOptions(file: File): BaseOptions =
        BaseOptions.builder().setModelAssetPath(file.absolutePath).build()

    private companion object {
        const val TAG = "ImageGenEngine"
    }
}
