package com.example.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.R
import com.example.model.ExportState
import com.example.model.HighlightClip
import com.example.model.OverlayPosition
import com.example.model.QualitySettings
import com.example.model.TargetResolution
import com.example.model.VideoDetailsSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/**
 * Real Jetpack Media3 Transformer video production engine.
 * Trims and stitches highlight clips into a Composition, applies OpenGL quality/color enhancement,
 * speed/timing adjustments, resolution upscaling, high-bitrate encoding, and Arabic burn-in text overlays.
 */
@OptIn(UnstableApi::class)
class VideoMontageExporter(private val context: Context) {

    @Volatile
    private var activeTransformer: Transformer? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun cancelExport() {
        mainHandler.post {
            activeTransformer?.cancel()
            activeTransformer = null
        }
    }

    fun buildPreviewVideoEffects(
        quality: QualitySettings,
        details: VideoDetailsSpec
    ): List<Effect> {
        val effects = mutableListOf<Effect>()
        if (quality.contrast != 0f) {
            effects += Contrast(quality.contrast.coerceIn(-0.9f, 0.9f))
        }
        if (quality.brightness != 0f) {
            effects += Brightness(quality.brightness.coerceIn(-0.8f, 0.8f))
        }
        if (quality.saturation != 0f) {
            effects += HslAdjustment.Builder()
                .adjustSaturation(quality.saturation.coerceIn(-100f, 100f))
                .build()
        }
        if (quality.warmth != 0f) {
            val rScale = (1f + quality.warmth).coerceIn(0.6f, 1.5f)
            val bScale = (1f - quality.warmth * 0.8f).coerceIn(0.6f, 1.5f)
            effects += RgbAdjustment.Builder()
                .setRedScale(rScale)
                .setBlueScale(bScale)
                .build()
        }
        if (details.burnInEnabled && details.burnInText.isNotBlank()) {
            val overlayBitmap = renderArabicOverlayBitmap(details.burnInText)
            val settings = StaticOverlaySettings.Builder()
                .setOverlayFrameAnchor(0f, details.overlayPosition.yNormalized)
                .setBackgroundFrameAnchor(0f, details.overlayPosition.yNormalized)
                .build()
            val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(overlayBitmap, settings)
            effects += OverlayEffect(listOf(bitmapOverlay))
        }
        return effects
    }

    fun exportMontage(
        sourceFilePath: String,
        clips: List<HighlightClip>,
        quality: QualitySettings,
        details: VideoDetailsSpec
    ): Flow<ExportState> = callbackFlow {
        val activeClips = clips.filter { it.isIncluded }
        if (activeClips.isEmpty()) {
            trySend(ExportState.Error("اختر لقطة واحدة على الأقل لتنفيذ المونتاج والتصدير."))
            close()
            return@callbackFlow
        }

        val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(exportsDir, "ClipCraft_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}.mp4")
        if (outFile.exists()) outFile.delete()

        trySend(ExportState.Exporting(2, "جاري تجهيز خط الزمن والمؤثرات البصرية..."))

        mainHandler.post {
            try {
                val baseEffects = buildPreviewVideoEffects(quality, details).toMutableList()
                if (quality.targetResolution != TargetResolution.ORIGINAL) {
                    baseEffects += Presentation.createForHeight(quality.targetResolution.targetHeight)
                }

                val editedItems = activeClips.map { clip ->
                    val mediaItem = MediaItem.Builder()
                        .setUri(Uri.fromFile(File(sourceFilePath)))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clip.startMs.coerceAtLeast(0L))
                                .setEndPositionMs(clip.endMs.coerceAtLeast(clip.startMs + 400L))
                                .build()
                        )
                        .build()

                    val combinedSpeed = (clip.speedMultiplier * quality.globalSpeed).coerceIn(0.5f, 2.5f)
                    val itemVideoEffects = baseEffects.toMutableList()
                    val itemAudioProcessors = mutableListOf<AudioProcessor>()

                    if (kotlin.math.abs(combinedSpeed - 1.0f) > 0.03f) {
                        itemVideoEffects += SpeedChangeEffect(combinedSpeed)
                        if (!quality.removeAudio) {
                            val sonic = SonicAudioProcessor().apply {
                                setSpeed(combinedSpeed)
                                setPitch(1.0f)
                            }
                            itemAudioProcessors += sonic
                        }
                    }

                    EditedMediaItem.Builder(mediaItem)
                        .setRemoveAudio(quality.removeAudio)
                        .setEffects(Effects(itemAudioProcessors, itemVideoEffects))
                        .build()
                }

                val sequence = EditedMediaItemSequence.Builder(editedItems).build()
                val composition = Composition.Builder(sequence).build()

                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(quality.bitrateTier.bitrateBps)
                            .build()
                    )
                    .build()

                var isFinished = false

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        isFinished = true
                        activeTransformer = null
                        val (w, h) = readDimensions(outFile.absolutePath)
                        trySend(
                            ExportState.Completed(
                                outputPath = outFile.absolutePath,
                                fileSizeBytes = outFile.length(),
                                durationMs = exportResult.durationMs.takeIf { it > 0 } ?: activeClips.sumOf { it.effectiveDurationMs },
                                averageVideoBitrate = exportResult.averageVideoBitrate,
                                width = w,
                                height = h
                            )
                        )
                        close()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        isFinished = true
                        activeTransformer = null
                        outFile.delete()
                        trySend(
                            ExportState.Error(
                                "تعذر تصدير المونتاج: ${exportException.localizedMessage ?: exportException.errorCodeName}"
                            )
                        )
                        close()
                    }
                }

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(listener)
                    .build()

                activeTransformer = transformer
                transformer.start(composition, outFile.absolutePath)

                val progressHolder = ProgressHolder()
                val pollRunnable = object : Runnable {
                    override fun run() {
                        if (isFinished) return
                        val t = activeTransformer ?: return
                        val state = t.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val pct = progressHolder.progress.coerceIn(5, 99)
                            val stage = when {
                                pct < 35 -> "جاري قص ودمج اللقطات المهمة..."
                                pct < 75 -> "جاري تحسين الجودة وتطبيق النصوص والمرشحات..."
                                else -> "جاري المعالجة النهائية وحفظ MP4..."
                            }
                            trySend(ExportState.Exporting(pct, stage))
                        }
                        if (state != Transformer.PROGRESS_STATE_NOT_STARTED && !isFinished) {
                            mainHandler.postDelayed(this, 250L)
                        }
                    }
                }
                mainHandler.postDelayed(pollRunnable, 250L)
            } catch (e: Exception) {
                activeTransformer = null
                outFile.delete()
                trySend(ExportState.Error("حدث خطأ أثناء تهيئة التصدير: ${e.localizedMessage ?: "خطأ غير معروف"}"))
                close()
            }
        }

        awaitClose {
            cancelExport()
        }
    }

    fun saveToDeviceGallery(sourceFilePath: String, title: String): Result<Uri> = runCatching {
        val sourceFile = File(sourceFilePath)
        require(sourceFile.exists()) { "ملف التصدير غير موجود." }
        val displayName = "${title.take(30).replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")}_${System.currentTimeMillis()}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ClipCraft")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("تعذر إنشاء سجل الفيديو في الاستوديو.")
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val dest = File(outDir, displayName)
            sourceFile.copyTo(dest, overwrite = true)
            Uri.fromFile(dest)
        }
    }

    private fun readDimensions(filePath: String): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            w to h
        } catch (_: Exception) {
            1080 to 1920
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun renderArabicOverlayBitmap(text: String): Bitmap {
        val maxWidth = 860
        val paddingH = 38
        val paddingV = 22
        val typeface = runCatching {
            ResourcesCompat.getFont(context, R.font.cairo)
        }.getOrNull() ?: Typeface.DEFAULT_BOLD

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
            setShadowLayer(4f, 0f, 2f, Color.argb(180, 0, 0, 0))
        }

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, maxWidth - paddingH * 2)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(6f, 1.0f)
            .setIncludePad(false)
            .build()

        val textWidth = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) }?.toInt() ?: 200
        val bmpWidth = (textWidth + paddingH * 2).coerceIn(220, maxWidth)
        val bmpHeight = (layout.height + paddingV * 2).coerceAtLeast(80)

        val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val rect = RectF(2f, 2f, bmpWidth - 2f, bmpHeight - 2f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 11, 14, 20)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 0, 229, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        canvas.drawRoundRect(rect, 26f, 26f, bgPaint)
        canvas.drawRoundRect(rect, 26f, 26f, borderPaint)

        canvas.save()
        val xOffset = (bmpWidth - layout.width) / 2f
        val yOffset = (bmpHeight - layout.height) / 2f
        canvas.translate(xOffset, yOffset)
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }
}
