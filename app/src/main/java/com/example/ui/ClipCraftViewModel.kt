package com.example.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiAnalysisState
import com.example.ai.GeminiHighlightAdvisor
import com.example.ai.HighlightAiAdvisor
import com.example.data.ClipCraftDatabase
import com.example.data.ProjectRepository
import com.example.data.SavedProject
import com.example.downloader.StoredMediaFile
import com.example.downloader.VideoDownloadEngine
import com.example.downloader.VideoFrameInspector
import com.example.editor.VideoMontageExporter
import com.example.model.AspectRatioMode
import com.example.model.BitrateTier
import com.example.model.DownloadState
import com.example.model.EditingFeatureId
import com.example.model.ExportState
import com.example.model.FrameSampleMetric
import com.example.model.HighlightClip
import com.example.model.LinkInspectionResult
import com.example.model.PacingMode
import com.example.model.QualityPreset
import com.example.model.QualitySettings
import com.example.model.TargetResolution
import com.example.model.VideoCodecMode
import com.example.model.VideoDetailsSpec
import com.example.model.VideoTechnicalInfo
import com.example.ui.components.formatMillisTimecode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private const val TAG = "ClipCraftViewModel"

data class StudioUiState(
    val projectId: String = UUID.randomUUID().toString(),
    val urlInput: String = "",
    val downloadState: DownloadState = DownloadState.Idle,
    val inspectionResult: LinkInspectionResult? = null,
    val technicalInfo: VideoTechnicalInfo? = null,
    val frameMetrics: List<FrameSampleMetric> = emptyList(),
    val aiAnalysisState: AiAnalysisState = AiAnalysisState.Idle,
    val clips: List<HighlightClip> = emptyList(),
    val qualitySettings: QualitySettings = QualitySettings(),
    val detailsSpec: VideoDetailsSpec = VideoDetailsSpec(),
    val exportState: ExportState = ExportState.Idle,
    val seekRequestMs: Pair<Long, Int>? = null,
    val savedProjects: List<SavedProject> = emptyList(),
    val storedFiles: List<StoredMediaFile> = emptyList(),
    val toastMessage: String? = null
) {
    val isAnalyzingFrames: Boolean
        get() = aiAnalysisState is AiAnalysisState.Analyzing

    val hasLoadedVideo: Boolean
        get() = technicalInfo != null &&
            (technicalInfo.isRemoteHlsStream || File(technicalInfo.filePath).exists())

    val includedClips: List<HighlightClip>
        get() = clips.filter { it.isIncluded }

    val estimatedOutputDurationMs: Long
        get() {
            val base = includedClips
            val active = if (qualitySettings.top3Only) base.take(3) else base
            val totalClipMs = active.sumOf { clip ->
                val trim = qualitySettings.jumpCutTrimMs.coerceIn(0L, 400L)
                val trimmedRaw = (clip.rawDurationMs - trim * 2L).coerceAtLeast(500L)
                (trimmedRaw / (clip.speedMultiplier * qualitySettings.globalSpeed).coerceIn(0.5f, 2.5f)).toLong()
            }
            val hookExtraMs = if (qualitySettings.loopHookAtEnd && active.isNotEmpty()) {
                val hook = active.firstOrNull { it.isHook } ?: active.first()
                (hook.rawDurationMs / (hook.speedMultiplier * qualitySettings.globalSpeed).coerceIn(0.5f, 2.5f)).toLong()
            } else {
                0L
            }
            val outroExtraMs = if (qualitySettings.includeOutroCta) 2200L else 0L
            return totalClipMs + hookExtraMs + outroExtraMs
        }

    val activeFeatures: Set<EditingFeatureId>
        get() = computeActiveFeatures(this)
}

fun computeActiveFeatures(state: StudioUiState): Set<EditingFeatureId> {
    if (!state.hasLoadedVideo) return emptySet()
    val q = state.qualitySettings
    val d = state.detailsSpec
    val clips = state.clips

    return buildSet {
        if (clips.firstOrNull()?.isHook == true) add(EditingFeatureId.F01_AUTO_HOOK_FIRST)
        if (clips.size > 1 && clips.any { !it.isIncluded }) add(EditingFeatureId.F02_DEAD_AIR_TRIM)
        if (q.loopHookAtEnd) add(EditingFeatureId.F03_HOOK_LOOP_REPLAY)
        if (q.jumpCutTrimMs > 0L) add(EditingFeatureId.F04_JUMP_CUT_TIGHTENER)
        if (q.top3Only) add(EditingFeatureId.F05_TOP_3_HIGHLIGHTS)
        if (q.aspectRatioMode == AspectRatioMode.PORTRAIT_9_16) add(EditingFeatureId.F06_ASPECT_REELS_9_16)
        if (state.aiAnalysisState is AiAnalysisState.Success) add(EditingFeatureId.F07_GEMINI_STORY_ARC)
        if (q.includeOutroCta) add(EditingFeatureId.F08_OUTRO_CTA_CARD)

        if (d.description.isNotBlank()) add(EditingFeatureId.F09_AI_FULL_EXPLANATION)
        if (d.voiceoverScript.isNotBlank()) add(EditingFeatureId.F10_VOICEOVER_SCRIPT)
        if (d.chapterTimestamps.isNotBlank()) add(EditingFeatureId.F11_CHAPTER_TIMESTAMPS)
        if (d.viralTitle.isNotBlank()) add(EditingFeatureId.F12_VIRAL_TITLE_HOOKS)
        if (d.bottomExplanationEnabled && d.bottomExplanationText.isNotBlank()) add(EditingFeatureId.F13_BURN_IN_EXPLANATION)
        if (d.burnInEnabled && d.burnInText.isNotBlank()) add(EditingFeatureId.F14_TOP_HOOK_BANNER)
        if (d.watermarkEnabled && d.watermarkText.isNotBlank()) add(EditingFeatureId.F15_CHANNEL_WATERMARK)
        if (d.hashtags.isNotBlank()) add(EditingFeatureId.F16_SEO_HASHTAGS)

        if (q.globalSpeed in 1.08f..1.35f) add(EditingFeatureId.F17_REELS_SPEED_1_15)
        if (q.globalSpeed <= 0.85f) add(EditingFeatureId.F18_CINEMATIC_SLOWMO)
        if (q.pacingMode == PacingMode.SHORT_15S) add(EditingFeatureId.F19_SHORTS_15S_CAP)
        if (q.pacingMode == PacingMode.REEL_30S) add(EditingFeatureId.F20_TIKTOK_30S_CAP)
        if (q.pacingMode == PacingMode.SUMMARY_60S) add(EditingFeatureId.F21_SUMMARY_60S_CAP)
        if (q.globalSpeed >= 1.45f) add(EditingFeatureId.F22_HYPER_TIMELAPSE)
        if (q.pitchPreserved) add(EditingFeatureId.F23_PITCH_PRESERVED)
        if (q.showTimecodeStamp) add(EditingFeatureId.F24_TIMECODE_STAMP)

        if (q.targetResolution == TargetResolution.HD_1080P) add(EditingFeatureId.F25_UPSCALE_1080P)
        if (q.targetResolution == TargetResolution.UHD_4K) add(EditingFeatureId.F26_UPSCALE_4K)
        if (q.bitrateTier == BitrateTier.ULTRA_16M) add(EditingFeatureId.F27_ULTRA_BITRATE_16M)
        if (q.contrast >= 0.14f) add(EditingFeatureId.F28_HDR_CONTRAST_PUNCH)
        if (q.brightness >= 0.04f) add(EditingFeatureId.F29_STUDIO_BRIGHT_LIFT)
        if (q.saturation >= 12f && !q.noirMode) add(EditingFeatureId.F30_COLOR_VIBRANCE)
        if (q.warmth >= 0.03f && !q.noirMode) add(EditingFeatureId.F31_CINEMA_WARMTH)
        if (q.noirMode) add(EditingFeatureId.F32_NOIR_MONOCHROME)

        if (q.rotationDegrees != 0f) add(EditingFeatureId.F33_ROTATE_90)
        if (q.mirrorHorizontal) add(EditingFeatureId.F34_MIRROR_FLIP)
        if (q.zoomScale > 1.05f) add(EditingFeatureId.F35_DYNAMIC_PUNCH_ZOOM)
        if (q.moodyDarkMode) add(EditingFeatureId.F36_MOODY_DARK_TONE)
        if (q.targetFps >= 60) add(EditingFeatureId.F37_SMOOTH_60FPS)
        if (q.removeAudio) add(EditingFeatureId.F38_MUTE_B_ROLL)
        if (q.codecMode == VideoCodecMode.H265_HEVC) add(EditingFeatureId.F39_CODEC_HEVC_H265)
        if (q.autoSaveToGallery) add(EditingFeatureId.F40_AUTO_SAVE_GALLERY)
    }
}

class ClipCraftViewModel(application: Application) : AndroidViewModel(application) {

    private val frameInspector = VideoFrameInspector(application)
    private val downloadEngine = VideoDownloadEngine(application, frameInspector = frameInspector)
    private val highlightAdvisor: HighlightAiAdvisor = GeminiHighlightAdvisor()
    val montageExporter = VideoMontageExporter(application)
    private val repository = ProjectRepository(ClipCraftDatabase.getInstance(application).projectDao())

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null
    private var analysisJob: Job? = null
    private var exportJob: Job? = null
    private var seekNonce = 0

    init {
        viewModelScope.launch {
            repository.savedProjects.collect { projects ->
                _uiState.update { it.copy(savedProjects = projects) }
            }
        }
        refreshStoredFiles()
    }

    fun updateUrlInput(url: String) {
        _uiState.update { it.copy(urlInput = url) }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun startInspectAndDownload(customUrl: String? = null) {
        val targetUrl = (customUrl ?: _uiState.value.urlInput).trim()
        if (customUrl != null) {
            _uiState.update { it.copy(urlInput = customUrl) }
        }
        if (targetUrl.isBlank()) {
            _uiState.update { it.copy(toastMessage = "يرجى لصق رابط الفيديو أولاً") }
            return
        }

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    projectId = UUID.randomUUID().toString(),
                    exportState = ExportState.Idle,
                    aiAnalysisState = AiAnalysisState.Idle
                )
            }
            downloadEngine.inspectAndDownload(targetUrl)
                .catch { e ->
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Link inspection flow error", e)
                    emit(
                        DownloadState.Error(
                            message = e.localizedMessage ?: "تعذر فحص رابط الفيديو",
                            diagnosticDetail = e.javaClass.simpleName
                        )
                    )
                }
                .collect { state ->
                    _uiState.update { it.copy(downloadState = state) }
                    if (state is DownloadState.Completed) {
                        onVideoReady(state.inspection, state.technicalInfo)
                    }
                }
        }
    }

    fun cancelDownload() {
        downloadEngine.cancelActiveDownload()
        downloadJob?.cancel()
        _uiState.update { it.copy(downloadState = DownloadState.Idle, toastMessage = "تم إلغاء التحميل") }
    }

    fun importLocalVideo(uri: Uri) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    projectId = UUID.randomUUID().toString(),
                    exportState = ExportState.Idle,
                    aiAnalysisState = AiAnalysisState.Idle
                )
            }
            downloadEngine.importLocalUri(uri)
                .catch { e ->
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Local import flow error", e)
                    emit(DownloadState.Error(e.localizedMessage ?: "تعذر استيراد الفيديو من الجهاز"))
                }
                .collect { state ->
                    _uiState.update { it.copy(downloadState = state) }
                    if (state is DownloadState.Completed) {
                        onVideoReady(state.inspection, state.technicalInfo)
                    }
                }
        }
    }

    private fun onVideoReady(inspection: LinkInspectionResult, technicalInfo: VideoTechnicalInfo) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    inspectionResult = inspection,
                    technicalInfo = technicalInfo,
                    aiAnalysisState = AiAnalysisState.Analyzing
                )
            }
            val projectId = _uiState.value.projectId
            val metrics = if (technicalInfo.isRemoteHlsStream) {
                emptyList()
            } else {
                runCatching {
                    frameInspector.sampleFramesAndEnergy(
                        filePath = technicalInfo.filePath,
                        projectId = projectId,
                        durationMs = technicalInfo.durationMs,
                        sampleCount = 12
                    )
                }.getOrDefault(emptyList())
            }
            _uiState.update {
                it.copy(
                    frameMetrics = metrics,
                    technicalInfo = technicalInfo.copy(frameCountSampled = metrics.size)
                )
            }
            runGeminiHighlightAnalysis(
                sourceTitle = inspection.title,
                technicalInfo = technicalInfo,
                metrics = metrics
            )
            refreshStoredFiles()
        }
    }

    fun reAnalyzeHighlights() {
        val tech = _uiState.value.technicalInfo ?: return
        val inspection = _uiState.value.inspectionResult
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _uiState.update { it.copy(aiAnalysisState = AiAnalysisState.Analyzing) }
            val metrics = when {
                tech.isRemoteHlsStream -> emptyList()
                _uiState.value.frameMetrics.isNotEmpty() -> _uiState.value.frameMetrics
                else -> frameInspector.sampleFramesAndEnergy(tech.filePath, _uiState.value.projectId, tech.durationMs)
            }
            _uiState.update { it.copy(frameMetrics = metrics) }
            runGeminiHighlightAnalysis(
                sourceTitle = inspection?.title ?: tech.fileName,
                technicalInfo = tech,
                metrics = metrics
            )
        }
    }

    private suspend fun runGeminiHighlightAnalysis(
        sourceTitle: String,
        technicalInfo: VideoTechnicalInfo,
        metrics: List<FrameSampleMetric>
    ) {
        try {
            val bundle = highlightAdvisor.analyzeHighlightsAndDetails(
                sourceTitle = sourceTitle,
                technicalInfo = technicalInfo,
                frameMetrics = metrics,
                pacingMode = _uiState.value.qualitySettings.pacingMode
            )
            applyQualityPreset(bundle.recommendedPreset)
            _uiState.update {
                it.copy(
                    clips = bundle.clips,
                    detailsSpec = bundle.details,
                    qualitySettings = it.qualitySettings.copy(globalSpeed = bundle.recommendedSpeed),
                    aiAnalysisState = AiAnalysisState.Success(bundle.noticeMessage),
                    toastMessage = "تم استخراج ${bundle.clips.count { c -> c.isIncluded }} لقطات مهمة وتجهيز الشرح والمونتاج عبر Gemini"
                )
            }
            persistCurrentProject(exportedPath = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini AI analysis failed", e)
            val fallbackSingleClip = if (_uiState.value.clips.isEmpty()) {
                listOf(
                    HighlightClip(
                        id = UUID.randomUUID().toString(),
                        title = "المقطع الكامل (تحرير يدوي)",
                        reason = "يمكنك قص اللقطات يدوياً أو إعادة المحاولة مع Gemini",
                        viralityScore = 80,
                        startMs = 0L,
                        endMs = technicalInfo.durationMs,
                        isIncluded = true,
                        isHook = true,
                        thumbnailPath = metrics.firstOrNull()?.thumbnailPath
                    )
                )
            } else {
                _uiState.value.clips
            }
            _uiState.update {
                it.copy(
                    clips = fallbackSingleClip,
                    aiAnalysisState = AiAnalysisState.Error(
                        e.localizedMessage ?: "فشل الاتصال بـ Gemini AI Logic."
                    ),
                    toastMessage = "تعذر تحليل Gemini — يمكنك التحرير يدوياً أو إعادة المحاولة"
                )
            }
            persistCurrentProject(exportedPath = null)
        }
    }

    fun toggleFeature(feature: EditingFeatureId) {
        val state = _uiState.value
        if (!state.hasLoadedVideo) return
        val currentlyActive = feature in state.activeFeatures

        when (feature) {
            EditingFeatureId.F01_AUTO_HOOK_FIRST -> {
                val sorted = state.clips.sortedByDescending { it.viralityScore }
                val updated = sorted.mapIndexed { idx, clip ->
                    clip.copy(isHook = idx == 0, isIncluded = if (idx == 0) true else clip.isIncluded)
                }
                _uiState.update { it.copy(clips = updated) }
            }
            EditingFeatureId.F02_DEAD_AIR_TRIM -> {
                if (currentlyActive) {
                    _uiState.update { s -> s.copy(clips = s.clips.map { it.copy(isIncluded = true) }) }
                } else {
                    if (state.clips.size > 1) {
                        val cutoff = (state.clips.size * 0.7f).toInt().coerceAtLeast(1)
                        val rankedIds = state.clips.sortedByDescending { it.viralityScore }.take(cutoff).map { it.id }.toSet()
                        _uiState.update { s ->
                            s.copy(clips = s.clips.map { it.copy(isIncluded = it.id in rankedIds || it.isHook) })
                        }
                    } else {
                        val tech = state.technicalInfo ?: return
                        val dur = tech.durationMs
                        if (dur > 4000L) {
                            val hook = HighlightClip(
                                id = UUID.randomUUID().toString(),
                                title = "الهوك السريع",
                                reason = "أول 3 ثوانٍ جذابة",
                                viralityScore = 94,
                                startMs = 0L,
                                endMs = (dur / 3L).coerceAtLeast(1500L),
                                isIncluded = true,
                                isHook = true,
                                thumbnailPath = state.frameMetrics.firstOrNull()?.thumbnailPath
                            )
                            val midFiller = HighlightClip(
                                id = UUID.randomUUID().toString(),
                                title = "فترة انتقالية بطيئة",
                                reason = "تم استبعادها لرفع الإيقاع",
                                viralityScore = 52,
                                startMs = dur / 3L,
                                endMs = (dur * 2L) / 3L,
                                isIncluded = false,
                                isHook = false,
                                thumbnailPath = state.frameMetrics.getOrNull(state.frameMetrics.size / 2)?.thumbnailPath
                            )
                            val climax = HighlightClip(
                                id = UUID.randomUUID().toString(),
                                title = "ذروة المشهد الأهم",
                                reason = "أقوى لقطة ختامية",
                                viralityScore = 96,
                                startMs = (dur * 2L) / 3L,
                                endMs = dur,
                                isIncluded = true,
                                isHook = false,
                                thumbnailPath = state.frameMetrics.lastOrNull()?.thumbnailPath
                            )
                            _uiState.update { it.copy(clips = listOf(hook, midFiller, climax)) }
                        }
                    }
                }
            }
            EditingFeatureId.F03_HOOK_LOOP_REPLAY ->
                updateQualitySettings { it.copy(loopHookAtEnd = !currentlyActive) }
            EditingFeatureId.F04_JUMP_CUT_TIGHTENER ->
                updateQualitySettings { it.copy(jumpCutTrimMs = if (currentlyActive) 0L else 250L) }
            EditingFeatureId.F05_TOP_3_HIGHLIGHTS ->
                updateQualitySettings { it.copy(top3Only = !currentlyActive) }
            EditingFeatureId.F06_ASPECT_REELS_9_16 ->
                updateQualitySettings {
                    it.copy(aspectRatioMode = if (currentlyActive) AspectRatioMode.ORIGINAL else AspectRatioMode.PORTRAIT_9_16)
                }
            EditingFeatureId.F07_GEMINI_STORY_ARC -> reAnalyzeHighlights()
            EditingFeatureId.F08_OUTRO_CTA_CARD ->
                updateQualitySettings { it.copy(includeOutroCta = !currentlyActive) }

            EditingFeatureId.F09_AI_FULL_EXPLANATION -> {
                if (currentlyActive) {
                    updateDetailsSpec { it.copy(description = "") }
                } else {
                    val generated = buildFallbackExplanation(state)
                    updateDetailsSpec { it.copy(description = generated) }
                }
            }
            EditingFeatureId.F10_VOICEOVER_SCRIPT -> {
                if (currentlyActive) {
                    updateDetailsSpec { it.copy(voiceoverScript = "") }
                } else {
                    val script = buildFallbackVoiceover(state)
                    updateDetailsSpec { it.copy(voiceoverScript = script) }
                }
            }
            EditingFeatureId.F11_CHAPTER_TIMESTAMPS -> {
                if (currentlyActive) {
                    updateDetailsSpec { it.copy(chapterTimestamps = "") }
                } else {
                    val chapters = buildChaptersFromClips(state.clips)
                    updateDetailsSpec { it.copy(chapterTimestamps = chapters) }
                }
            }
            EditingFeatureId.F12_VIRAL_TITLE_HOOKS -> {
                if (currentlyActive) {
                    updateDetailsSpec { it.copy(viralTitle = "") }
                } else {
                    val baseTitle = state.inspectionResult?.title?.ifBlank { "أهم اللقطات" } ?: "أهم اللقطات"
                    val hooks = state.detailsSpec.titleHookSuggestions.ifEmpty {
                        listOf(
                            "أقوى لحظة في $baseTitle 🔥",
                            "ملخص وشرح $baseTitle في ثوانٍ ⚡",
                            "لن تصدق ما حدث في $baseTitle!"
                        )
                    }
                    updateDetailsSpec {
                        it.copy(
                            viralTitle = hooks.first(),
                            titleHookSuggestions = hooks
                        )
                    }
                }
            }
            EditingFeatureId.F13_BURN_IN_EXPLANATION ->
                updateDetailsSpec {
                    it.copy(
                        bottomExplanationEnabled = !currentlyActive,
                        bottomExplanationText = it.bottomExplanationText.ifBlank {
                            it.viralTitle.ifBlank { "ملخص وشرح أهم اللقطات" }
                        }
                    )
                }
            EditingFeatureId.F14_TOP_HOOK_BANNER ->
                updateDetailsSpec {
                    it.copy(
                        burnInEnabled = !currentlyActive,
                        burnInText = it.burnInText.ifBlank { "شاهد اللحظة الأهم 🔥" }
                    )
                }
            EditingFeatureId.F15_CHANNEL_WATERMARK ->
                updateDetailsSpec {
                    it.copy(
                        watermarkEnabled = !currentlyActive,
                        watermarkText = it.watermarkText.ifBlank { "@ClipCraft" }
                    )
                }
            EditingFeatureId.F16_SEO_HASHTAGS ->
                updateDetailsSpec {
                    it.copy(hashtags = if (currentlyActive) "" else "#مونتاج #شرح #لقطات_مهمة #رييلز #ClipCraft")
                }

            EditingFeatureId.F17_REELS_SPEED_1_15 ->
                updateQualitySettings { it.copy(globalSpeed = if (currentlyActive) 1.0f else 1.15f) }
            EditingFeatureId.F18_CINEMATIC_SLOWMO ->
                updateQualitySettings { it.copy(globalSpeed = if (currentlyActive) 1.0f else 0.70f) }
            EditingFeatureId.F19_SHORTS_15S_CAP ->
                applyPacingMode(if (currentlyActive) PacingMode.FULL_HIGHLIGHTS else PacingMode.SHORT_15S)
            EditingFeatureId.F20_TIKTOK_30S_CAP ->
                applyPacingMode(if (currentlyActive) PacingMode.FULL_HIGHLIGHTS else PacingMode.REEL_30S)
            EditingFeatureId.F21_SUMMARY_60S_CAP ->
                applyPacingMode(if (currentlyActive) PacingMode.FULL_HIGHLIGHTS else PacingMode.SUMMARY_60S)
            EditingFeatureId.F22_HYPER_TIMELAPSE ->
                updateQualitySettings { it.copy(globalSpeed = if (currentlyActive) 1.0f else 1.60f) }
            EditingFeatureId.F23_PITCH_PRESERVED ->
                updateQualitySettings { it.copy(pitchPreserved = !currentlyActive) }
            EditingFeatureId.F24_TIMECODE_STAMP ->
                updateQualitySettings { it.copy(showTimecodeStamp = !currentlyActive) }

            EditingFeatureId.F25_UPSCALE_1080P ->
                updateQualitySettings {
                    it.copy(targetResolution = if (currentlyActive) TargetResolution.ORIGINAL else TargetResolution.HD_1080P)
                }
            EditingFeatureId.F26_UPSCALE_4K ->
                updateQualitySettings {
                    it.copy(targetResolution = if (currentlyActive) TargetResolution.HD_1080P else TargetResolution.UHD_4K)
                }
            EditingFeatureId.F27_ULTRA_BITRATE_16M ->
                updateQualitySettings {
                    it.copy(bitrateTier = if (currentlyActive) BitrateTier.HIGH_8M else BitrateTier.ULTRA_16M)
                }
            EditingFeatureId.F28_HDR_CONTRAST_PUNCH ->
                updateQualitySettings { it.copy(contrast = if (currentlyActive) 0f else 0.22f) }
            EditingFeatureId.F29_STUDIO_BRIGHT_LIFT ->
                updateQualitySettings { it.copy(brightness = if (currentlyActive) 0f else 0.10f) }
            EditingFeatureId.F30_COLOR_VIBRANCE ->
                updateQualitySettings {
                    it.copy(
                        saturation = if (currentlyActive) 0f else 22f,
                        noirMode = false
                    )
                }
            EditingFeatureId.F31_CINEMA_WARMTH ->
                updateQualitySettings {
                    it.copy(
                        warmth = if (currentlyActive) 0f else 0.08f,
                        noirMode = false
                    )
                }
            EditingFeatureId.F32_NOIR_MONOCHROME ->
                updateQualitySettings { it.copy(noirMode = !currentlyActive) }

            EditingFeatureId.F33_ROTATE_90 ->
                updateQualitySettings { it.copy(rotationDegrees = if (currentlyActive) 0f else 90f) }
            EditingFeatureId.F34_MIRROR_FLIP ->
                updateQualitySettings { it.copy(mirrorHorizontal = !currentlyActive) }
            EditingFeatureId.F35_DYNAMIC_PUNCH_ZOOM ->
                updateQualitySettings { it.copy(zoomScale = if (currentlyActive) 1.0f else 1.12f) }
            EditingFeatureId.F36_MOODY_DARK_TONE ->
                updateQualitySettings { it.copy(moodyDarkMode = !currentlyActive) }
            EditingFeatureId.F37_SMOOTH_60FPS ->
                updateQualitySettings { it.copy(targetFps = if (currentlyActive) 30 else 60) }
            EditingFeatureId.F38_MUTE_B_ROLL ->
                updateQualitySettings { it.copy(removeAudio = !currentlyActive) }
            EditingFeatureId.F39_CODEC_HEVC_H265 ->
                updateQualitySettings {
                    it.copy(codecMode = if (currentlyActive) VideoCodecMode.H264_COMPAT else VideoCodecMode.H265_HEVC)
                }
            EditingFeatureId.F40_AUTO_SAVE_GALLERY ->
                updateQualitySettings { it.copy(autoSaveToGallery = !currentlyActive) }
        }
    }

    fun activateProMontageBundle() {
        val state = _uiState.value
        if (!state.hasLoadedVideo) return
        val updatedQuality = state.qualitySettings.copy(
            preset = QualityPreset.VIRAL_HDR_PUNCH,
            targetResolution = TargetResolution.HD_1080P,
            bitrateTier = BitrateTier.ULTRA_16M,
            contrast = 0.22f,
            brightness = 0.06f,
            saturation = 22f,
            warmth = 0.05f,
            globalSpeed = 1.15f,
            jumpCutTrimMs = 200L,
            loopHookAtEnd = true,
            includeOutroCta = true,
            showTimecodeStamp = true,
            targetFps = 60,
            pitchPreserved = true,
            autoSaveToGallery = true,
            noirMode = false
        )
        val updatedDetails = state.detailsSpec.copy(
            viralTitle = state.detailsSpec.viralTitle.ifBlank {
                "ملخص وأقوى لقطات: ${state.inspectionResult?.title ?: "الفيديو"}"
            },
            burnInEnabled = true,
            burnInText = state.detailsSpec.burnInText.ifBlank { "شاهد أهم اللقطات والشرح 🔥" },
            bottomExplanationEnabled = true,
            bottomExplanationText = state.detailsSpec.bottomExplanationText.ifBlank {
                "شرح سريع ومونتاج احترافي لأبرز المشاهد"
            },
            watermarkEnabled = true,
            watermarkText = state.detailsSpec.watermarkText.ifBlank { "@ClipCraft" },
            voiceoverScript = state.detailsSpec.voiceoverScript.ifBlank { buildFallbackVoiceover(state) },
            chapterTimestamps = state.detailsSpec.chapterTimestamps.ifBlank { buildChaptersFromClips(state.clips) },
            description = state.detailsSpec.description.ifBlank { buildFallbackExplanation(state) },
            hashtags = state.detailsSpec.hashtags.ifBlank { "#مونتاج #شرح #لقطات_مهمة #رييلز #ClipCraft" }
        )
        _uiState.update {
            it.copy(
                qualitySettings = updatedQuality,
                detailsSpec = updatedDetails,
                toastMessage = "تم تفعيل باقة المونتاج والشرح الشاملة (${computeActiveFeatures(it.copy(qualitySettings = updatedQuality, detailsSpec = updatedDetails)).size}/40 ميزة)"
            )
        }
    }

    private fun buildFallbackExplanation(state: StudioUiState): String {
        val title = state.inspectionResult?.title ?: state.technicalInfo?.fileName ?: "الفيديو"
        val clipSummaries = state.clips.filter { it.isIncluded }.mapIndexed { i, c ->
            "${i + 1}. ${c.title}: ${c.reason}"
        }.joinToString("\n")
        return "شرح تفصيلي لمونتاج ($title):\n$clipSummaries"
    }

    private fun buildFallbackVoiceover(state: StudioUiState): String {
        return state.clips.filter { it.isIncluded }.mapIndexed { idx, c ->
            "[${formatMillisTimecode(c.startMs)}] لقطة ${idx + 1} (${c.title}): ${c.reason}"
        }.joinToString("\n")
    }

    private fun buildChaptersFromClips(clips: List<HighlightClip>): String {
        if (clips.isEmpty()) return "00:00 الهوك الافتتاحي"
        return clips.filter { it.isIncluded }.joinToString("\n") { c ->
            "${formatMillisTimecode(c.startMs).substringBefore('.')} ${c.title}"
        }
    }

    fun seekPreviewTo(ms: Long) {
        seekNonce++
        _uiState.update { it.copy(seekRequestMs = ms to seekNonce) }
    }

    fun toggleClipIncluded(clipId: String) {
        _uiState.update { state ->
            state.copy(
                clips = state.clips.map { if (it.id == clipId) it.copy(isIncluded = !it.isIncluded) else it }
            )
        }
    }

    fun updateClipRange(clipId: String, startMs: Long, endMs: Long) {
        val maxDur = _uiState.value.technicalInfo?.durationMs ?: Long.MAX_VALUE
        val safeStart = startMs.coerceIn(0L, (maxDur - 500L).coerceAtLeast(0L))
        val safeEnd = endMs.coerceIn(safeStart + 500L, maxDur)
        _uiState.update { state ->
            state.copy(
                clips = state.clips.map {
                    if (it.id == clipId) it.copy(startMs = safeStart, endMs = safeEnd) else it
                }
            )
        }
    }

    fun updateClipSpeed(clipId: String, speed: Float) {
        _uiState.update { state ->
            state.copy(
                clips = state.clips.map {
                    if (it.id == clipId) it.copy(speedMultiplier = speed.coerceIn(0.5f, 2.0f)) else it
                }
            )
        }
    }

    fun moveClipUp(clipId: String) {
        _uiState.update { state ->
            val list = state.clips.toMutableList()
            val idx = list.indexOfFirst { it.id == clipId }
            if (idx > 0) {
                val item = list.removeAt(idx)
                list.add(idx - 1, item)
            }
            state.copy(clips = list)
        }
    }

    fun moveClipDown(clipId: String) {
        _uiState.update { state ->
            val list = state.clips.toMutableList()
            val idx = list.indexOfFirst { it.id == clipId }
            if (idx in 0 until list.lastIndex) {
                val item = list.removeAt(idx)
                list.add(idx + 1, item)
            }
            state.copy(clips = list)
        }
    }

    fun addCustomClip() {
        val tech = _uiState.value.technicalInfo ?: return
        val dur = tech.durationMs
        val newClip = HighlightClip(
            id = UUID.randomUUID().toString(),
            title = "لقطة مخصصة #${_uiState.value.clips.size + 1}",
            reason = "مضافة يدوياً من خط الزمن",
            viralityScore = 85,
            startMs = 0L,
            endMs = (dur / 3L).coerceIn(1500L, dur),
            speedMultiplier = 1.0f,
            isIncluded = true,
            isHook = false,
            thumbnailPath = _uiState.value.frameMetrics.firstOrNull()?.thumbnailPath
        )
        _uiState.update { it.copy(clips = it.clips + newClip) }
    }

    fun removeClip(clipId: String) {
        _uiState.update { state ->
            state.copy(clips = state.clips.filterNot { it.id == clipId })
        }
    }

    fun applyPacingMode(mode: PacingMode) {
        _uiState.update { state ->
            val maxTarget = mode.maxTargetDurationMs
            val updatedClips = if (maxTarget == null) {
                state.clips.mapIndexed { idx, clip -> clip.copy(isIncluded = idx < 5) }
            } else {
                var running = 0L
                state.clips.map { clip ->
                    val dur = (clip.rawDurationMs / (clip.speedMultiplier * state.qualitySettings.globalSpeed)).toLong()
                    val keep = (running + dur <= maxTarget) || clip.isHook
                    if (keep) running += dur
                    clip.copy(isIncluded = keep)
                }
            }
            state.copy(
                qualitySettings = state.qualitySettings.copy(pacingMode = mode),
                clips = updatedClips
            )
        }
    }

    fun applyQualityPreset(preset: QualityPreset) {
        _uiState.update { state ->
            val updated = when (preset) {
                QualityPreset.ORIGINAL -> state.qualitySettings.copy(
                    preset = preset,
                    contrast = 0f,
                    brightness = 0f,
                    saturation = 0f,
                    warmth = 0f,
                    noirMode = false,
                    moodyDarkMode = false
                )
                QualityPreset.CINEMA_4K -> state.qualitySettings.copy(
                    preset = preset,
                    targetResolution = TargetResolution.UHD_4K,
                    bitrateTier = BitrateTier.ULTRA_16M,
                    contrast = 0.22f,
                    brightness = -0.02f,
                    saturation = 12f,
                    warmth = 0.06f,
                    noirMode = false
                )
                QualityPreset.VIRAL_HDR_PUNCH -> state.qualitySettings.copy(
                    preset = preset,
                    targetResolution = TargetResolution.HD_1080P,
                    bitrateTier = BitrateTier.HIGH_8M,
                    contrast = 0.18f,
                    brightness = 0.04f,
                    saturation = 22f,
                    warmth = 0.03f,
                    noirMode = false
                )
                QualityPreset.STUDIO_BRIGHT -> state.qualitySettings.copy(
                    preset = preset,
                    targetResolution = TargetResolution.QHD_1440P,
                    bitrateTier = BitrateTier.HIGH_8M,
                    contrast = 0.10f,
                    brightness = 0.12f,
                    saturation = 8f,
                    warmth = -0.02f,
                    noirMode = false
                )
            }
            state.copy(qualitySettings = updated)
        }
    }

    fun updateQualitySettings(transform: (QualitySettings) -> QualitySettings) {
        _uiState.update { it.copy(qualitySettings = transform(it.qualitySettings)) }
    }

    fun updateDetailsSpec(transform: (VideoDetailsSpec) -> VideoDetailsSpec) {
        _uiState.update { it.copy(detailsSpec = transform(it.detailsSpec)) }
    }

    fun startExport() {
        val tech = _uiState.value.technicalInfo
        if (tech == null || (!tech.isRemoteHlsStream && !File(tech.filePath).exists())) {
            _uiState.update { it.copy(toastMessage = "قم بتحميل أو اختيار فيديو أولاً") }
            return
        }
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            val stateSnapshot = _uiState.value
            montageExporter.exportMontage(
                sourceFilePath = tech.filePath,
                clips = stateSnapshot.clips,
                quality = stateSnapshot.qualitySettings,
                details = stateSnapshot.detailsSpec
            ).collect { exportState ->
                _uiState.update { it.copy(exportState = exportState) }
                if (exportState is ExportState.Completed) {
                    persistCurrentProject(exportedPath = exportState.outputPath)
                    refreshStoredFiles()
                }
            }
        }
    }

    fun cancelExport() {
        montageExporter.cancelExport()
        exportJob?.cancel()
        _uiState.update { it.copy(exportState = ExportState.Idle, toastMessage = "تم إيقاف التصدير") }
    }

    fun saveExportedToGallery() {
        val completed = _uiState.value.exportState as? ExportState.Completed ?: return
        val title = _uiState.value.detailsSpec.viralTitle.ifBlank { "ClipCraft_Montage" }
        viewModelScope.launch {
            val result = montageExporter.saveToDeviceGallery(completed.outputPath, title)
            _uiState.update {
                it.copy(
                    toastMessage = result.fold(
                        onSuccess = { "تم حفظ المونتاج بنجاح في معرض الفيديو (Movies/ClipCraft)" },
                        onFailure = { err -> "تعذر الحفظ: ${err.localizedMessage}" }
                    )
                )
            }
        }
    }

    fun loadSavedProject(project: SavedProject) {
        viewModelScope.launch {
            val isRemote = project.downloadedFilePath.startsWith("http://", true) ||
                project.downloadedFilePath.startsWith("https://", true)
            val file = File(project.downloadedFilePath)
            if (!isRemote && !file.exists()) {
                _uiState.update { it.copy(toastMessage = "ملف الفيديو الأصلي غير موجود في التخزين") }
                return@launch
            }
            val tech = if (isRemote) {
                frameInspector.inspectRemoteStream(project.downloadedFilePath, project.title)
            } else {
                frameInspector.inspectVideoFile(project.downloadedFilePath)
            }
            val exportState = project.exportedFilePath?.let { outPath ->
                val outFile = File(outPath)
                if (outFile.exists()) {
                    val outInfo = frameInspector.inspectVideoFile(outPath)
                    ExportState.Completed(
                        outputPath = outPath,
                        fileSizeBytes = outFile.length(),
                        durationMs = outInfo.durationMs,
                        averageVideoBitrate = outInfo.bitrateBps.toInt(),
                        width = outInfo.width,
                        height = outInfo.height
                    )
                } else ExportState.Idle
            } ?: ExportState.Idle

            _uiState.update {
                it.copy(
                    projectId = project.id,
                    urlInput = project.sourceUrl,
                    technicalInfo = tech,
                    inspectionResult = LinkInspectionResult(
                        originalUrl = project.sourceUrl,
                        resolvedVideoUrl = project.sourceUrl,
                        title = project.title,
                        contentType = tech.mimeType,
                        contentLengthBytes = tech.fileSizeBytes,
                        isDirectVideo = !isRemote,
                        extractedFromHtml = false,
                        isHlsStream = isRemote
                    ),
                    clips = project.clips,
                    qualitySettings = project.quality,
                    detailsSpec = project.details,
                    exportState = exportState,
                    aiAnalysisState = AiAnalysisState.Success("تم تحميل خطة المونتاج المحفوظة"),
                    downloadState = DownloadState.Completed(
                        project.downloadedFilePath,
                        LinkInspectionResult(
                            project.sourceUrl,
                            project.sourceUrl,
                            project.title,
                            tech.mimeType,
                            tech.fileSizeBytes,
                            isDirectVideo = !isRemote,
                            extractedFromHtml = false,
                            isHlsStream = isRemote
                        ),
                        tech
                    ),
                    toastMessage = "تم فتح المشروع: ${project.title}"
                )
            }
        }
    }

    fun deleteSavedProject(projectId: String) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
            _uiState.update { it.copy(toastMessage = "تم حذف المشروع من السجل") }
        }
    }

    fun refreshStoredFiles() {
        _uiState.update { it.copy(storedFiles = downloadEngine.listAllStoredMediaFiles()) }
    }

    fun deleteStoredFile(filePath: String) {
        downloadEngine.deleteStoredFile(filePath)
        refreshStoredFiles()
        _uiState.update { it.copy(toastMessage = "تم حذف الملف من التخزين الداخلي") }
    }

    private suspend fun persistCurrentProject(exportedPath: String?) {
        val state = _uiState.value
        val tech = state.technicalInfo ?: return
        repository.saveProject(
            id = state.projectId,
            title = state.detailsSpec.viralTitle.ifBlank { state.inspectionResult?.title ?: tech.fileName },
            sourceUrl = state.urlInput.ifBlank { tech.filePath },
            technicalInfo = tech,
            exportedFilePath = exportedPath,
            clips = state.clips,
            quality = state.qualitySettings,
            details = state.detailsSpec
        )
    }

    override fun onCleared() {
        super.onCleared()
        downloadEngine.cancelActiveDownload()
        montageExporter.cancelExport()
    }
}
