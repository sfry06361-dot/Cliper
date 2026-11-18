package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.SavedProject
import com.example.downloader.StoredMediaFile
import com.example.ui.StudioUiState
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.formatBytesReadable
import com.example.ui.components.formatMillisTimecode
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ViralAmber
import java.io.File

@Composable
fun ProjectsLibraryScreen(
    uiState: StudioUiState,
    onOpenProject: (SavedProject) -> Unit,
    onDeleteProject: (String) -> Unit,
    onDeleteStoredFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                StudioSectionHeader(
                    title = "المشاريع المحفوظة",
                    subtitle = "جميع الفيديوهات التي تم فحصها وتحريرها",
                    icon = Icons.Rounded.FolderOpen
                )
            }

            if (uiState.savedProjects.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, StudioBorder),
                        colors = CardDefaults.cardColors(containerColor = StudioSurface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Rounded.Movie, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(36.dp))
                            Text("لا توجد مشاريع محفوظة بعد", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            Text(
                                "عند فحص أي رابط وتنزيله سيتم حفظ المشروع هنا تلقائياً.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            } else {
                items(uiState.savedProjects, key = { it.id }) { project ->
                    SavedProjectCard(
                        project = project,
                        onOpen = { onOpenProject(project) },
                        onDelete = { onDeleteProject(project.id) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
                StudioSectionHeader(
                    title = "ملفات التنزيل والمونتاج داخل التطبيق",
                    subtitle = "ملفات التخزين الداخلي (downloads & exports)",
                    icon = Icons.Rounded.Storage,
                    accent = ViralAmber
                )
            }

            if (uiState.storedFiles.isEmpty()) {
                item {
                    Text(
                        text = "لا توجد ملفات فيديو محملة أو مصدرة داخل مجلدات التطبيق حالياً.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            } else {
                items(uiState.storedFiles, key = { it.filePath }) { storedFile ->
                    StoredFileCard(
                        file = storedFile,
                        onDelete = { onDeleteStoredFile(storedFile.filePath) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedProjectCard(
    project: SavedProject,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, StudioBorder),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!project.thumbnailPath.isNullOrBlank() && File(project.thumbnailPath).exists()) {
                AsyncImage(
                    model = File(project.thumbnailPath),
                    contentDescription = project.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 86.dp, height = 56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, StudioBorder, RoundedCornerShape(10.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = project.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${project.width}×${project.height} • ${formatMillisTimecode(project.durationMs)} • ${project.clips.count { it.isIncluded }} لقطات",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                val hasExport = !project.exportedFilePath.isNullOrBlank() && File(project.exportedFilePath).exists()
                Surface(
                    color = if (hasExport) EmeraldContainer else CyanContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (hasExport) "تم التصدير" else "جاهز للتحرير",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasExport) EmeraldSuccess else ElectricCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            OutlinedButton(
                onClick = onOpen,
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Rounded.PlayCircleOutline, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("فتح", color = ElectricCyan)
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "حذف المشروع", tint = CrimsonAlert)
            }
        }
    }
}

@Composable
private fun StoredFileCard(
    file: StoredMediaFile,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, StudioBorder),
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = if (file.isExport) EmeraldContainer else CyanContainer,
                        shape = RoundedCornerShape(5.dp)
                    ) {
                        Text(
                            text = if (file.isExport) "تصدير" else "تنزيل",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (file.isExport) EmeraldSuccess else ElectricCyan,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = file.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = formatBytesReadable(file.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "حذف الملف", tint = CrimsonAlert)
            }
        }
    }
}
