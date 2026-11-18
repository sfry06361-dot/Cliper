package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.model.ExportState
import com.example.ui.StudioUiState
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.StudioVideoPlayer
import com.example.ui.components.TelemetryChip
import com.example.ui.components.formatBytesReadable
import com.example.ui.components.formatMillisTimecode
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ViralAmber
import java.io.File
import java.util.Locale

@Composable
fun ExportProduceScreen(
    uiState: StudioUiState,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onSaveToGallery: () -> Unit,
    onGoToLinkTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tech = uiState.technicalInfo

    if (tech == null || (!tech.isRemoteHlsStream && !File(tech.filePath).exists())) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, StudioBorder),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                modifier = Modifier.widthIn(max = 460.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Rounded.RocketLaunch, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.size(48.dp))
                    Text("لا يوجد مشروع جاهز للتصدير", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text(
                        "حمّل الفيديو وافحصه ثم اختر اللقطات المهمة قبل تنفيذ التصدير النهائي.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Button(
                        onClick = onGoToLinkTab,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = ObsidianBg)
                    ) {
                        Text("البدء من فحص الرابط", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Production Summary Card
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
                            title = "ملخص مواصفات المونتاج النهائي",
                            subtitle = "محرك المعالجة الفعلي Jetpack Media3 Transformer",
                            icon = Icons.Rounded.RocketLaunch,
                            accent = ElectricCyan
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TelemetryChip(
                                label = "اللقطات المدمجة",
                                value = "${uiState.includedClips.size} لقطات",
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryChip(
                                label = "المدة الصافية",
                                value = formatMillisTimecode(uiState.estimatedOutputDurationMs),
                                accentColor = ViralAmber,
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryChip(
                                label = "السرعة العامة",
                                value = String.format(Locale.US, "%.2fx", uiState.qualitySettings.globalSpeed),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TelemetryChip(
                                label = "الدقة المستهدفة",
                                value = uiState.qualitySettings.targetResolution.labelAr,
                                accentColor = EmeraldSuccess,
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryChip(
                                label = "معدل البت",
                                value = uiState.qualitySettings.bitrateTier.labelAr,
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryChip(
                                label = "الميزات",
                                value = "${uiState.activeFeatures.size} / 40",
                                accentColor = ViralAmber,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (uiState.detailsSpec.burnInEnabled && uiState.detailsSpec.burnInText.isNotBlank()) {
                            Text(
                                text = "النص المطبوع على الفيديو: \"${uiState.detailsSpec.burnInText}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = ElectricCyan
                            )
                        }
                        if (uiState.detailsSpec.bottomExplanationEnabled && uiState.detailsSpec.bottomExplanationText.isNotBlank()) {
                            Text(
                                text = "شريط الشرح السفلي: \"${uiState.detailsSpec.bottomExplanationText}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = ViralAmber
                            )
                        }

                        val isExporting = uiState.exportState is ExportState.Exporting
                        if (!isExporting) {
                            Button(
                                onClick = onStartExport,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldSuccess,
                                    contentColor = ObsidianBg
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("export_montage_button")
                            ) {
                                Icon(Icons.Rounded.RocketLaunch, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "إنتاج وتصدير الفيديو بجودة عالية",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }

            // Export Progress / Result
            item {
                when (val exp = uiState.exportState) {
                    is ExportState.Idle -> Unit
                    is ExportState.Exporting -> {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                            colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            color = ElectricCyan,
                                            strokeWidth = 3.dp,
                                            modifier = Modifier.size(26.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = exp.stageLabel,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = TextPrimary
                                        )
                                    }
                                    Text(
                                        text = "${exp.progressPercent}%",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = ElectricCyan
                                    )
                                }

                                LinearProgressIndicator(
                                    progress = { exp.progressPercent / 100f },
                                    color = ElectricCyan,
                                    trackColor = StudioBorder,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )

                                OutlinedButton(
                                    onClick = onCancelExport,
                                    border = BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.6f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Rounded.Cancel, contentDescription = null, tint = CrimsonAlert)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("إلغاء التصدير", color = CrimsonAlert)
                                }
                            }
                        }
                    }
                    is ExportState.Error -> {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, CrimsonAlert),
                            colors = CardDefaults.cardColors(containerColor = CrimsonContainer.copy(alpha = 0.7f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = CrimsonAlert)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(exp.message, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            }
                        }
                    }
                    is ExportState.Completed -> {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.65f)),
                            colors = CardDefaults.cardColors(containerColor = StudioSurface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                StudioSectionHeader(
                                    title = "تم إنتاج المونتاج بنجاح!",
                                    subtitle = "معاينة الملف النهائي الجاهز للنشر",
                                    icon = Icons.Rounded.CheckCircle,
                                    accent = EmeraldSuccess
                                )

                                StudioVideoPlayer(filePath = exp.outputPath)

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    TelemetryChip(
                                        label = "الدقة النهائية",
                                        value = "${exp.width}×${exp.height}",
                                        accentColor = EmeraldSuccess,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TelemetryChip(
                                        label = "المدة",
                                        value = formatMillisTimecode(exp.durationMs),
                                        accentColor = ViralAmber,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TelemetryChip(
                                        label = "الحجم",
                                        value = formatBytesReadable(exp.fileSizeBytes),
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = onSaveToGallery,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ElectricCyan,
                                            contentColor = ObsidianBg
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("save_gallery_button")
                                    ) {
                                        Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("حفظ بالاستوديو", fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val file = File(exp.outputPath)
                                            if (file.exists()) {
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    file
                                                )
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "video/mp4"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    putExtra(Intent.EXTRA_SUBJECT, uiState.detailsSpec.viralTitle)
                                                    putExtra(
                                                        Intent.EXTRA_TEXT,
                                                        "${uiState.detailsSpec.viralTitle}\n\n${uiState.detailsSpec.description}\n\n${uiState.detailsSpec.hashtags}"
                                                    )
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "مشاركة المونتاج"))
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, StudioBorder),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("share_montage_button")
                                    ) {
                                        Icon(Icons.Rounded.Share, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("مشاركة الفيديو", color = TextPrimary)
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        val fullDetails = buildString {
                                            appendLine(uiState.detailsSpec.viralTitle)
                                            appendLine()
                                            appendLine(uiState.detailsSpec.description)
                                            if (uiState.detailsSpec.chapterTimestamps.isNotBlank()) {
                                                appendLine()
                                                appendLine("الفصول:")
                                                appendLine(uiState.detailsSpec.chapterTimestamps)
                                            }
                                            if (uiState.detailsSpec.voiceoverScript.isNotBlank()) {
                                                appendLine()
                                                appendLine("سيناريو الشرح:")
                                                appendLine(uiState.detailsSpec.voiceoverScript)
                                            }
                                            appendLine()
                                            append(uiState.detailsSpec.hashtags)
                                        }
                                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cb.setPrimaryClip(ClipData.newPlainText("ClipCraft Details", fullDetails))
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, ViralAmber.copy(alpha = 0.6f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = ViralAmber, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("نسخ العنوان والتفاصيل والهاشتاجات", color = ViralAmber)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
