package com.andoni.convertidor.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.andoni.convertidor.MainActivity
import com.linkedin.android.litr.MediaTransformer
import com.linkedin.android.litr.TransformationListener
import com.linkedin.android.litr.TransformationOptions
import com.linkedin.android.litr.analytics.TrackTransformationInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

class ConversionService : Service() {

    companion object {
        const val EXTRA_INPUT_URI   = "input_uri"
        const val EXTRA_INPUT_PATH  = "input_path"  // mantenido por compatibilidad
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val EXTRA_FORMAT      = "format"
        const val EXTRA_IS_REPAIR   = "is_repair"
        const val EXTRA_DURATION_MS = "duration_ms"

        const val CHANNEL_ID          = "conversion_channel"
        const val NOTIF_PROGRESS_ID   = 1001
        const val NOTIF_COMPLETED_ID  = 1002
        const val NOTIF_ERROR_ID      = 1003

        private val _state = MutableStateFlow(ConversionState())
        val state = _state.asStateFlow()

        fun resetState() { _state.value = ConversionState() }
    }

    data class ConversionState(
        val isConverting: Boolean = false,
        val progress: Int = 0,
        val outputPath: String = "",
        val error: String? = null,
        val isCompleted: Boolean = false
    )

    private var transformer: MediaTransformer? = null
    private var currentRequestId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        transformer = MediaTransformer(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentRequestId?.let { transformer?.cancel(it) }
        transformer?.release()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Aceptar tanto EXTRA_INPUT_URI (content://) como EXTRA_INPUT_PATH (compatibilidad)
        val inputUriStr = intent?.getStringExtra(EXTRA_INPUT_URI)
            ?: intent?.getStringExtra(EXTRA_INPUT_PATH)
        val outputPath  = intent?.getStringExtra(EXTRA_OUTPUT_PATH) ?: return stopAndReturn()
        val format      = intent?.getStringExtra(EXTRA_FORMAT)      ?: "mp4"

        if (inputUriStr.isNullOrBlank()) {
            _state.value = ConversionState(error = "No se puede acceder al archivo de video")
            return stopAndReturn()
        }

        currentRequestId?.let { transformer?.cancel(it) }

        startForeground(NOTIF_PROGRESS_ID, buildProgressNotification(0))
        _state.value = ConversionState(isConverting = true, progress = 0)

        // Si es URI de contenido (content://) la usamos directamente.
        // Si es ruta de archivo la convertimos a file://
        val inputUri = if (inputUriStr.startsWith("content://") || inputUriStr.startsWith("file://")) {
            Uri.parse(inputUriStr)
        } else {
            Uri.fromFile(File(inputUriStr))
        }
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()
        val requestId  = UUID.randomUUID().toString()
        currentRequestId = requestId

        transformer?.transform(
            requestId,
            inputUri,
            outputPath,
            null,   // targetVideoFormat: null = mismo formato que el origen
            null,   // targetAudioFormat: null = mismo formato que el origen
            object : TransformationListener {
                override fun onStarted(id: String) {
                    _state.value = ConversionState(isConverting = true, progress = 1)
                }
                override fun onProgress(id: String, progress: Float) {
                    val pct = (progress * 100).toInt().coerceIn(1, 99)
                    _state.value = _state.value.copy(progress = pct)
                    updateProgressNotification(pct)
                }
                override fun onCompleted(id: String, trackTransformationInfos: List<TrackTransformationInfo>?) {
                    registerFileInMediaStore(outputPath, format)
                    _state.value = ConversionState(
                        isConverting = false,
                        progress     = 100,
                        outputPath   = outputPath,
                        isCompleted  = true
                    )
                    showCompletionNotification(outputPath)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                override fun onCancelled(id: String, trackTransformationInfos: List<TrackTransformationInfo>?) {
                    _state.value = ConversionState(error = "Conversión cancelada")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                override fun onError(id: String, cause: Throwable?, trackTransformationInfos: List<TrackTransformationInfo>?) {
                    _state.value = ConversionState(error = cause?.message ?: "Error desconocido durante la conversión")
                    showErrorNotification()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            },
            TransformationOptions.Builder().build()
        )

        return START_NOT_STICKY
    }

    // ── Notificaciones ────────────────────────────────────────────────────────

    private fun buildProgressNotification(progress: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isIndeterminate = progress == 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Convirtiendo video…")
            .setContentText(if (isIndeterminate) "Iniciando…" else "$progress%")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setProgress(100, progress, isIndeterminate)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateProgressNotification(progress: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_PROGRESS_ID, buildProgressNotification(progress))
    }

    private fun showCompletionNotification(outputPath: String) {
        val fileName = outputPath.substringAfterLast('/')
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_COMPLETED_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("✓ Conversión completada")
                .setContentText(fileName)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun showErrorNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_ERROR_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Error en la conversión")
                .setContentText("Revisa el video e inténtalo de nuevo")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun registerFileInMediaStore(filePath: String, format: String) {
        val file = File(filePath)
        if (!file.exists()) return
        val mimeType = when (format.lowercase()) {
            "webm" -> "video/webm"
            "3gp"  -> "video/3gpp"
            else   -> "video/mp4"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoConvert")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), values
            ) ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                val upd = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(uri, upd, null, null)
                file.delete()
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
            }
        } else {
            val dest = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "VideoConvert/${file.name}"
            )
            dest.parentFile?.mkdirs()
            file.copyTo(dest, overwrite = true)
            file.delete()
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), null, null)
        }
    }

    private fun stopAndReturn(): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}
