package com.andoni.convertidor.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoRepository(private val context: Context) {

    suspend fun getAllVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoItem>()

        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT
            )

            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder
            )?.use { cursor ->
                val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val mimeCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val sizeCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durCol      = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val widthCol    = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id  = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                    )
                    videos.add(
                        VideoItem(
                            id        = id,
                            name      = cursor.getString(nameCol) ?: "video_$id",
                            path      = cursor.getString(dataCol) ?: "",
                            uri       = uri,
                            mimeType  = cursor.getString(mimeCol) ?: "video/mp4",
                            size      = cursor.getLong(sizeCol),
                            duration  = cursor.getLong(durCol),
                            dateAdded = cursor.getLong(dateCol),
                            width     = cursor.getInt(widthCol),
                            height    = cursor.getInt(heightCol)
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.w("VideoRepository", "Sin permiso para leer videos", e)
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error al leer videos", e)
        }
        videos
    }

    suspend fun getVideoById(id: Long): VideoItem? =
        getAllVideos().find { it.id == id }
}
