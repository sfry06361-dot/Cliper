package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.model.BitrateTier
import com.example.model.HighlightClip
import com.example.model.OverlayPosition
import com.example.model.PacingMode
import com.example.model.QualityPreset
import com.example.model.QualitySettings
import com.example.model.TargetResolution
import com.example.model.VideoDetailsSpec
import com.example.ui.StudioUiState
import com.example.ui.components.GeminiAnalysisStatusBanner
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.StudioVideoPlayer
import com.example.ui.components.TelemetryChip
import com.example.ui.components.formatMillisTimecode
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.JetBrainsMonoFontFamily
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
fun MontageEditorScreen(
    uiState: StudioUiState,
    onSeekPreview: (Long) -> Unit,
    onReAnalyzeHighlights: () -> Unit,
    onToggleClip: (String) -> Unit,
    onUpdateClipRange: (String, Long, Long) -> Unit,
    onUpdateClipSpeed: (String, Float) -> Unit,
    onMoveClipUp: (String) -> Unit,
    onMoveClipDown: (String) -> Unit,
    onAddCustomClip: () -> Unit,
    onRemoveClip: (String) -> Unit,
    onApplyPacingMode: (PacingMode) -> Unit,
    onApplyQualityPreset: (QualityPreset) -> Unit,
    onUpdateQuality: ((QualitySettings) -> QualitySettings) -> Unit,
    onUpdateDetails: ((VideoDetailsSpec) -> VideoDetailsSpec) -> Unit,
    onGoToLinkTab: () -> Unit,
    onGoToExportTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tech = uiState.technicalInfo
    if (tech == null || !File(tech.filePath).exists()) {
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
                    Icon(
                        imageVector = Icons.Rounded.ContentCut,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "لا يوجد فيديو محمل في الاستوديو بعد",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "انسخ رابط أي فيديو أو اختر مقطعاً من جهازك ليقوم المحرك بفحصه وتجهيز المونتاج.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Button(
                        onClick = onGoToLinkTab,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = ObsidianBg)
                    ) {
                        Text("العودة إلى صفحة الرابط والفحص", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    var selectedSubTab by rememberSaveable { mutableIntStateOf(0) }
    val subTabs = listOf(
        "اللقطات المهمة" to Icons.Rounded.ContentCut,
        "الوقت والسرعة" to Icons.Rounded.Speed,
        "تحسين الجودة" to Icons.Rounded.HighQuality,
        "التفاصيل والنص" to Icons.Rounded.Description
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
        ) {
            // Live Player Preview + Summary Strip
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StudioVideoPlayer(
                    filePath = tech.filePath,
                    quality = uiState.qualitySettings,
                    details = uiState.detailsSpec,
                    seekRequest = uiState.seekRequestMs
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TelemetryChip(
                        label = "اللقطات المختارة",
                        value = "${uiState.includedClips.size} من ${uiState.clips.size}",
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryChip(
                        label = "المدة بعد المونتاج",
                        value = formatMillisTimecode(uiState.estimatedOutputDurationMs),
                        accentColor = ViralAmber,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onGoToExportTab,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess, contentColor = ObsidianBg),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("quick_produce_button")
                    ) {
                        Icon(Icons.Rounded.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تصدير", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Editor Sub-tabs
            TabRow(
                selectedTabIndex = selectedSubTab,
                containerColor = StudioSurface,
                contentColor = ElectricCyan,
                indicator = { tabPositions ->
                    SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedSubTab]),
                        color = ElectricCyan
                    )
                }
            ) {
                subTabs.forEachIndexed { index, (label, icon) ->
                    Tab(
                        selected = selectedSubTab == index,
                        onClick = { selectedSubTab = index },
                        text = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        selectedContentColor = ElectricCyan,
                        unselectedContentColor = TextSecondary
                    )
                }
            }

            // Sub-tab Content
            when (selectedSubTab) {
                0 -> HighlightsEditorTab(
                    uiState = uiState,
                    durationMs = tech.durationMs,
                    onSeekPreview = onSeekPreview,
                    onReAnalyzeHighlights = onReAnalyzeHighlights,
                    onToggleClip = onToggleClip,
                    onUpdateClipRange = onUpdateClipRange,
                    onUpdateClipSpeed = onUpdateClipSpeed,
                    onMoveClipUp = onMoveClipUp,
                    onMoveClipDown = onMoveClipDown,
                    onAddCustomClip = onAddCustomClip,
                    onRemoveClip = onRemoveClip
                )
                1 -> TimingSpeedEditorTab(
                    uiState = uiState,
                    originalDurationMs = tech.durationMs,
                    onApplyPacingMode = onApplyPacingMode,
                    onUpdateQuality = onUpdateQuality
                )
                2 -> QualityEnhancementTab(
                    quality = uiState.qualitySettings,
                    onApplyPreset = onApplyQualityPreset,
                    onUpdateQuality = onUpdateQuality
                )
                3 -> DetailsAndOverlayTab(
                    details = uiState.detailsSpec,
                    onUpdateDetails = onUpdateDetails
                )
            }
        }
    }
}

@Composable
private fun HighlightsEditorTab(
    uiState: StudioUiState,
    durationMs: Long,
    onSeekPreview: (Long) -> Unit,
    onReAnalyzeHighlights: () -> Unit,
    onToggleClip: (String) -> Unit,
    onUpdateClipRange: (String, Long, Long) -> Unit,
    onUpdateClipSpeed: (String, Float) -> Unit,
    onMoveClipUp: (String) -> Unit,
    onMoveClipDown: (String) -> Unit,
    onAddCustomClip: () -> Unit,
    onRemoveClip: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            GeminiAnalysisStatusBanner(
                analysisState = uiState.aiAnalysisState,
                onRetry = onReAnalyzeHighlights
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onReAnalyzeHighlights,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إعادة الفحص التلقائي", color = ElectricCyan)
                }

                OutlinedButton(
                    onClick = onAddCustomClip,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إضافة لقطة يدوياً", color = TextPrimary)
                }
            }
        }

        itemsIndexed(uiState.clips, key = { _, clip -> clip.id }) { index, clip ->
            HighlightClipCard(
                index = index,
                totalCount = uiState.clips.size,
                clip = clip,
                maxDurationMs = durationMs,
                onSeekPreview = { onSeekPreview(clip.startMs) },
                onToggleIncluded = { onToggleClip(clip.id) },
                onUpdateRange = { s, e -> onUpdateClipRange(clip.id, s, e) },
                onUpdateSpeed = { sp -> onUpdateClipSpeed(clip.id, sp) },
                onMoveUp = { onMoveClipUp(clip.id) },
                onMoveDown = { onMoveClipDown(clip.id) },
                onRemove = { onRemoveClip(clip.id) }
            )
        }
    }
}

@Composable
private fun HighlightClipCard(
    index: Int,
    totalCount: Int,
    clip: HighlightClip,
    maxDurationMs: Long,
    onSeekPreview: () -> Unit,
    onToggleIncluded: () -> Unit,
    onUpdateRange: (Long, Long) -> Unit,
    onUpdateSpeed: (Float) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    val borderColor = when {
        !clip.isIncluded -> StudioBorder.copy(alpha = 0.5f)
        clip.isHook -> ViralAmber.copy(alpha = 0.7f)
        else -> ElectricCyan.copy(alpha = 0.5f)
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(
            containerColor = if (clip.isIncluded) StudioSurface else StudioSurface.copy(alpha = 0.55f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!clip.thumbnailPath.isNullOrBlank()) {
                    AsyncImage(
                        model = File(clip.thumbnailPath),
                        contentDescription = clip.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 84.dp, height = 52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, StudioBorder, RoundedCornerShape(10.dp))
                            .clickable(onClick = onSeekPreview)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = if (clip.isHook) AmberContainer else CyanContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (clip.isHook) "HOOK #${index + 1}" else "#${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (clip.isHook) ViralAmber else ElectricCyan,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "جاذبية ${clip.viralityScore}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldSuccess
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = clip.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = clip.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Switch(
                    checked = clip.isIncluded,
                    onCheckedChange = { onToggleIncluded() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ObsidianBg,
                        checkedTrackColor = ElectricCyan
                    )
                )
            }

            // Precision Time Range Trim Slider
            val safeMax = maxDurationMs.coerceAtLeast(1500L).toFloat()
            val sliderStart = clip.startMs.toFloat().coerceIn(0f, safeMax - 500f)
            val sliderEnd = clip.endMs.toFloat().coerceIn(sliderStart + 500f, safeMax)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "من ${formatMillisTimecode(clip.startMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
                Text(
                    text = "المدة الصافية: ${formatMillisTimecode(clip.effectiveDurationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ViralAmber
                )
                Text(
                    text = "إلى ${formatMillisTimecode(clip.endMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }

            RangeSlider(
                value = sliderStart..sliderEnd,
                onValueChange = { range ->
                    onUpdateRange(range.start.toLong(), range.endInclusive.toLong())
                },
                valueRange = 0f..safeMax,
                colors = SliderDefaults.colors(
                    thumbColor = ElectricCyan,
                    activeTrackColor = ElectricCyan,
                    inactiveTrackColor = StudioBorder
                )
            )

            // Clip Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onSeekPreview) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "معاينة اللقطة", tint = EmeraldSuccess)
                    }
                    IconButton(onClick = onMoveUp, enabled = index > 0) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = "تقديم اللقطة",
                            tint = if (index > 0) TextPrimary else TextSecondary.copy(alpha = 0.35f)
                        )
                    }
                    IconButton(onClick = onMoveDown, enabled = index < totalCount - 1) {
                        Icon(
                            Icons.Rounded.ArrowDownward,
                            contentDescription = "تأخير اللقطة",
                            tint = if (index < totalCount - 1) TextPrimary else TextSecondary.copy(alpha = 0.35f)
                        )
                    }
                    if (totalCount > 1) {
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "حذف اللقطة", tint = CrimsonAlert)
                        }
                    }
                }

                // Per-clip speed toggle pill
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(1.0f, 1.15f, 1.35f, 1.5f).forEach { speedOpt ->
                        val active = kotlin.math.abs(clip.speedMultiplier - speedOpt) < 0.04f
                        Surface(
                            color = if (active) CyanContainer else StudioSurfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (active) ElectricCyan else StudioBorder),
                            modifier = Modifier
                                .clickable { onUpdateSpeed(speedOpt) }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "${speedOpt}x",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) ElectricCyan else TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimingSpeedEditorTab(
    uiState: StudioUiState,
    originalDurationMs: Long,
    onApplyPacingMode: (PacingMode) -> Unit,
    onUpdateQuality: ((QualitySettings) -> QualitySettings) -> Unit
) {
    val quality = uiState.qualitySettings
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, StudioBorder),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StudioSectionHeader(
                        title = "تعديل الوقت وإيقاع المشاهد",
                        subtitle = "اضبط طول المونتاج النهائي بما يناسب الشورتس والرييلز",
                        icon = Icons.Rounded.Speed
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryChip(
                            label = "الوقت الأصلي",
                            value = formatMillisTimecode(originalDurationMs),
                            accentColor = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryChip(
                            label = "وقت المونتاج النهائي",
                            value = formatMillisTimecode(uiState.estimatedOutputDurationMs),
                            accentColor = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "نمط المدة المستهدفة:",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PacingMode.entries.forEach { mode ->
                            FilterChip(
                                selected = quality.pacingMode == mode,
                                onClick = { onApplyPacingMode(mode) },
                                label = { Text(mode.labelAr) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanContainer,
                                    selectedLabelColor = ElectricCyan
                                )
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, StudioBorder),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("السرعة العامة للفيديو", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            text = String.format(Locale.US, "%.2fx", quality.globalSpeed),
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = ViralAmber
                        )
                    }

                    Slider(
                        value = quality.globalSpeed,
                        onValueChange = { v -> onUpdateQuality { it.copy(globalSpeed = v) } },
                        valueRange = 0.5f..2.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = ViralAmber,
                            activeTrackColor = ViralAmber
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0.75f to "بطيء", 1.0f to "طبيعي", 1.15f to "رييلز", 1.25f to "سريع", 1.5f to "مكثف")
                            .forEach { (speed, label) ->
                                val selected = kotlin.math.abs(quality.globalSpeed - speed) < 0.04f
                                OutlinedButton(
                                    onClick = { onUpdateQuality { it.copy(globalSpeed = speed) } },
                                    border = BorderStroke(1.dp, if (selected) ViralAmber else StudioBorder),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "${speed}x\n$label",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) ViralAmber else TextSecondary
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
private fun QualityEnhancementTab(
    quality: QualitySettings,
    onApplyPreset: (QualityPreset) -> Unit,
    onUpdateQuality: ((QualitySettings) -> QualitySettings) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, StudioBorder),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StudioSectionHeader(
                        title = "ترقية الدقة وجودة البث",
                        subtitle = "رفع الدقة عبر OpenGL Shader ومضاعفة معدل البت",
                        icon = Icons.Rounded.HighQuality,
                        accent = ViralAmber
                    )

                    Text("الدقة المستهدفة:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TargetResolution.entries.forEach { res ->
                            FilterChip(
                                selected = quality.targetResolution == res,
                                onClick = { onUpdateQuality { it.copy(targetResolution = res) } },
                                label = { Text(res.labelAr) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AmberContainer,
                                    selectedLabelColor = ViralAmber
                                )
                            )
                        }
                    }

                    Text("جودة الترميز (Bitrate):", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BitrateTier.entries.forEach { tier ->
                            FilterChip(
                                selected = quality.bitrateTier == tier,
                                onClick = { onUpdateQuality { it.copy(bitrateTier = tier) } },
                                label = { Text(tier.labelAr) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanContainer,
                                    selectedLabelColor = ElectricCyan
                                )
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, StudioBorder),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StudioSectionHeader(
                        title = "معالجة الألوان والحدة والوضوح",
                        subtitle = "تطبق مباشرةً أثناء التصدير عبر Media3 Effects",
                        icon = Icons.Rounded.Tune
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QualityPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = quality.preset == preset,
                                onClick = { onApplyPreset(preset) },
                                label = { Text(preset.labelAr) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanContainer,
                                    selectedLabelColor = ElectricCyan
                                )
                            )
                        }
                    }

                    QualitySliderRow(
                        label = "التباين والوضوح (Contrast)",
                        value = quality.contrast,
                        valueRange = -0.4f..0.6f,
                        valueDisplay = "${(quality.contrast * 100).toInt()}%",
                        onValueChange = { v -> onUpdateQuality { it.copy(contrast = v) } }
                    )

                    QualitySliderRow(
                        label = "السطوع (Brightness)",
                        value = quality.brightness,
                        valueRange = -0.35f..0.35f,
                        valueDisplay = "${(quality.brightness * 100).toInt()}%",
                        onValueChange = { v -> onUpdateQuality { it.copy(brightness = v) } }
                    )

                    QualitySliderRow(
                        label = "تشبع الألوان (Saturation)",
                        value = quality.saturation,
                        valueRange = -40f..60f,
                        valueDisplay = "${quality.saturation.toInt()}",
                        onValueChange = { v -> onUpdateQuality { it.copy(saturation = v) } }
                    )

                    QualitySliderRow(
                        label = "الدفء اللوني (Warmth)",
                        value = quality.warmth,
                        valueRange = -0.2f..0.25f,
                        valueDisplay = "${(quality.warmth * 100).toInt()}%",
                        onValueChange = { v -> onUpdateQuality { it.copy(warmth = v) } }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("كتم الصوت الأصلي", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            Text("تصدير الفيديو كـ B-Roll صامت بدون مسار صوت", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        Switch(
                            checked = quality.removeAudio,
                            onCheckedChange = { checked -> onUpdateQuality { it.copy(removeAudio = checked) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = ElectricCyan)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QualitySliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDisplay: String,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(
                valueDisplay,
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
        )
    }
}

@Composable
private fun DetailsAndOverlayTab(
    details: VideoDetailsSpec,
    onUpdateDetails: ((VideoDetailsSpec) -> VideoDetailsSpec) -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, StudioBorder),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StudioSectionHeader(
                        title = "كتابة النص على الفيديو (Burn-In)",
                        subtitle = "يُطبع مباشرةً على إطارات الفيديو بخط واضح أثناء التصدير",
                        icon = Icons.Rounded.AutoAwesome,
                        accent = ElectricCyan
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("تفعيل النص على الفيديو", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Switch(
                            checked = details.burnInEnabled,
                            onCheckedChange = { enabled -> onUpdateDetails { it.copy(burnInEnabled = enabled) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = ElectricCyan)
                        )
                    }

                    OutlinedTextField(
                        value = details.burnInText,
                        onValueChange = { txt -> onUpdateDetails { it.copy(burnInText = txt) } },
                        label = { Text("النص الظاهر على الفيديو") },
                        enabled = details.burnInEnabled,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = StudioBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("burn_in_text_input")
                    )

                    Text("موضع النص على الشاشة:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OverlayPosition.entries.forEach { pos ->
                            FilterChip(
                                selected = details.overlayPosition == pos,
                                onClick = { onUpdateDetails { it.copy(overlayPosition = pos) } },
                                label = { Text(pos.labelAr) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanContainer,
                                    selectedLabelColor = ElectricCyan
                                )
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, StudioBorder),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StudioSectionHeader(
                        title = "كتابة التفاصيل والعنوان والنشر هنا",
                        subtitle = "جهّز عنوان الجذب والوصف والهاشتاجات وانسخها بضغطة واحدة",
                        icon = Icons.Rounded.Description,
                        accent = ViralAmber
                    )

                    OutlinedTextField(
                        value = details.viralTitle,
                        onValueChange = { txt -> onUpdateDetails { it.copy(viralTitle = txt) } },
                        label = { Text("عنوان الفيديو لجذب المشاهدات") },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ViralAmber,
                            unfocusedBorderColor = StudioBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("viral_title_input")
                    )

                    OutlinedTextField(
                        value = details.description,
                        onValueChange = { txt -> onUpdateDetails { it.copy(description = txt) } },
                        label = { Text("التفاصيل والوصف الكامل للفيديو") },
                        minLines = 4,
                        maxLines = 8,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ViralAmber,
                            unfocusedBorderColor = StudioBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("video_details_input")
                    )

                    OutlinedTextField(
                        value = details.hashtags,
                        onValueChange = { txt -> onUpdateDetails { it.copy(hashtags = txt) } },
                        label = { Text("الهاشتاجات") },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ViralAmber,
                            unfocusedBorderColor = StudioBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            val fullText = buildString {
                                appendLine(details.viralTitle)
                                appendLine()
                                appendLine(details.description)
                                appendLine()
                                append(details.hashtags)
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("ClipCraft Video Details", fullText))
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ViralAmber,
                            contentColor = ObsidianBg
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("copy_details_button")
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("نسخ العنوان والتفاصيل والهاشتاجات", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
