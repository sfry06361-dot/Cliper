package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceUrl: String,
    val downloadedFilePath: String,
    val exportedFilePath: String?,
    val thumbnailPath: String?,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val clipsJson: String,
    val qualityJson: String,
    val detailsJson: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)
