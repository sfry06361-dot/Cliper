package com.example.downloader

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.example.model.FrameSampleMetric
import com.example.model.VideoTechnicalInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Inspects downloaded video files using MediaMetadataRetriever, extracts technical metadata,
 * saves frame thumbnails, and computes per-frame luminance, sharpness, and motion-delta energy
 * to pinpoint the highest-impact moments in the video.
 */
class VideoFrameInspector(private val context: Context) {

    private val thumbsRoot: File
        get() = File(context.filesDir, "thumbs").apply { mkdirs() }

    suspend fun inspectVideoFile(filePath: String): VideoTechnicalInfo = withContext(Dispatchers.IO) {
        val file = File(filePath)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(filePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"
            val fps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.toInt() ?: 30
            } else {
                30
            }

            val (width, height) = if (rotation == 90 || rotation == 270) {
                rawHeight to rawWidth
            } else {
                rawWidth to rawHeight
            }

            VideoTechnicalInfo(
                filePath = filePath,
                fileName = file.name,
                fileSizeBytes = file.length(),
                durationMs = durationMs.coerceAtLeast(1000L),
                width = width.coerceAtLeast(320),
                height = height.coerceAtLeast(240),
                bitrateBps = bitrate,
                fps = fps.coerceIn(15, 120),
                hasAudio = hasAudio,
                rotationDegrees = rotation,
                mimeType = mime
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun sampleFramesAndEnergy(
        filePath: String,
        projectId: String,
        durationMs: Long,
        sampleCount: Int = 12
    ): List<FrameSampleMetric> = withContext(Dispatchers.IO) {
        val outDir = File(thumbsRoot, projectId).apply { mkdirs() }
        val retriever = MediaMetadataRetriever()
        val results = mutableListOf<FrameSampleMetric>()
        var previousGrid: IntArray? = null

        try {
            retriever.setDataSource(filePath)
            val safeDuration = durationMs.coerceAtLeast(1500L)
            val actualSamples = sampleCount.coerceIn(4, 16)
            val stepMs = safeDuration / actualSamples

            for (index in 0 until actualSamples) {
                val timestampMs = (index * stepMs + stepMs / 3).coerceAtMost(safeDuration - 200L).coerceAtLeast(0L)
                val frame = retriever.getFrameAtTime(
                    timestampMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue

                val scaled = Bitmap.createScaledBitmap(frame, 160, 90, true)
                if (scaled != frame) frame.recycle()

                val thumbFile = File(outDir, "frame_$index.jpg")
                FileOutputStream(thumbFile).use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 82, fos)
                }

                val grid = extractLumaGrid(scaled, gridW = 16, gridH = 9)
                val lumaScore = computeBalancedLumaScore(grid)
                val sharpnessScore = computeSpatialContrastScore(grid, 16, 9)
                val motionDeltaScore = previousGrid?.let { prev -> computeFrameDelta(prev, grid) } ?: 0.55f
                previousGrid = grid

                val combinedEnergy = (motionDeltaScore * 0.45f + sharpnessScore * 0.35f + lumaScore * 0.20f)
                    .coerceIn(0.15f, 0.99f)

                scaled.recycle()

                results += FrameSampleMetric(
                    timestampMs = timestampMs,
                    thumbnailPath = thumbFile.absolutePath,
                    lumaScore = lumaScore,
                    motionDeltaScore = motionDeltaScore,
                    sharpnessScore = sharpnessScore,
                    combinedEnergy = combinedEnergy
                )
            }
        } finally {
            runCatching { retriever.release() }
        }
        results
    }

    private fun extractLumaGrid(bitmap: Bitmap, gridW: Int, gridH: Int): IntArray {
        val small = Bitmap.createScaledBitmap(bitmap, gridW, gridH, false)
        val pixels = IntArray(gridW * gridH)
        small.getPixels(pixels, 0, gridW, 0, 0, gridW, gridH)
        if (small != bitmap) small.recycle()
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
    }

    private fun computeBalancedLumaScore(luma: IntArray): Float {
        if (luma.isEmpty()) return 0.5f
        val mean = luma.average()
        // Optimal exposure near ~128 gives highest visual clarity score
        val distFromMid = abs(mean - 128.0) / 128.0
        return (1.0 - distFromMid * 0.7).toFloat().coerceIn(0.2f, 1.0f)
    }

    private fun computeSpatialContrastScore(luma: IntArray, w: Int, h: Int): Float {
        if (luma.isEmpty()) return 0.5f
        var gradSum = 0.0
        var count = 0
        for (y in 0 until h - 1) {
            for (x in 0 until w - 1) {
                val idx = y * w + x
                val dx = abs(luma[idx] - luma[idx + 1])
                val dy = abs(luma[idx] - luma[idx + w])
                gradSum += sqrt((dx * dx + dy * dy).toDouble())
                count++
            }
        }
        val avgGrad = if (count > 0) gradSum / count else 0.0
        return (avgGrad / 48.0).toFloat().coerceIn(0.15f, 1.0f)
    }

    private fun computeFrameDelta(prev: IntArray, curr: IntArray): Float {
        val n = minOf(prev.size, curr.size)
        if (n == 0) return 0.5f
        var diff = 0L
        for (i in 0 until n) {
            diff += abs(prev[i] - curr[i])
        }
        val avgDiff = diff.toFloat() / n
        return (avgDiff / 52f).coerceIn(0.1f, 1.0f)
    }
}
