package com.chatgemma.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class VideoFrameExtractor @Inject constructor() {

    /**
     * Extracts up to [maxFrames] evenly spaced frames from the video at [videoUri].
     * Returns a list of Bitmaps scaled down to [maxDimension] px on the longest side.
     */
    suspend fun extractFrames(
        context: Context,
        videoUri: Uri,
        maxFrames: Int = 8,
        maxDimension: Int = 512
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<Bitmap>()
        try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return@withContext emptyList()

            val step = if (maxFrames > 1) durationMs / (maxFrames - 1) else durationMs
            for (i in 0 until maxFrames) {
                val timeUs = (i * step * 1000L).coerceAtMost(durationMs * 1000L)
                val bmp = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue
                frames.add(bmp.scaledDown(maxDimension))
            }
        } catch (e: Exception) {
            // Silently return what was extracted so far
        } finally {
            retriever.release()
        }
        frames
    }

    private fun Bitmap.scaledDown(maxDimension: Int): Bitmap {
        if (width <= maxDimension && height <= maxDimension) return this
        val scale = maxDimension.toFloat() / maxOf(width, height)
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }
}
