package com.andoni.convertidor.data

import android.net.Uri

data class VideoItem(
    val id: Long,
    val name: String,
    val path: String,
    val uri: Uri,
    val mimeType: String,
    val size: Long,
    val duration: Long,   // milisegundos
    val dateAdded: Long,
    val width: Int = 0,
    val height: Int = 0
)

val VideoItem.extension: String
    get() = name.substringAfterLast('.', "").lowercase()

val VideoItem.nameWithoutExtension: String
    get() = name.substringBeforeLast('.')

val VideoItem.formattedSize: String
    get() = when {
        size < 1_024 -> "$size B"
        size < 1_048_576 -> "${size / 1_024} KB"
        else -> String.format("%.1f MB", size / 1_048_576.0)
    }

val VideoItem.formattedDuration: String
    get() {
        val totalSec = duration / 1_000
        val h = totalSec / 3_600
        val m = (totalSec % 3_600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

val VideoItem.resolution: String
    get() = if (width > 0 && height > 0) "${width}x${height}" else "Desconocida"
