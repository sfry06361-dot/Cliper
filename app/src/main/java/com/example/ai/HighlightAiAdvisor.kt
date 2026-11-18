package com.example.ai

import android.graphics.BitmapFactory
import android.util.Log
import com.example.model.FrameSampleMetric
import com.example.model.HighlightClip
import com.example.model.OverlayPosition
import com.example.model.PacingMode
import com.example.model.QualityPreset
import com.example.model.VideoDetailsSpec
import com.example.model.VideoTechnicalInfo
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

private const val TAG = "GeminiHighlightAdvisor"

data class HighlightSuggestionBundle(
    val clips: List<HighlightClip>,
    val details: VideoDetailsSpec,
    val recommendedPreset: QualityPreset,
    val recommendedSpeed: Float,
    val poweredByGemini: Boolean,
    val noticeMessage: String
)

sealed interface AiAnalysisState {
    data object Idle : AiAnalysisState
    data object Analyzing : AiAnalysisState
    data class Success(val summary: String) : AiAnalysisState
    data class Error(val message: String) : AiAnalysisState
}

interface HighlightAiAdvisor {
    suspend fun analyzeHighlightsAndDetails(
        sourceTitle: String,
        technicalInfo: VideoTechnicalInfo,
        frameMetrics: List<FrameSampleMetric>,
        pacingMode: PacingMode
    ): HighlightSuggestionBundle
}

/**
 * Multimodal Gemini 3.8 Flash analyzer via Firebase AI Logic.
 * Inspects sampled keyframes + motion/sharpness telemetry and returns a structured
 * montage plan (viral hook clip first, top highlight cuts, quality preset, pacing speed,
 * burn-in overlay hook, viral Arabic title, description, and hashtags).
 */
class GeminiHighlightAdvisor : HighlightAiAdvisor {

    private val responseSchema = Schema.obj(
        properties = mapOf(
            "viralTitle" to Schema.string(description = "Catchy Arabic title optimized to attract views"),
            "burnInText" to Schema.string(description = "Short punchy Arabic text (3-7 words) to burn directly onto the video"),
            "description" to Schema.string(description = "Detailed Arabic video description with highlight timestamps and engaging summary"),
            "hashtags" to Schema.string(description = "Space-separated Arabic and English hashtags"),
            "recommendedPreset" to Schema.enumeration(
                values = listOf("CINEMA_4K", "VIRAL_HDR_PUNCH", "STUDIO_BRIGHT", "ORIGINAL"),
                description = "Best visual enhancement preset based on lighting and contrast of the frames"
            ),
            "recommendedSpeed" to Schema.double(description = "Optimal global playback speed between 0.85 and 1.45"),
            "clips" to Schema.array(
                description = "Ordered list of highlight clips, putting the strongest hook clip first",
                items = Schema.obj(
                    properties = mapOf(
                        "title" to Schema.string(description = "Short Arabic title of this highlight moment"),
                        "reason" to Schema.string(description = "Why this moment attracts viewers in Arabic"),
                        "viralityScore" to Schema.integer(description = "Engagement score from 60 to 99"),
                        "startMs" to Schema.integer(description = "Clip start timestamp in milliseconds within [0, durationMs]"),
                        "endMs" to Schema.integer(description = "Clip end timestamp in milliseconds, at least 1500ms after startMs"),
                        "speedMultiplier" to Schema.double(description = "Clip speed multiplier between 0.8 and 1.5"),
                        "isHook" to Schema.boolean(description = "True only for the strongest opening hook clip"),
                        "isIncluded" to Schema.boolean(description = "True for top engaging clips, false for secondary/filler clips")
                    )
                )
            )
        )
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = this@GeminiHighlightAdvisor.responseSchema
                temperature = 0.4f
            }
        )
    }

    override suspend fun analyzeHighlightsAndDetails(
        sourceTitle: String,
        technicalInfo: VideoTechnicalInfo,
        frameMetrics: List<FrameSampleMetric>,
        pacingMode: PacingMode
    ): HighlightSuggestionBundle = withContext(Dispatchers.IO) {
        val durationMs = technicalInfo.durationMs.coerceAtLeast(3000L)
        val sampledMetrics = selectRepresentativeSamples(frameMetrics, maxFrames = 8)
        val decodedBitmaps = sampledMetrics.mapNotNull { metric ->
            runCatching { BitmapFactory.decodeFile(metric.thumbnailPath) }.getOrNull()?.let { bmp ->
                metric to bmp
            }
        }

        try {
            val response = withTimeout(60_000L) {
                model.generateContent(
                    content {
                        text(
                            buildPromptText(
                                sourceTitle = sourceTitle,
                                technicalInfo = technicalInfo,
                                metrics = sampledMetrics,
                                pacingMode = pacingMode
                            )
                        )
                        decodedBitmaps.forEach { (metric, bitmap) ->
                            text("إطار عند ${metric.timestampMs} ms (طاقة الحركة=${(metric.motionDeltaScore * 100).toInt()}%، الوضوح=${(metric.sharpnessScore * 100).toInt()}%):")
                            image(bitmap)
                        }
                    }
                )
            }

            val rawJson = response.text?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("أرجع Gemini استجابة فارغة بدون بيانات مونتاج.")

            parseGeminiResponse(
                rawJson = rawJson,
                durationMs = durationMs,
                frameMetrics = frameMetrics,
                pacingMode = pacingMode
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini highlight analysis timed out after 60s", e)
            throw IllegalStateException("انتهت مهلة تحليل الفيديو بالذكاء الاصطناعي (60 ثانية). حاول مجدداً.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini highlight analysis failed", e)
            throw IllegalStateException(
                "تعذر تحليل الفيديو عبر Gemini: ${e.localizedMessage ?: e.javaClass.simpleName}",
                e
            )
        } finally {
            decodedBitmaps.forEach { (_, bmp) ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    private fun buildPromptText(
        sourceTitle: String,
        technicalInfo: VideoTechnicalInfo,
        metrics: List<FrameSampleMetric>,
        pacingMode: PacingMode
    ): String {
        val telemetryLines = metrics.joinToString("\n") { m ->
            "- t=${m.timestampMs}ms | energy=${(m.combinedEnergy * 100).toInt()}% | motion=${(m.motionDeltaScore * 100).toInt()}% | sharpness=${(m.sharpnessScore * 100).toInt()}% | luma=${(m.lumaScore * 100).toInt()}%"
        }
        val targetInfo = pacingMode.maxTargetDurationMs?.let { "${it / 1000} ثانية" } ?: "مونتاج شامل لأهم اللقطات"

        return """
            أنت مخرج ومونتير فيديو محترف متخصص في الفيديوهات القصيرة عالية المشاهدات (Reels / Shorts / TikTok).
            مهمتك فحص إطارات الفيديو المرفقة وبيانات الفحص التقنية، واستخراج خطة مونتاج دقيقة جداً:
            1. اختر أقوى لقطة افتتاحية (Hook) تجذب الانتباه خلال أول 3 ثوانٍ وضعها أولاً (isHook = true).
            2. استبعد اللحظات البطيئة أو المملة واجعل اللقطات المهمة والذروة فقط مفعّلة (isIncluded = true) ضمن مدة مستهدفة: $targetInfo.
            3. التزم تماماً بحدود الفيديو من 0 إلى ${technicalInfo.durationMs} مللي ثانية، واجعل كل لقطة لا تقل عن 1500 مللي ثانية.
            4. اقترح أفضل إعداد تحسين جودة (recommendedPreset) بناءً على إضاءة وتباين الإطارات التي تراها، والسرعة المناسبة (recommendedSpeed).
            5. اكتب عنواناً عربياً جذاباً جداً (viralTitle)، ونصاً قصيراً قوياً ليُطبع على الفيديو (burnInText)، وتفاصيل وصفية دقيقة مع التوقيتات (description)، وهاشتاجات قوية (hashtags).

            بيانات الفيديو:
            - العنوان المصدر: $sourceTitle
            - المدة الإجمالية: ${technicalInfo.durationMs} ms
            - الدقة الأصلية: ${technicalInfo.width}x${technicalInfo.height} (${technicalInfo.fps} fps)
            - مؤشرات الطاقة للإطارات المفحوصة:
            $telemetryLines
        """.trimIndent()
    }

    private fun parseGeminiResponse(
        rawJson: String,
        durationMs: Long,
        frameMetrics: List<FrameSampleMetric>,
        pacingMode: PacingMode
    ): HighlightSuggestionBundle {
        val root = JSONObject(rawJson)
        val viralTitle = root.optString("viralTitle").ifBlank { "أهم لقطات الفيديو بجودة فائقة" }
        val burnInText = root.optString("burnInText").ifBlank { "شاهد اللحظة الأهم هنا 🔥" }
        val description = root.optString("description").ifBlank { viralTitle }
        val hashtags = root.optString("hashtags").ifBlank { "#مونتاج #لقطات_مهمة #ClipCraft" }
        val presetName = root.optString("recommendedPreset", "VIRAL_HDR_PUNCH")
        val preset = runCatching { QualityPreset.valueOf(presetName) }.getOrDefault(QualityPreset.VIRAL_HDR_PUNCH)
        val recSpeed = root.optDouble("recommendedSpeed", 1.1).toFloat().coerceIn(0.8f, 1.5f)

        val clipsArray = root.optJSONArray("clips")
        val parsedClips = mutableListOf<HighlightClip>()
        if (clipsArray != null) {
            for (i in 0 until clipsArray.length()) {
                val obj = clipsArray.optJSONObject(i) ?: continue
                val rawStart = obj.optLong("startMs", 0L).coerceIn(0L, (durationMs - 1000L).coerceAtLeast(0L))
                val rawEnd = obj.optLong("endMs", (rawStart + 3500L).coerceAtMost(durationMs))
                    .coerceIn((rawStart + 1000L).coerceAtMost(durationMs), durationMs)
                if (rawEnd - rawStart < 800L) continue

                val midMs = (rawStart + rawEnd) / 2L
                val nearestThumb = frameMetrics.minByOrNull { kotlin.math.abs(it.timestampMs - midMs) }?.thumbnailPath

                parsedClips += HighlightClip(
                    id = UUID.randomUUID().toString(),
                    title = obj.optString("title").ifBlank { "لقطة مميزة #${i + 1}" },
                    reason = obj.optString("reason").ifBlank { "لقطة مختارة بالذكاء الاصطناعي لرفع التفاعل" },
                    viralityScore = obj.optInt("viralityScore", 88).coerceIn(50, 99),
                    startMs = rawStart,
                    endMs = rawEnd,
                    speedMultiplier = obj.optDouble("speedMultiplier", 1.05).toFloat().coerceIn(0.6f, 1.8f),
                    isIncluded = obj.optBoolean("isIncluded", i < 4),
                    isHook = obj.optBoolean("isHook", i == 0),
                    energyScore = (obj.optInt("viralityScore", 85) / 100f).coerceIn(0.5f, 0.99f),
                    thumbnailPath = nearestThumb
                )
            }
        }

        if (parsedClips.isEmpty()) {
            throw IllegalStateException("لم يرجع Gemini أي لقطات صالحة ضمن مدة الفيديو.")
        }

        // Ensure Hook clip comes first and at least one clip is included
        val hookIdx = parsedClips.indexOfFirst { it.isHook }
        val reordered = if (hookIdx > 0) {
            val list = parsedClips.toMutableList()
            val hookClip = list.removeAt(hookIdx).copy(isIncluded = true)
            listOf(hookClip) + list
        } else {
            parsedClips.mapIndexed { idx, c -> if (idx == 0) c.copy(isHook = true, isIncluded = true) else c }
        }

        val maxTargetMs = pacingMode.maxTargetDurationMs
        val finalClips = if (maxTargetMs != null) {
            var running = 0L
            reordered.map { clip ->
                val dur = (clip.rawDurationMs / (clip.speedMultiplier * recSpeed)).toLong()
                val keep = (clip.isIncluded && running + dur <= maxTargetMs) || clip.isHook
                if (keep) running += dur
                clip.copy(isIncluded = keep)
            }
        } else {
            reordered
        }

        return HighlightSuggestionBundle(
            clips = finalClips,
            details = VideoDetailsSpec(
                viralTitle = viralTitle,
                burnInEnabled = true,
                burnInText = burnInText,
                overlayPosition = OverlayPosition.TOP_HOOK,
                description = description,
                hashtags = hashtags
            ),
            recommendedPreset = preset,
            recommendedSpeed = recSpeed,
            poweredByGemini = true,
            noticeMessage = "تم التحليل عبر Gemini 3.8 Flash بفحص الإطارات الحقيقية وترتيب أقوى اللقطات لجذب المشاهدات."
        )
    }

    private fun selectRepresentativeSamples(
        metrics: List<FrameSampleMetric>,
        maxFrames: Int
    ): List<FrameSampleMetric> {
        if (metrics.size <= maxFrames) return metrics
        val step = metrics.size.toFloat() / maxFrames
        return (0 until maxFrames).map { i ->
            metrics[(i * step).toInt().coerceIn(0, metrics.lastIndex)]
        }
    }
}
