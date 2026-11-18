package com.example.data

import com.example.model.HighlightClip
import com.example.model.QualitySettings
import com.example.model.VideoDetailsSpec
import com.example.model.VideoTechnicalInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SavedProject(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val downloadedFilePath: String,
    val exportedFilePath: String?,
    val thumbnailPath: String?,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val clips: List<HighlightClip>,
    val quality: QualitySettings,
    val details: VideoDetailsSpec,
    val updatedAtMs: Long
)

class ProjectRepository(private val dao: ProjectDao) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val savedProjects: Flow<List<SavedProject>> = dao.observeAllProjects().map { list ->
        list.mapNotNull { entity ->
            runCatching {
                SavedProject(
                    id = entity.id,
                    title = entity.title,
                    sourceUrl = entity.sourceUrl,
                    downloadedFilePath = entity.downloadedFilePath,
                    exportedFilePath = entity.exportedFilePath,
                    thumbnailPath = entity.thumbnailPath,
                    durationMs = entity.durationMs,
                    width = entity.width,
                    height = entity.height,
                    clips = json.decodeFromString(entity.clipsJson),
                    quality = json.decodeFromString(entity.qualityJson),
                    details = json.decodeFromString(entity.detailsJson),
                    updatedAtMs = entity.updatedAtMs
                )
            }.getOrNull()
        }
    }

    suspend fun saveProject(
        id: String,
        title: String,
        sourceUrl: String,
        technicalInfo: VideoTechnicalInfo,
        exportedFilePath: String?,
        clips: List<HighlightClip>,
        quality: QualitySettings,
        details: VideoDetailsSpec
    ) {
        val entity = ProjectEntity(
            id = id,
            title = title,
            sourceUrl = sourceUrl,
            downloadedFilePath = technicalInfo.filePath,
            exportedFilePath = exportedFilePath,
            thumbnailPath = clips.firstOrNull { !it.thumbnailPath.isNullOrBlank() }?.thumbnailPath,
            durationMs = technicalInfo.durationMs,
            width = technicalInfo.width,
            height = technicalInfo.height,
            clipsJson = json.encodeToString(clips),
            qualityJson = json.encodeToString(quality),
            detailsJson = json.encodeToString(details),
            updatedAtMs = System.currentTimeMillis()
        )
        dao.upsertProject(entity)
    }

    suspend fun deleteProject(id: String) {
        dao.deleteProjectById(id)
    }
}
