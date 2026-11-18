package com.example.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
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
import com.example.model.QualitySettings
import com.example.model.TargetResolution
import com.example.model.VideoCodecMode
import com.example.model.VideoDetailsSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/**
 * Real Jetpack Media3 Transformer video production engine supporting all 40 studio editing features:
 * trimming, jump-cut tightening, hook looping, outro CTA cards, aspect ratio cropping, rotation,
 * mirror flip, punch zoom, upscaling, OpenGL color grading, speed + pitch-preserved audio,
 * H.264/H.265 encoding, and multi-layer Arabic burn-in overlays.
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
        details: VideoDetailsSpec,
        estimatedDurationMs: Long = 0L
    ): List<Effect> {
        val effects = mutableListOf<Effect>()

        // Geometry transforms: Rotation, Mirror Flip, Dynamic Punch Zoom
        val scaleX = (if (quality.mirrorHorizontal) -1f else 1f) * quality.zoomScale.coerceIn(1.0f, 1.5f)
        val scaleY = quality.zoomScale.coerceIn(1.0f, 1.5f)
        if (quality.rotationDegrees != 0f || kotlin.math.abs(scaleX - 1f) > 0.01f || kotlin.math.abs(scaleY - 1f) > 0.01f) {
            effects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(quality.rotationDegrees)
                .setScale(scaleX, scaleY)
                .build()
        }

        // Color Grading & Visual Enhancements
        val effectiveContrast = (quality.contrast + if (quality.moodyDarkMode) 0.14f else 0f).coerceIn(-0.9f, 0.9f)
        val effectiveBrightness = (quality.brightness + if (quality.moodyDarkMode) -0.10f else 0f).coerceIn(-0.8f, 0.8f)
        val effectiveSaturation = if (quality.noirMode) -100f else quality.saturation.coerceIn(-100f, 100f)

        if (effectiveContrast != 0f) {
            effects += Contrast(effectiveContrast)
        }
        if (effectiveBrightness != 0f) {
            effects += Brightness(effectiveBrightness)
        }
        if (effectiveSaturation != 0f) {
            effects += HslAdjustment.Builder()
                .adjustSaturation(effectiveSaturation)
                .build()
        }
        if (quality.warmth != 0f && !quality.noirMode) {
            val rScale = (1f + quality.warmth).coerceIn(0.6f, 1.5f)
            val bScale = (1f - quality.warmth * 0.8f).coerceIn(0.6f, 1.5f)
            effects += RgbAdjustment.Builder()
                .setRedScale(rScale)
                .setBlueScale(bScale)
                .build()
        }

        // Multi-layer Burn-In Overlays (Hook Banner, Bottom Explanation Bar, Watermark, Timecode Stamp)
        val overlays = mutableListOf<TextureOverlay>()
        if (details.burnInEnabled && details.burnInText.isNotBlank()) {
            val hookBmp = renderArabicOverlayBitmap(details.burnInText, accentColor = Color.rgb(0, 229, 255))
            val settings = StaticOverlaySettings.Builder()
                .setOverlayFrameAnchor(0f, details.overlayPosition.yNormalized)
                .setBackgroundFrameAnchor(0f, details.overlayPosition.yNormalized)
                .build()
            overlays += BitmapOverlay.createStaticBitmapOverlay(hookBmp, settings)
        }
        if (details.bottomExplanationEnabled && details.bottomExplanationText.isNotBlank()) {
            val expBmp = renderArabicOverlayBitmap(details.bottomExplanationText, accentColor = Color.rgb(255, 179, 0))
            val settings = StaticOverlaySettings.Builder()
                .setOverlayFrameAnchor(0f, -0.76f)
                .setBackgroundFrameAnchor(0f, -0.76f)
                .build()
            overlays += BitmapOverlay.createStaticBitmapOverlay(expBmp, settings)
        }
        if (details.watermarkEnabled && details.watermarkText.isNotBlank()) {
            val wmBmp = renderBadgeBitmap(details.watermarkText, bgAlpha = 140)
            val settings = StaticOverlaySettings.Builder()
                .setOverlayFrameAnchor(0.75f, 0.85f)
                .setBackgroundFrameAnchor(0.75f, 0.85f)
                .build()
            overlays += BitmapOverlay.createStaticBitmapOverlay(wmBmp, settings)
        }
        if (quality.showTimecodeStamp && estimatedDurationMs > 0L) {
            val sec = estimatedDurationMs / 1000L
            val tcText = String.format(Locale.US, "CLIP %02d:%02d", sec / 60L, sec % 60L)
            val tcBmp = renderBadgeBitmap(tcText, bgAlpha = 185)
            val settings = StaticOverlaySettings.Builder()
                .setOverlayFrameAnchor(-0.74f, 0.85f)
                .setBackgroundFrameAnchor(-0.74f, 0.85f)
                .build()
            overlays += BitmapOverlay.createStaticBitmapOverlay(tcBmp, settings)
        }

        if (overlays.isNotEmpty()) {
            effects += OverlayEffect(overlays)
        }

        return effects
    }

    fun exportMontage(
        sourceFilePath: String,
        clips: List<HighlightClip>,
        quality: QualitySettings,
        details: VideoDetailsSpec
    ): Flow<ExportState> = callbackFlow {
        val baseActiveClips = clips.filter { it.isIncluded }
        if (baseActiveClips.isEmpty()) {
            trySend(ExportState.Error("اختر لقطة واحدة على الأقل لتنفيذ المونتاج والتصدير."))
            close()
            return@callbackFlow
        }

        val activeClips = buildList {
            val limited = if (quality.top3Only) baseActiveClips.take(3) else baseActiveClips
            addAll(limited)
            if (quality.loopHookAtEnd && limited.isNotEmpty()) {
                val hook = limited.firstOrNull { it.isHook } ?: limited.first()
                add(hook.copy(id = "${hook.id}_loop"))
            }
        }

        val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(exportsDir, "ClipCraft_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}.mp4")
        if (outFile.exists()) outFile.delete()

        val sourceUri = if (sourceFilePath.startsWith("http://", true) || sourceFilePath.startsWith("https://", true)) {
            Uri.parse(sourceFilePath)
        } else {
            Uri.fromFile(File(sourceFilePath))
        }

        trySend(ExportState.Exporting(2, "جاري تجهيز خط الزمن والمؤثرات البصرية..."))

        mainHandler.post {
            try {
                val estimatedDur = activeClips.sumOf { it.effectiveDurationMs }
                val baseEffects = buildPreviewVideoEffects(quality, details, estimatedDur).toMutableList()

                quality.aspectRatioMode.ratio?.let { targetRatio ->
                    baseEffects += Presentation.createForAspectRatio(
                        targetRatio,
                        Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                    )
                }
                if (quality.targetResolution != TargetResolution.ORIGINAL) {
                    baseEffects += Presentation.createForHeight(quality.targetResolution.targetHeight)
                }

                val editedItems = activeClips.map { clip ->
                    val trim = quality.jumpCutTrimMs.coerceIn(0L, 400L)
                    val safeStart = (clip.startMs + trim).coerceAtLeast(0L)
                    val safeEnd = (clip.endMs - trim).coerceAtLeast(safeStart + 500L)

                    val mediaItem = MediaItem.Builder()
                        .setUri(sourceUri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(safeStart)
                                .setEndPositionMs(safeEnd)
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
                                setPitch(if (quality.pitchPreserved) 1.0f else combinedSpeed.coerceIn(0.75f, 1.35f))
                            }
                            itemAudioProcessors += sonic
                        }
                    }

                    EditedMediaItem.Builder(mediaItem)
                        .setRemoveAudio(quality.removeAudio)
                        .setFrameRate(quality.targetFps.coerceIn(24, 60))
                        .setEffects(Effects(itemAudioProcessors, itemVideoEffects))
                        .build()
                }.toMutableList()

                // Feature 8: Append Outro Call-to-Action Card if enabled
                var outroFile: File? = null
                if (quality.includeOutroCta && quality.outroText.isNotBlank()) {
                    outroFile = renderOutroCtaImage(quality.outroText)
                    val outroItem = MediaItem.Builder()
                        .setUri(Uri.fromFile(outroFile))
                        .setImageDurationMs(2200L)
                        .build()
                    val outroEffects = if (quality.targetResolution != TargetResolution.ORIGINAL) {
                        Effects(emptyList(), listOf(Presentation.createForHeight(quality.targetResolution.targetHeight)))
                    } else {
                        Effects.EMPTY
                    }
                    editedItems += EditedMediaItem.Builder(outroItem)
                        .setFrameRate(quality.targetFps.coerceIn(24, 60))
                        .setEffects(outroEffects)
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
                    .setEnableFallback(true)
                    .build()

                var isFinished = false

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        isFinished = true
                        activeTransformer = null
                        outroFile?.delete()
                        val (w, h) = readDimensions(outFile.absolutePath)
                        if (quality.autoSaveToGallery) {
                            saveToDeviceGallery(outFile.absolutePath, details.viralTitle.ifBlank { "ClipCraft" })
                        }
                        trySend(
                            ExportState.Completed(
                                outputPath = outFile.absolutePath,
                                fileSizeBytes = outFile.length(),
                                durationMs = exportResult.durationMs.takeIf { it > 0 } ?: estimatedDur,
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
                        outroFile?.delete()
                        outFile.delete()
                        trySend(
                            ExportState.Error(
                                "تعذر تصدير المونتاج: ${exportException.localizedMessage ?: exportException.errorCodeName}"
                            )
                        )
                        close()
                    }
                }

                val targetMime = if (quality.codecMode == VideoCodecMode.H265_HEVC) {
                    MimeTypes.VIDEO_H265
                } else {
                    MimeTypes.VIDEO_H264
                }

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(targetMime)
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
                                pct < 75 -> "جاري ترقية الجودة وتطبيق الشرح والمؤثرات..."
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

    private fun renderOutroCtaImage(text: String): File {
        val width = 1280
        val height = 720
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.rgb(11, 14, 20), Color.rgb(6, 50, 61), Color.rgb(18, 23, 34)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val typeface = runCatching { ResourcesCompat.getFont(context, R.font.cairo) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
            setShadowLayer(8f, 0f, 3f, Color.BLACK)
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, width - 160)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate((width - layout.width) / 2f, (height - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()

        val file = File(context.cacheDir, "outro_cta_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
        bmp.recycle()
        return file
    }

    private fun renderArabicOverlayBitmap(text: String, accentColor: Int): Bitmap {
        val maxWidth = 860
        val paddingH = 36
        val paddingV = 20
        val typeface = runCatching {
            ResourcesCompat.getFont(context, R.font.cairo)
        }.getOrNull() ?: Typeface.DEFAULT_BOLD

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
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
        val bmpHeight = (layout.height + paddingV * 2).coerceAtLeast(76)

        val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val rect = RectF(2f, 2f, bmpWidth - 2f, bmpHeight - 2f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 11, 14, 20)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        canvas.drawRoundRect(rect, 24f, 24f, bgPaint)
        canvas.drawRoundRect(rect, 24f, 24f, borderPaint)

        canvas.save()
        canvas.translate((bmpWidth - layout.width) / 2f, (bmpHeight - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    private fun renderBadgeBitmap(text: String, bgAlpha: Int): Bitmap {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }
        val textW = textPaint.measureText(text).toInt().coerceAtLeast(60)
        val w = textW + 32
        val h = 48
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(bgAlpha, 11, 14, 20) }
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 14f, 14f, bg)
        canvas.drawText(text, 16f, 33f, textPaint)
        return bmp
    }
}
