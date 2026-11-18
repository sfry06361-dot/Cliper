package com.example.model

import kotlinx.serialization.Serializable

@Serializable
data class LinkInspectionResult(
    val originalUrl: String,
    val resolvedVideoUrl: String,
    val title: String,
    val contentType: String,
    val contentLengthBytes: Long,
    val isDirectVideo: Boolean,
    val extractedFromHtml: Boolean,
    val thumbnailUrl: String? = null,
    val httpStatus: Int = 200,
    val inspectionNotes: String = ""
)

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Inspecting(val url: String, val stageLabel: String) : DownloadState
    data class Downloading(
        val bytesRead: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val progressPercent: Int,
        val inspection: LinkInspectionResult
    ) : DownloadState
    data class Verifying(val inspection: LinkInspectionResult) : DownloadState
    data class Completed(
        val filePath: String,
        val inspection: LinkInspectionResult,
        val technicalInfo: VideoTechnicalInfo
    ) : DownloadState
    data class Error(val message: String, val diagnosticDetail: String? = null) : DownloadState
}

@Serializable
data class VideoTechnicalInfo(
    val filePath: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bitrateBps: Long,
    val fps: Int,
    val hasAudio: Boolean,
    val rotationDegrees: Int,
    val mimeType: String,
    val frameCountSampled: Int = 0
)

@Serializable
data class FrameSampleMetric(
    val timestampMs: Long,
    val thumbnailPath: String,
    val lumaScore: Float,
    val motionDeltaScore: Float,
    val sharpnessScore: Float,
    val combinedEnergy: Float
)

@Serializable
data class HighlightClip(
    val id: String,
    val title: String,
    val reason: String,
    val viralityScore: Int,
    val startMs: Long,
    val endMs: Long,
    val speedMultiplier: Float = 1.0f,
    val isIncluded: Boolean = true,
    val isHook: Boolean = false,
    val energyScore: Float = 0.8f,
    val thumbnailPath: String? = null
) {
    val rawDurationMs: Long get() = (endMs - startMs).coerceAtLeast(500L)
    val effectiveDurationMs: Long get() = (rawDurationMs / speedMultiplier.coerceIn(0.5f, 2.5f)).toLong()
}

@Serializable
enum class QualityPreset(val labelAr: String, val subtitleAr: String) {
    ORIGINAL("أصلي", "بدون تعديل ألوان"),
    CINEMA_4K("4K سينمائي", "تباين عميق وتشبع متوازن"),
    VIRAL_HDR_PUNCH("رييلز حاد HDR", "ألوان مشبعة وجاذبية بصرية عالية"),
    STUDIO_BRIGHT("إضاءة استوديو", "وضوح وسطوع مثالي للشروحات")
}

@Serializable
enum class TargetResolution(val labelAr: String, val targetHeight: Int) {
    ORIGINAL("الدقة الأصلية", 0),
    HD_1080P("1080p Full HD", 1080),
    QHD_1440P("1440p 2K QHD", 1440),
    UHD_4K("2160p 4K UHD", 2160)
}

@Serializable
enum class BitrateTier(val labelAr: String, val bitrateBps: Int) {
    STANDARD_4M("قياسي 4 Mbps", 4_000_000),
    HIGH_8M("عالي 8 Mbps", 8_000_000),
    ULTRA_16M("فائق الدقة 16 Mbps", 16_000_000)
}

@Serializable
enum class PacingMode(val labelAr: String, val maxTargetDurationMs: Long?) {
    FULL_HIGHLIGHTS("كل اللقطات المهمة", null),
    SHORT_15S("هوك سريع 15 ثانية", 15_000L),
    REEL_30S("رييلز / تيك توك 30 ثانية", 30_000L),
    SUMMARY_60S("ملخص احترافي 60 ثانية", 60_000L)
}

@Serializable
enum class OverlayPosition(val labelAr: String, val yNormalized: Float) {
    TOP_HOOK("أعلى الشاشة (Hook)", 0.72f),
    CENTER_PUNCH("وسط الفيديو", 0.0f),
    BOTTOM_CAPTION("أسفل الفيديو (ترجمة/تفاصيل)", -0.70f)
}

@Serializable
data class QualitySettings(
    val preset: QualityPreset = QualityPreset.VIRAL_HDR_PUNCH,
    val targetResolution: TargetResolution = TargetResolution.HD_1080P,
    val bitrateTier: BitrateTier = BitrateTier.HIGH_8M,
    val contrast: Float = 0.16f,
    val brightness: Float = 0.04f,
    val saturation: Float = 18f,
    val warmth: Float = 0.03f,
    val removeAudio: Boolean = false,
    val globalSpeed: Float = 1.1f,
    val pacingMode: PacingMode = PacingMode.FULL_HIGHLIGHTS
)

@Serializable
data class VideoDetailsSpec(
    val viralTitle: String = "",
    val burnInEnabled: Boolean = true,
    val burnInText: String = "",
    val overlayPosition: OverlayPosition = OverlayPosition.TOP_HOOK,
    val description: String = "",
    val hashtags: String = ""
)

sealed interface ExportState {
    data object Idle : ExportState
    data class Exporting(val progressPercent: Int, val stageLabel: String) : ExportState
    data class Completed(
        val outputPath: String,
        val fileSizeBytes: Long,
        val durationMs: Long,
        val averageVideoBitrate: Int,
        val width: Int,
        val height: Int
    ) : ExportState
    data class Error(val message: String) : ExportState
}
