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
import com.example.model.BitrateTier
import com.example.model.DownloadState
import com.example.model.ExportState
import com.example.model.FrameSampleMetric
import com.example.model.HighlightClip
import com.example.model.LinkInspectionResult
import com.example.model.PacingMode
import com.example.model.QualityPreset
import com.example.model.QualitySettings
import com.example.model.TargetResolution
import com.example.model.VideoDetailsSpec
import com.example.model.VideoTechnicalInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        get() = technicalInfo != null && File(technicalInfo.filePath).exists()

    val includedClips: List<HighlightClip>
        get() = clips.filter { it.isIncluded }

    val estimatedOutputDurationMs: Long
        get() = includedClips.sumOf {
            (it.rawDurationMs / (it.speedMultiplier * qualitySettings.globalSpeed).coerceIn(0.5f, 2.5f)).toLong()
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
            downloadEngine.inspectAndDownload(targetUrl).collect { state ->
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
            downloadEngine.importLocalUri(uri).collect { state ->
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
            val metrics = frameInspector.sampleFramesAndEnergy(
                filePath = technicalInfo.filePath,
                projectId = projectId,
                durationMs = technicalInfo.durationMs,
                sampleCount = 12
            )
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
            val metrics = _uiState.value.frameMetrics.ifEmpty {
                frameInspector.sampleFramesAndEnergy(tech.filePath, _uiState.value.projectId, tech.durationMs)
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
                    toastMessage = "تم استخراج ${bundle.clips.count { c -> c.isIncluded }} لقطات مهمة وكتابة التفاصيل عبر Gemini"
                )
            }
            persistCurrentProject(exportedPath = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini AI analysis failed", e)
            // Surface real error transparently and provide base editable clip range so user isn't blocked from manual editing
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
                    toastMessage = "تعذر تحليل Gemini — راجع تفاصيل الخطأ في شاشة المونتاج"
                )
            }
            persistCurrentProject(exportedPath = null)
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
                    warmth = 0f
                )
                QualityPreset.CINEMA_4K -> state.qualitySettings.copy(
                    preset = preset,
                    targetResolution = TargetResolution.UHD_4K,
                    bitrateTier = BitrateTier.ULTRA_16M,
                    contrast = 0.22f,
                    brightness = -0.02f,
                    saturation = 12f,
                    warmth = 0.06f
                )
                QualityPreset.VIRAL_HDR_PUNCH -> state.qualitySettings.copy(
                    preset = preset,
                    targetResolution = TargetResolution.HD_1080P,
                    bitrateTier = BitrateTier.HIGH_8M,
                    contrast = 0.18f,
                    brightness = 0.04f,
                    saturation = 22f,
                    warmth = 0.03f
                )
                QualityPreset.STUDIO_BRIGHT -> state.qualitySettings.copy(
                    preset = preset,
                    targetResolution = TargetResolution.QHD_1440P,
                    bitrateTier = BitrateTier.HIGH_8M,
                    contrast = 0.10f,
                    brightness = 0.12f,
                    saturation = 8f,
                    warmth = -0.02f
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
        if (tech == null || !File(tech.filePath).exists()) {
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
            val file = File(project.downloadedFilePath)
            if (!file.exists()) {
                _uiState.update { it.copy(toastMessage = "ملف الفيديو الأصلي غير موجود في التخزين") }
                return@launch
            }
            val tech = frameInspector.inspectVideoFile(project.downloadedFilePath)
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
                        isDirectVideo = true,
                        extractedFromHtml = false
                    ),
                    clips = project.clips,
                    qualitySettings = project.quality,
                    detailsSpec = project.details,
                    exportState = exportState,
                    aiAnalysisState = AiAnalysisState.Success("تم تحميل خطة المونتاج المحفوظة"),
                    downloadState = DownloadState.Completed(
                        project.downloadedFilePath,
                        LinkInspectionResult(project.sourceUrl, project.sourceUrl, project.title, tech.mimeType, tech.fileSizeBytes, true, false),
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
