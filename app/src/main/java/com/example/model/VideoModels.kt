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
    val isHlsStream: Boolean = false,
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
    val frameCountSampled: Int = 0,
    val isRemoteHlsStream: Boolean = false
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
enum class AspectRatioMode(val labelAr: String, val ratio: Float?) {
    ORIGINAL("الأصل", null),
    PORTRAIT_9_16("9:16 رييلز/تيك توك", 9f / 16f),
    LANDSCAPE_16_9("16:9 يوتيوب", 16f / 9f),
    SQUARE_1_1("1:1 مربع", 1f)
}

@Serializable
enum class VideoCodecMode(val labelAr: String) {
    H264_COMPAT("H.264 متوافق"),
    H265_HEVC("H.265 / HEVC فائق")
}

@Serializable
enum class OverlayPosition(val labelAr: String, val yNormalized: Float) {
    TOP_HOOK("أعلى الشاشة (Hook)", 0.72f),
    CENTER_PUNCH("وسط الفيديو", 0.0f),
    BOTTOM_CAPTION("أسفل الفيديو (شرح/ترجمة)", -0.70f)
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
    val pacingMode: PacingMode = PacingMode.FULL_HIGHLIGHTS,
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.ORIGINAL,
    val jumpCutTrimMs: Long = 0L,
    val loopHookAtEnd: Boolean = false,
    val top3Only: Boolean = false,
    val includeOutroCta: Boolean = false,
    val outroText: String = "اشترك وتابع للمزيد من اللقطات القوية 🔥",
    val rotationDegrees: Float = 0f,
    val mirrorHorizontal: Boolean = false,
    val zoomScale: Float = 1.0f,
    val targetFps: Int = 30,
    val codecMode: VideoCodecMode = VideoCodecMode.H264_COMPAT,
    val autoSaveToGallery: Boolean = false,
    val showTimecodeStamp: Boolean = false,
    val noirMode: Boolean = false,
    val moodyDarkMode: Boolean = false,
    val pitchPreserved: Boolean = true
)

@Serializable
data class VideoDetailsSpec(
    val viralTitle: String = "",
    val titleHookSuggestions: List<String> = emptyList(),
    val burnInEnabled: Boolean = true,
    val burnInText: String = "",
    val overlayPosition: OverlayPosition = OverlayPosition.TOP_HOOK,
    val bottomExplanationEnabled: Boolean = false,
    val bottomExplanationText: String = "",
    val watermarkEnabled: Boolean = false,
    val watermarkText: String = "@ClipCraft",
    val voiceoverScript: String = "",
    val chapterTimestamps: String = "",
    val description: String = "",
    val hashtags: String = ""
)

@Serializable
enum class FeatureCategory(val labelAr: String) {
    MONTAGE_HIGHLIGHTS("المونتاج واللقطات المهمة"),
    EXPLANATION_DETAILS("الشرح والتفاصيل والنصوص"),
    TIMING_PACING("الوقت والسرعة والإيقاع"),
    QUALITY_COLOR("رفع الجودة والألوان"),
    EFFECTS_EXPORT("المؤثرات والصوت والتصدير")
}

/**
 * 40 real, actionable video editing features that activate when editing any video.
 */
@Serializable
enum class EditingFeatureId(
    val number: Int,
    val titleAr: String,
    val subtitleAr: String,
    val category: FeatureCategory
) {
    // 1-8: Montage & Highlights
    F01_AUTO_HOOK_FIRST(1, "الهوك الافتتاحي أولاً", "وضع أقوى 3 ثوانٍ في بداية المونتاج", FeatureCategory.MONTAGE_HIGHLIGHTS),
    F02_DEAD_AIR_TRIM(2, "قص اللحظات المملة", "استبعاد المقاطع بطيئة الحركة تلقائياً", FeatureCategory.MONTAGE_HIGHLIGHTS),
    F03_HOOK_LOOP_REPLAY(3, "تكرار الهوك في النهاية", "إعادة لقطة البداية في الخاتمة لرفع الإكمال", FeatureCategory.MONTAGE_HIGHLIGHTS),
    F04_JUMP_CUT_TIGHTENER(4, "قص قفزات سريعة", "تضييق أطراف كل لقطة 250ms لإيقاع سريع", FeatureCategory.MONTAGE_HIGHLIGHTS),
    F05_TOP_3_HIGHLIGHTS(5, "أفضل 3 لقطات فقط", "التركيز على أعلى 3 مشاهد جذباً للمشاهدات", FeatureCategory.MONTAGE_HIGHLIGHTS),
    F06_ASPECT_REELS_9_16(6, "مقاس رييلز عمودي 9:16", "قص تلقائي لملء شاشة تيك توك وشورتس", FeatureCategory.MONTAGE_HIGHLIGHTS),
    F07_GEMINI_STORY_ARC(7, "ترتيب درامي عبر Gemini", "تحليل وتنظيم اللقطات بالذكاء الاصطناعي", FeatureCategory.MONTAGE_HIGHLIGHTS),
    F08_OUTRO_CTA_CARD(8, "بطاقة ختام تحفيزية", "إضافة شريحة دعوة للمتابعة بنهاية الفيديو", FeatureCategory.MONTAGE_HIGHLIGHTS),

    // 9-16: AI Explanation & Details
    F09_AI_FULL_EXPLANATION(9, "توليد الشرح التفصيلي", "صياغة شرح كامل لمحتوى الفيديو هنا", FeatureCategory.EXPLANATION_DETAILS),
    F10_VOICEOVER_SCRIPT(10, "سيناريو تعليق صوتي", "نص شرح احترافي مقسم مع اللقطات", FeatureCategory.EXPLANATION_DETAILS),
    F11_CHAPTER_TIMESTAMPS(11, "فهرس التوقيتات والفصول", "توليد فصول 00:00 لوصف اليوتيوب", FeatureCategory.EXPLANATION_DETAILS),
    F12_VIRAL_TITLE_HOOKS(12, "3 عناوين عالية النقر", "اقتراح 3 عناوين جاهزة لجذب المشاهدات", FeatureCategory.EXPLANATION_DETAILS),
    F13_BURN_IN_EXPLANATION(13, "شريط الشرح أسفل الفيديو", "طباعة ملخص الشرح داخل إطارات الفيديو", FeatureCategory.EXPLANATION_DETAILS),
    F14_TOP_HOOK_BANNER(14, "شارة الهوك العلوية", "طباعة جملة الجذب أعلى الشاشة أثناء التصدير", FeatureCategory.EXPLANATION_DETAILS),
    F15_CHANNEL_WATERMARK(15, "علامة مائية لحسابك", "حفظ حقوقك بطباعة اسم قناتك على الفيديو", FeatureCategory.EXPLANATION_DETAILS),
    F16_SEO_HASHTAGS(16, "هاشتاجات SEO مستهدفة", "توليد وسوم عربية وعالمية للانتشار", FeatureCategory.EXPLANATION_DETAILS),

    // 17-24: Timing & Pacing
    F17_REELS_SPEED_1_15(17, "إيقاع رييلز 1.15x", "تسريع ذكي خفيف يمنع ملل المشاهد", FeatureCategory.TIMING_PACING),
    F18_CINEMATIC_SLOWMO(18, "حركة بطيئة سينمائية 0.7x", "إبطاء درامي سلس للقطات المميزة", FeatureCategory.TIMING_PACING),
    F19_SHORTS_15S_CAP(19, "ضغط شورتس 15 ثانية", "تحديد المدة القصوى للقطات بـ 15 ثانية", FeatureCategory.TIMING_PACING),
    F20_TIKTOK_30S_CAP(20, "ضبط مدة 30 ثانية", "موازنة اللقطات لتناسب تيك توك 30 ثانية", FeatureCategory.TIMING_PACING),
    F21_SUMMARY_60S_CAP(21, "ملخص 60 ثانية", "تجميع أهم اللقطات في دقيقة واحدة", FeatureCategory.TIMING_PACING),
    F22_HYPER_TIMELAPSE(22, "تايم لابس سريع 1.6x", "تسريع فائق للأحداث الطويلة", FeatureCategory.TIMING_PACING),
    F23_PITCH_PRESERVED(23, "حفظ نبرة الصوت", "منع تشوه الصوت عند تسريع الفيديو", FeatureCategory.TIMING_PACING),
    F24_TIMECODE_STAMP(24, "ختم التوقيت والمدة", "طباعة شارة المدة الاحترافية بالزاوية", FeatureCategory.TIMING_PACING),

    // 25-32: Quality & Color Grading
    F25_UPSCALE_1080P(25, "ترقية الدقة 1080p", "رفع أبعاد الفيديو إلى Full HD", FeatureCategory.QUALITY_COLOR),
    F26_UPSCALE_4K(26, "ترقية الدقة 4K UHD", "تصدير فائق الوضوح بدقة 2160p", FeatureCategory.QUALITY_COLOR),
    F27_ULTRA_BITRATE_16M(27, "مضاعفة البت 16 Mbps", "تصفية التكسير والضبابية في الحركة", FeatureCategory.QUALITY_COLOR),
    F28_HDR_CONTRAST_PUNCH(28, "تباين HDR حاد", "إبراز التفاصيل والظلال عبر OpenGL", FeatureCategory.QUALITY_COLOR),
    F29_STUDIO_BRIGHT_LIFT(29, "تفتيح إضاءة الاستوديو", "إنعاش اللقطات المظلمة بوضوح عالٍ", FeatureCategory.QUALITY_COLOR),
    F30_COLOR_VIBRANCE(30, "تشبع وحيوية الألوان", "تعزيز إشباع الألوان لجذب العين", FeatureCategory.QUALITY_COLOR),
    F31_CINEMA_WARMTH(31, "تدريج دافئ سينمائي", "توازن ألوان دافئ بمظهر أفلام", FeatureCategory.QUALITY_COLOR),
    F32_NOIR_MONOCHROME(32, "أبيض وأسود كلاسيكي", "تحويل المشاهد للنمط السينمائي الأحادي", FeatureCategory.QUALITY_COLOR),

    // 33-40: Visual Effects, Audio & Export
    F33_ROTATE_90(33, "تدوير الإطار 90°", "تعديل اتجاه الفيديو العمودي أو الأفقي", FeatureCategory.EFFECTS_EXPORT),
    F34_MIRROR_FLIP(34, "قلب أفقي (مرآة)", "عكس اتجاه المشهد أفقياً", FeatureCategory.EFFECTS_EXPORT),
    F35_DYNAMIC_PUNCH_ZOOM(35, "تكبير بؤري 1.12x", "قص الحواف والتركيز على مركز المشهد", FeatureCategory.EFFECTS_EXPORT),
    F36_MOODY_DARK_TONE(36, "طابع غامق سينمائي", "تقليل السطوع مع تباين حاد للقطات الليلية", FeatureCategory.EFFECTS_EXPORT),
    F37_SMOOTH_60FPS(37, "معدل إطارات 60 FPS", "ضبط التصدير بسلاسة حركة 60 إطار/ثانية", FeatureCategory.EFFECTS_EXPORT),
    F38_MUTE_B_ROLL(38, "كتم الصوت (B-Roll)", "استخراج لقطات صامتة للمونتاج الصوتي", FeatureCategory.EFFECTS_EXPORT),
    F39_CODEC_HEVC_H265(39, "ترميز H.265 HEVC", "ضغط ذكي يحافظ على الجودة بحجم أقل", FeatureCategory.EFFECTS_EXPORT),
    F40_AUTO_SAVE_GALLERY(40, "حفظ تلقائي بالمعرض", "حفظ الفيديو المنتج تلقائياً في Movies/ClipCraft", FeatureCategory.EFFECTS_EXPORT)
}

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
