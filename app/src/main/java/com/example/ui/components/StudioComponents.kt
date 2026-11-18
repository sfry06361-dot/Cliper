package com.example.ui.components

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.model.OverlayPosition
import com.example.model.QualitySettings
import com.example.model.VideoDetailsSpec
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.OnAmberContainer
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ViralAmber
import java.io.File
import java.util.Locale

fun formatMillisTimecode(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val mins = (safe / 1000L) / 60L
    val secs = (safe / 1000L) % 60L
    val hundredths = (safe % 1000L) / 10L
    return String.format(Locale.US, "%02d:%02d.%02d", mins, secs, hundredths)
}

fun formatBytesReadable(bytes: Long): String {
    if (bytes <= 0L) return "غير محدد"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun StudioVideoPlayer(
    filePath: String,
    quality: QualitySettings? = null,
    details: VideoDetailsSpec? = null,
    seekRequest: Pair<Long, Int>? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var playerErrorMsg by remember(filePath) { mutableStateOf<String?>(null) }
    val player = remember(filePath) {
        val mediaUri = if (filePath.startsWith("http://", true) || filePath.startsWith("https://", true)) {
            Uri.parse(filePath)
        } else {
            Uri.fromFile(File(filePath))
        }
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playerErrorMsg = "تعذر تشغيل المعاينة المباشرة (${error.errorCodeName}) — يمكنك المتابعة في التصدير."
                }
            })
            setMediaItem(MediaItem.fromUri(mediaUri))
            prepare()
        }
    }

    LaunchedEffect(quality?.globalSpeed, quality?.removeAudio) {
        if (quality != null) {
            player.playbackParameters = PlaybackParameters(quality.globalSpeed.coerceIn(0.5f, 2.5f))
            player.volume = if (quality.removeAudio) 0f else 1f
        }
    }

    LaunchedEffect(seekRequest) {
        if (seekRequest != null) {
            player.seekTo(seekRequest.first.coerceAtLeast(0L))
            player.playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .border(1.dp, StudioBorder, RoundedCornerShape(18.dp))
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                if (view.player !== player) {
                    view.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Live preview of Top/Center/Bottom Hook Banner
        if (details != null && details.burnInEnabled && details.burnInText.isNotBlank()) {
            val alignment = when (details.overlayPosition) {
                OverlayPosition.TOP_HOOK -> Alignment.TopCenter
                OverlayPosition.CENTER_PUNCH -> Alignment.Center
                OverlayPosition.BOTTOM_CAPTION -> Alignment.BottomCenter
            }
            Box(
                modifier = Modifier
                    .align(alignment)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .background(ObsidianBg.copy(alpha = 0.84f), RoundedCornerShape(12.dp))
                    .border(1.2.dp, ElectricCyan.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = details.burnInText,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Live preview of Bottom Explanation Bar
        if (details != null && details.bottomExplanationEnabled && details.bottomExplanationText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .background(ObsidianBg.copy(alpha = 0.88f), RoundedCornerShape(10.dp))
                    .border(1.dp, ViralAmber.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = details.bottomExplanationText,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = ViralAmber,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Live preview of Channel Watermark
        if (details != null && details.watermarkEnabled && details.watermarkText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(ObsidianBg.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                    .border(1.dp, StudioBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = details.watermarkText,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = Color.White
                )
            }
        }

        // Live preview of Timecode Stamp
        if (quality?.showTimecodeStamp == true) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(ObsidianBg.copy(alpha = 0.78f), RoundedCornerShape(8.dp))
                    .border(1.dp, ElectricCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "TIMECODE",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = ElectricCyan
                )
            }
        }

        if (!playerErrorMsg.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
                    .background(ObsidianBg.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                    .border(1.dp, StudioBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = playerErrorMsg!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun StudioSectionHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector = Icons.Rounded.AutoAwesome,
    accent: Color = ElectricCyan
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(accent.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun TelemetryChip(
    label: String,
    value: String,
    accentColor: Color = ElectricCyan,
    modifier: Modifier = Modifier
) {
    Surface(
        color = StudioSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, StudioBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = accentColor
            )
        }
    }
}

@Composable
fun GeminiAnalysisStatusBanner(
    analysisState: com.example.ai.AiAnalysisState,
    onRetry: () -> Unit
) {
    when (analysisState) {
        is com.example.ai.AiAnalysisState.Idle -> Unit
        is com.example.ai.AiAnalysisState.Analyzing -> {
            Surface(
                color = CyanContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = ElectricCyan,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "جاري فحص الإطارات واختيار اللقطات المهمة عبر Gemini 3.8 Flash...",
                        style = MaterialTheme.typography.bodySmall,
                        color = com.example.ui.theme.OnCyanContainer
                    )
                }
            }
        }
        is com.example.ai.AiAnalysisState.Success -> {
            Surface(
                color = com.example.ui.theme.EmeraldContainer.copy(alpha = 0.72f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, com.example.ui.theme.EmeraldSuccess.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = com.example.ui.theme.EmeraldSuccess,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = analysisState.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = com.example.ui.theme.TextPrimary
                    )
                }
            }
        }
        is com.example.ai.AiAnalysisState.Error -> {
            Surface(
                color = com.example.ui.theme.CrimsonContainer.copy(alpha = 0.75f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, com.example.ui.theme.CrimsonAlert.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = com.example.ui.theme.CrimsonAlert,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = analysisState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = com.example.ui.theme.TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.TextButton(onClick = onRetry) {
                        Text("إعادة المحاولة", color = ElectricCyan)
                    }
                }
            }
        }
    }
}
