package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.model.DownloadState
import com.example.ui.StudioUiState
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.TelemetryChip
import com.example.ui.components.formatBytesReadable
import com.example.ui.components.formatMillisTimecode
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ViralAmber
import java.io.File

private const val SAMPLE_TEST_VIDEO_URL =
    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"

@Composable
fun StudioLinkScreen(
    uiState: StudioUiState,
    onUrlChange: (String) -> Unit,
    onInspectAndDownload: (String?) -> Unit,
    onCancelDownload: () -> Unit,
    onPickLocalVideo: (android.net.Uri) -> Unit,
    onNavigateToEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onPickLocalVideo(uri)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Banner
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, StudioBorder),
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(176.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_studio_hero),
                            contentDescription = "استوديو كليب كرافت للمونتاج",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            ObsidianBg.copy(alpha = 0.25f),
                                            ObsidianBg.copy(alpha = 0.92f)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = CyanContainer.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "محرك الفحص والتنزيل المدمج • CLIPCRAFT",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = SpaceGroteskFontFamily
                                    ),
                                    color = ElectricCyan,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Text(
                                text = "انسخ رابط أي فيديو وابدأ المونتاج الدقيق",
                                style = MaterialTheme.typography.headlineSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "فحص الرابط وتنزيله داخلياً، استخراج اللقطات المهمة، رفع الجودة، وكتابة التفاصيل.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // Link Paste & Inspect Card
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, StudioBorder),
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StudioSectionHeader(
                            title = "رابط الفيديو للفحص والتنزيل",
                            subtitle = "يدعم روابط MP4/MOV/WebM المباشرة أو صفحات الفيديو المفتوحة",
                            icon = Icons.Rounded.Link
                        )

                        OutlinedTextField(
                            value = uiState.urlInput,
                            onValueChange = onUrlChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("video_url_input"),
                            placeholder = {
                                Text(
                                    text = "https://example.com/video.mp4",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = JetBrainsMonoFontFamily
                                    ),
                                    color = TextSecondary
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = TextPrimary
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricCyan,
                                unfocusedBorderColor = StudioBorder,
                                focusedContainerColor = ObsidianBg,
                                unfocusedContainerColor = ObsidianBg,
                                cursorColor = ElectricCyan
                            ),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (uiState.urlInput.isNotEmpty()) {
                                        IconButton(onClick = { onUrlChange("") }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Clear,
                                                contentDescription = "مسح الرابط",
                                                tint = TextSecondary
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            val pasted = runCatching {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.primaryClip
                                                    ?.takeIf { it.itemCount > 0 }
                                                    ?.getItemAt(0)
                                                    ?.coerceToText(context)
                                                    ?.toString()
                                                    .orEmpty()
                                            }.getOrDefault("")
                                            if (pasted.isNotBlank()) {
                                                onUrlChange(pasted.trim())
                                            }
                                        },
                                        modifier = Modifier.testTag("paste_url_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentPaste,
                                            contentDescription = "لصق من الحافظة",
                                            tint = ElectricCyan
                                        )
                                    }
                                }
                            }
                        )

                        val isBusy = uiState.downloadState is DownloadState.Inspecting ||
                            uiState.downloadState is DownloadState.Downloading ||
                            uiState.downloadState is DownloadState.Verifying

                        Button(
                            onClick = { onInspectAndDownload(null) },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = ObsidianBg
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("inspect_download_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "فحص الرابط وتنزيل الفيديو",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    mediaPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, StudioBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("pick_local_video_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VideoLibrary,
                                    contentDescription = null,
                                    tint = TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "من الجهاز",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            OutlinedButton(
                                onClick = { onInspectAndDownload(SAMPLE_TEST_VIDEO_URL) },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, ViralAmber.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("sample_video_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Bolt,
                                    contentDescription = null,
                                    tint = ViralAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "تجربة رابط جاهز",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ViralAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Live Download & Inspection Status
            item {
                AnimatedVisibility(visible = uiState.downloadState !is DownloadState.Idle) {
                    DownloadStatusCard(
                        downloadState = uiState.downloadState,
                        isAnalyzingFrames = uiState.isAnalyzingFrames,
                        onCancel = onCancelDownload
                    )
                }
            }

            // Technical Inspection Result + Sampled Frame Filmstrip
            val tech = uiState.technicalInfo
            if (tech != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.4f)),
                        colors = CardDefaults.cardColors(containerColor = StudioSurface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                StudioSectionHeader(
                                    title = "نتيجة فحص الفيديو التقني",
                                    subtitle = uiState.inspectionResult?.inspectionNotes ?: tech.fileName,
                                    icon = Icons.Rounded.CheckCircle,
                                    accent = EmeraldSuccess
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TelemetryChip(
                                    label = "الدقة الأصلية",
                                    value = "${tech.width}×${tech.height}",
                                    modifier = Modifier.weight(1f)
                                )
                                TelemetryChip(
                                    label = "المدة",
                                    value = formatMillisTimecode(tech.durationMs),
                                    accentColor = ViralAmber,
                                    modifier = Modifier.weight(1f)
                                )
                                TelemetryChip(
                                    label = "الحجم",
                                    value = formatBytesReadable(tech.fileSizeBytes),
                                    accentColor = EmeraldSuccess,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TelemetryChip(
                                    label = "معدل البت",
                                    value = if (tech.bitrateBps > 0) "${tech.bitrateBps / 1000} kbps" else "تلقائي",
                                    modifier = Modifier.weight(1f)
                                )
                                TelemetryChip(
                                    label = "الإطارات",
                                    value = "${tech.fps} FPS",
                                    modifier = Modifier.weight(1f)
                                )
                                TelemetryChip(
                                    label = "الصوت",
                                    value = if (tech.hasAudio) "موجود" else "صامت",
                                    accentColor = if (tech.hasAudio) EmeraldSuccess else TextSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (uiState.frameMetrics.isNotEmpty()) {
                                Text(
                                    text = "الإطارات المفحوصة ومؤشر طاقة المشهد (${uiState.frameMetrics.size} نقطة):",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(uiState.frameMetrics) { metric ->
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .width(96.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(StudioSurfaceElevated)
                                                .border(1.dp, StudioBorder, RoundedCornerShape(10.dp))
                                                .padding(4.dp)
                                        ) {
                                            AsyncImage(
                                                model = File(metric.thumbnailPath),
                                                contentDescription = "لقطة عند ${formatMillisTimecode(metric.timestampMs)}",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(54.dp)
                                                    .clip(RoundedCornerShape(7.dp))
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = formatMillisTimecode(metric.timestampMs),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = "طاقة ${(metric.combinedEnergy * 100).toInt()}%",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (metric.combinedEnergy >= 0.65f) ViralAmber else ElectricCyan
                                            )
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = onNavigateToEditor,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldSuccess,
                                    contentColor = ObsidianBg
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("go_to_editor_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MovieCreation,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "بدء المونتاج والقص وتحسين الجودة",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadStatusCard(
    downloadState: DownloadState,
    isAnalyzingFrames: Boolean,
    onCancel: () -> Unit
) {
    when (downloadState) {
        is DownloadState.Idle -> Unit
        is DownloadState.Inspecting -> {
            Card(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
                colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = ElectricCyan,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = downloadState.stageLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = downloadState.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "إلغاء",
                            tint = TextSecondary
                        )
                    }
                }
            }
        }
        is DownloadState.Downloading -> {
            Card(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f)),
                colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "جاري تنزيل الفيديو في ملفات التطبيق...",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "إيقاف التنزيل",
                                tint = CrimsonAlert
                            )
                        }
                    }
                    if (downloadState.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { downloadState.progressPercent / 100f },
                            color = ElectricCyan,
                            trackColor = StudioBorder,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            color = ElectricCyan,
                            trackColor = StudioBorder,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${formatBytesReadable(downloadState.bytesRead)} / ${formatBytesReadable(downloadState.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(
                            text = "${downloadState.progressPercent}% • ${formatBytesReadable(downloadState.speedBytesPerSec)}/s",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                }
            }
        }
        is DownloadState.Verifying -> {
            Card(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, ViralAmber.copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = ViralAmber,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "جاري التحقق من سلامة حاوية الفيديو واستخراج الإطارات...",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                }
            }
        }
        is DownloadState.Completed -> {
            if (isAnalyzingFrames) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f)),
                    colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = ElectricCyan,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "جاري تحليل طاقة المشاهد واكتشاف اللقطات المهمة...",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
        is DownloadState.Error -> {
            Card(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.55f)),
                colors = CardDefaults.cardColors(containerColor = CrimsonContainer.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = CrimsonAlert
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = downloadState.message,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        if (!downloadState.diagnosticDetail.isNullOrBlank()) {
                            Text(
                                text = downloadState.diagnosticDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}
