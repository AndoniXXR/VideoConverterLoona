package com.andoni.convertidor.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.andoni.convertidor.MainActivity
import com.linkedin.android.litr.MediaTransformer
import com.linkedin.android.litr.TransformationListener
import com.linkedin.android.litr.TransformationOptions
import com.linkedin.android.litr.analytics.TrackTransformationInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

class ConversionService : Service() {

    companion object {
        const val EXTRA_INPUT_URI    = "input_uri"
        const val EXTRA_INPUT_PATH   = "input_path"  // mantenido por compatibilidad
        const val EXTRA_OUTPUT_PATH  = "output_path"
        const val EXTRA_FORMAT       = "format"
        const val EXTRA_IS_REPAIR    = "is_repair"
        const val EXTRA_DURATION_MS  = "duration_ms"
        const val EXTRA_TARGET_WIDTH  = "target_width"
        const val EXTRA_TARGET_HEIGHT = "target_height"
        const val EXTRA_TARGET_FPS    = "target_fps"

        // GIF-specific extras
        const val EXTRA_GIF_START_MS    = "gif_start_ms"
        const val EXTRA_GIF_DURATION_MS = "gif_duration_ms"
        const val EXTRA_GIF_FPS         = "gif_fps"
        const val EXTRA_GIF_WIDTH       = "gif_width"
        const val EXTRA_GIF_LOOP_COUNT  = "gif_loop_count"
        const val EXTRA_VIDEO_ID         = "video_id"

        const val CHANNEL_ID             = "conversion_channel"
        const val CHANNEL_ALERT_ID       = "conversion_alert_channel"
        const val NOTIF_PROGRESS_ID      = 1001
        const val NOTIF_COMPLETED_ID     = 1002
        const val NOTIF_ERROR_ID         = 1003
        const val NOTIF_RESOLUTION_ID    = 1004

        private val _state = MutableStateFlow(ConversionState())
        val state = _state.asStateFlow()

        fun resetState() { _state.value = ConversionState() }
        fun setStartingState() { _state.value = ConversionState(isConverting = true, progress = 0) }
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
    @Volatile private var interpolationThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentVideoId: Long = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        transformer = MediaTransformer(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        interpolationThread?.interrupt()
        currentRequestId?.let { transformer?.cancel(it) }
        transformer?.release()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoConvert::Conversion")
            wakeLock?.acquire(4 * 60 * 60 * 1000L) // máx 4 horas
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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

        val hasResolutionChange = (intent?.getIntExtra(EXTRA_TARGET_WIDTH, -1) ?: -1) > 0
        val targetFps = intent?.getIntExtra(EXTRA_TARGET_FPS, -1) ?: -1
        val hasFpsChange = targetFps > 0
        val notifTitle = when {
            format == "gif"                     -> "Creando GIF…"
            hasFpsChange && hasResolutionChange -> "Cambiando resolución y FPS…"
            hasFpsChange                        -> "Interpolando FPS…"
            hasResolutionChange                 -> "Cambiando resolución…"
            else                                -> "Convirtiendo video…"
        }
        _notifTitle = notifTitle
        currentVideoId = intent?.getLongExtra(EXTRA_VIDEO_ID, -1L) ?: -1L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_PROGRESS_ID, buildProgressNotification(0, notifTitle),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_PROGRESS_ID, buildProgressNotification(0, notifTitle))
        }
        acquireWakeLock()
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

        val targetW = intent.getIntExtra(EXTRA_TARGET_WIDTH,  -1)
        val targetH = intent.getIntExtra(EXTRA_TARGET_HEIGHT, -1)

        // ── Pipeline GIF ───────────────────────────────────────────────────
        if (format == "gif") {
            val gifStartMs    = intent.getLongExtra(EXTRA_GIF_START_MS, 0L)
            val gifDurationMs = intent.getLongExtra(EXTRA_GIF_DURATION_MS, 5000L)
            val gifFps        = intent.getIntExtra(EXTRA_GIF_FPS, 10)
            val gifWidth      = intent.getIntExtra(EXTRA_GIF_WIDTH, 320)
            val gifLoopCount  = intent.getIntExtra(EXTRA_GIF_LOOP_COUNT, 0)

            _notifTitle = "Creando GIF…"
            updateProgressNotification(0)

            Thread {
                try {
                    convertToGif(inputUri, outputPath, gifStartMs, gifDurationMs, gifFps, gifWidth, gifLoopCount)
                } catch (e: Exception) {
                    android.util.Log.e("ConversionService", "GIF conversion failed", e)
                    _state.value = ConversionState(error = e.message ?: "Error al crear GIF")
                    showErrorNotification()
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }.apply { name = "gif-encoder"; isDaemon = true; start() }
            return START_NOT_STICKY
        }

        // ── Pipeline de FPS ────────────────────────────────────────────────

        val srcFps = run {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(applicationContext, inputUri, null)
                var fps = -1
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        fps = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE))
                            fmt.getInteger(MediaFormat.KEY_FRAME_RATE) else -1
                        break
                    }
                }
                fps.takeIf { it in 1..240 } ?: 30
            } catch (_: Exception) { 30 } finally { ex.release() }
        }

        if (hasFpsChange && targetFps > srcFps && FpsInterpolator.isAvailable()) {
            // Interpolar FPS en un Thread de fondo para NO bloquear onStartCommand.
            // Una vez terminado (o si falla), arrancamos LiTr desde ese mismo thread.
            updateProgressNotification(0)
            val tmpFile = File(cacheDir, "fps_interp_${System.currentTimeMillis()}.mp4")

            // Las variables que el thread usa deben ser val inmutables
            val capturedInputUri  = inputUri
            val capturedOutputPath = outputPath
            val capturedFormat    = format
            val capturedTargetW   = targetW
            val capturedTargetH   = targetH
            val capturedFps       = targetFps
            val capturedRequestId = requestId

            val thread = Thread {
                val ok = try {
                    FpsInterpolator.interpolate(
                        context      = applicationContext,
                        inputUri     = capturedInputUri,
                        outputFile   = tmpFile,
                        targetFps    = capturedFps,
                        targetWidth  = capturedTargetW,
                        targetHeight = capturedTargetH,
                        onProgress   = { pct ->
                            val mapped = (pct * 0.60).toInt()
                            _state.value = _state.value.copy(progress = mapped)
                            updateProgressNotification(mapped)
                        }
                    )
                } catch (e: Exception) {
                    android.util.Log.e("ConversionService", "FPS interpolation failed", e)
                    false
                }

                if (Thread.currentThread().isInterrupted) return@Thread

                if (ok && tmpFile.exists()) {
                    // Mux: video interpolado + audio del original → archivo combinado
                    val combinedFile = File(cacheDir, "fps_combined_${System.currentTimeMillis()}.mp4")
                    val muxOk = muxVideoWithOriginalAudio(tmpFile, capturedInputUri, combinedFile)
                    tmpFile.delete()

                    if (muxOk && combinedFile.exists()) {
                        // La interpolación y el mux fueron exitosos:
                        // copiar directamente al output (NO pasar por LiTr para preservar FPS).
                        try {
                            val outFile = File(capturedOutputPath)
                            outFile.parentFile?.mkdirs()
                            combinedFile.copyTo(outFile, overwrite = true)
                            combinedFile.delete()

                            _state.value = _state.value.copy(progress = 90)
                            updateProgressNotification(90)

                            val savedUri = registerFileInMediaStore(capturedOutputPath, capturedFormat)
                            _state.value = ConversionState(
                                isConverting = false,
                                progress     = 100,
                                outputPath   = capturedOutputPath,
                                isCompleted  = true
                            )
                            vibrateOnCompletion()
                            showCompletionNotification(capturedOutputPath, savedUri)
                            releaseWakeLock()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } catch (e: Exception) {
                            android.util.Log.e("ConversionService", "Copy combined→output failed", e)
                            combinedFile.delete()
                            // Fallback: pasar original por LiTr
                            val (vidFmt, audFmt) = buildTargetFormats(capturedInputUri, capturedFormat, capturedTargetW, capturedTargetH, capturedFps)
                            launchLitr(capturedRequestId, capturedInputUri, capturedOutputPath, vidFmt, audFmt, null,
                                hasPriorFps = false, format = capturedFormat)
                        }
                    } else {
                        combinedFile.delete()
                        val (vidFmt, audFmt) = buildTargetFormats(capturedInputUri, capturedFormat, capturedTargetW, capturedTargetH, capturedFps)
                        launchLitr(capturedRequestId, capturedInputUri, capturedOutputPath, vidFmt, audFmt, null,
                            hasPriorFps = false, format = capturedFormat)
                    }
                } else {
                    tmpFile.delete()
                    val (vidFmt, audFmt) = buildTargetFormats(capturedInputUri, capturedFormat, capturedTargetW, capturedTargetH, capturedFps)
                    launchLitr(capturedRequestId, capturedInputUri, capturedOutputPath, vidFmt, audFmt, null,
                        hasPriorFps = false, format = capturedFormat)
                }
            }
            thread.name = "fps-interpolator"
            thread.isDaemon = true
            interpolationThread = thread
            thread.start()
            return START_NOT_STICKY
        } else {
            // Sin interpolación de FPS: ir directamente a LiTr
            val (videoFmt, audioFmt) = buildTargetFormats(inputUri, format, targetW, targetH, targetFps)
            launchLitr(requestId, inputUri, outputPath, videoFmt, audioFmt, null, format = format)
        }
        return START_NOT_STICKY
    }

    private fun launchLitr(
        requestId:    String,
        inputUri:     Uri,
        outputPath:   String,
        videoFormat:  MediaFormat,
        audioFormat:  MediaFormat,
        tempFile:     File?,
        hasPriorFps:  Boolean = false,
        format:       String  = "mp4"
    ) {
        transformer?.transform(
            requestId,
            inputUri,
            outputPath,
            videoFormat,
            audioFormat,
            object : TransformationListener {
                override fun onStarted(id: String) {
                    _state.value = ConversionState(isConverting = true, progress = 1)
                }
                override fun onProgress(id: String, progress: Float) {
                    val base = if (hasPriorFps) 60 else 0
                    val span = if (hasPriorFps) 40 else 100
                    val pct = (base + progress * span).toInt().coerceIn(base + 1, 99)
                    _state.value = _state.value.copy(progress = pct)
                    updateProgressNotification(pct)
                }
                override fun onCompleted(id: String, trackTransformationInfos: List<TrackTransformationInfo>?) {
                    tempFile?.delete()
                    val savedUri = registerFileInMediaStore(outputPath, format)
                    _state.value = ConversionState(
                        isConverting = false,
                        progress     = 100,
                        outputPath   = outputPath,
                        isCompleted  = true
                    )
                    vibrateOnCompletion()
                    showCompletionNotification(outputPath, savedUri)
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                override fun onCancelled(id: String, trackTransformationInfos: List<TrackTransformationInfo>?) {
                    tempFile?.delete()
                    _state.value = ConversionState(error = "Conversión cancelada")
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                override fun onError(id: String, cause: Throwable?, trackTransformationInfos: List<TrackTransformationInfo>?) {
                    tempFile?.delete()
                    _state.value = ConversionState(error = cause?.message ?: "Error desconocido durante la conversión")
                    showErrorNotification()
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            },
            TransformationOptions.Builder().build()
        )
    }

    // ── Conversión a GIF ─────────────────────────────────────────────────────

    private fun convertToGif(
        inputUri: Uri,
        outputPath: String,
        startMs: Long,
        durationMs: Long,
        fps: Int,
        targetWidth: Int,
        loopCount: Int
    ) {
        val TAG = "ConversionService"
        android.util.Log.i(TAG, "GIF: inicio uri=$inputUri startMs=$startMs durMs=$durationMs fps=$fps width=$targetWidth loop=$loopCount")

        // ── 1. MediaExtractor: encontrar pista de video ──
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(applicationContext, inputUri, null)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "GIF: no se puede abrir video", e)
            _state.value = ConversionState(error = "No se puede acceder al video")
            releaseWakeLock()
            showErrorNotification(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return
        }

        var trackIdx = -1
        var trackFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                trackIdx = i; trackFormat = fmt; break
            }
        }
        if (trackIdx < 0 || trackFormat == null) {
            android.util.Log.e(TAG, "GIF: no hay pista de video")
            extractor.release()
            _state.value = ConversionState(error = "No se encontró pista de video")
            releaseWakeLock()
            showErrorNotification(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return
        }
        extractor.selectTrack(trackIdx)

        val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
        val videoW = trackFormat.getInteger(MediaFormat.KEY_WIDTH)
        val videoH = trackFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val videoDurUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION))
            trackFormat.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE
        android.util.Log.i(TAG, "GIF: video ${videoW}x${videoH} mime=$mime dur=${videoDurUs/1000}ms")

        // ── 2. Dimensiones del GIF ──
        val scale = targetWidth.toFloat() / videoW
        val gifW = targetWidth
        val gifH = (videoH * scale).toInt().let { if (it % 2 != 0) it + 1 else it }

        val startUs = startMs * 1000L
        val endUs = ((startMs + durationMs) * 1000L).coerceAtMost(videoDurUs)
        val frameIntervalUs = 1_000_000L / fps
        val totalFrames = ((endUs - startUs) / frameIntervalUs).toInt().coerceAtLeast(1)
        android.util.Log.i(TAG, "GIF: ${gifW}x${gifH} frames=$totalFrames interval=${frameIntervalUs}us range=[${startUs/1000}ms..${endUs/1000}ms]")

        // ── 3. ImageReader + HandlerThread para capturar frames ──
        val ht = android.os.HandlerThread("gif-reader").apply { start() }
        val handler = android.os.Handler(ht.looper)
        val frameSem = java.util.concurrent.Semaphore(0)
        val imageReader = android.media.ImageReader.newInstance(
            videoW, videoH, android.graphics.ImageFormat.YUV_420_888, 3
        )
        imageReader.setOnImageAvailableListener({ frameSem.release() }, handler)

        // ── 4. MediaCodec decoder → Surface del ImageReader ──
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(trackFormat, imageReader.surface, null, 0)
        decoder.start()
        android.util.Log.i(TAG, "GIF: decoder iniciado")

        if (startMs > 0) {
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            android.util.Log.d(TAG, "GIF: seek a ${extractor.sampleTime/1000}ms (pedido ${startMs}ms)")
        }

        // ── 5. Encoder GIF ──
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()
        val fos = java.io.FileOutputStream(outputFile)

        val gifEncoder = AnimatedGifEncoder()
        gifEncoder.setSize(gifW, gifH)
        gifEncoder.setRepeat(loopCount)
        gifEncoder.setDelay(1000 / fps)
        gifEncoder.setQuality(10)
        gifEncoder.start(fos)

        // ── 6. Bucle de decodificación ──
        val bufInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var framesEncoded = 0
        var nextCaptureUs = startUs

        while (!outputDone && !Thread.currentThread().isInterrupted) {
            // Alimentar input buffers
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                        android.util.Log.d(TAG, "GIF: input EOS (extractor agotado)")
                    } else {
                        val pts = extractor.sampleTime
                        decoder.queueInputBuffer(inIdx, 0, size, pts, 0)
                        extractor.advance()
                        if (pts > endUs) {
                            val eosIdx = decoder.dequeueInputBuffer(10_000)
                            if (eosIdx >= 0) {
                                decoder.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                            inputDone = true
                            android.util.Log.d(TAG, "GIF: input EOS (pts=${pts/1000}ms > end=${endUs/1000}ms)")
                        }
                    }
                }
            }

            // Drenar output buffers
            val outIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000)
            if (outIdx >= 0) {
                val pts = bufInfo.presentationTimeUs
                val eos = (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val wantCapture = pts >= nextCaptureUs && pts <= endUs && framesEncoded < totalFrames

                if (wantCapture) {
                    decoder.releaseOutputBuffer(outIdx, true) // render a Surface
                    val got = frameSem.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS)
                    if (got) {
                        val image = imageReader.acquireLatestImage()
                        if (image != null) {
                            val bitmap = imageToArgbBitmap(image)
                            image.close()
                            val scaled = Bitmap.createScaledBitmap(bitmap, gifW, gifH, true)
                            gifEncoder.addFrame(scaled)
                            if (scaled !== bitmap) scaled.recycle()
                            bitmap.recycle()
                            framesEncoded++
                            nextCaptureUs = startUs + framesEncoded.toLong() * frameIntervalUs
                            val pct = (framesEncoded * 100 / totalFrames).coerceIn(1, 99)
                            _state.value = _state.value.copy(progress = pct)
                            updateProgressNotification(pct)
                            android.util.Log.d(TAG, "GIF: frame $framesEncoded/$totalFrames pts=${pts/1000}ms")
                        } else {
                            android.util.Log.w(TAG, "GIF: acquireLatestImage null pts=${pts/1000}ms")
                        }
                    } else {
                        android.util.Log.w(TAG, "GIF: semáforo timeout pts=${pts/1000}ms")
                    }
                } else {
                    decoder.releaseOutputBuffer(outIdx, false)
                }

                if (eos || framesEncoded >= totalFrames) {
                    outputDone = true
                    android.util.Log.i(TAG, "GIF: output done eos=$eos frames=$framesEncoded")
                }
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                android.util.Log.d(TAG, "GIF: output format changed: ${decoder.outputFormat}")
            }
        }

        // ── 7. Limpieza ──
        decoder.stop()
        decoder.release()
        imageReader.close()
        ht.quitSafely()
        extractor.release()

        gifEncoder.finish()
        fos.close()

        android.util.Log.i(TAG, "GIF: terminado. Frames=$framesEncoded Tamaño=${File(outputPath).length()} bytes")

        if (framesEncoded < 2) {
            outputFile.delete()
            _state.value = ConversionState(error = "No se pudieron extraer suficientes frames")
            releaseWakeLock()
            showErrorNotification(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return
        }

        val savedUri = registerFileInMediaStore(outputPath, "gif")
        _state.value = ConversionState(
            isConverting = false,
            progress     = 100,
            outputPath   = outputPath,
            isCompleted  = true
        )
        vibrateOnCompletion()
        showCompletionNotification(outputPath, savedUri)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun imageToArgbBitmap(image: android.media.Image): Bitmap {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        // Convertir YUV_420_888 → NV21 para usar YuvImage
        val nv21 = ByteArray(w * h * 3 / 2)
        // Copiar Y
        for (row in 0 until h) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, row * w, w)
        }
        // Copiar VU intercalado (NV21 = Y + VU)
        val uvH = h / 2
        var nv21Offset = w * h
        for (row in 0 until uvH) {
            for (col in 0 until w / 2) {
                val uvIdx = row * uvRowStride + col * uvPixelStride
                nv21[nv21Offset++] = vBuf.get(uvIdx)   // V
                nv21[nv21Offset++] = uBuf.get(uvIdx)   // U
            }
        }

        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
        val baos = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, w, h), 90, baos)
        return android.graphics.BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size())
    }

    // ── Vibración ─────────────────────────────────────────────────────────────

    private fun vibrateOnCompletion() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            mgr.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ── Notificaciones ────────────────────────────────────────────────────────

    private fun buildProgressNotification(progress: Int, title: String = "Convirtiendo video…"): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                if (currentVideoId > 0) putExtra(EXTRA_VIDEO_ID, currentVideoId)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isIndeterminate = progress == 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (isIndeterminate) "Iniciando…" else "$progress%")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setProgress(100, progress, isIndeterminate)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
    }

    private var _notifTitle = "Convirtiendo video…"

    private fun updateProgressNotification(progress: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_PROGRESS_ID, buildProgressNotification(progress, _notifTitle))
    }

    private fun showCompletionNotification(outputPath: String, mediaUri: Uri?) {
        val fileName = outputPath.substringAfterLast('/')
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val isGif = fileName.endsWith(".gif", ignoreCase = true)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            if (currentVideoId > 0) putExtra(EXTRA_VIDEO_ID, currentVideoId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, NOTIF_COMPLETED_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        nm.notify(
            NOTIF_COMPLETED_ID,
            NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
                .setContentTitle(if (isGif) "✓ GIF creado" else "✓ Conversión completada")
                .setContentText("Toca para abrir: $fileName")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
        )
    }

    private fun showErrorNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_ERROR_ID,
            NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
                .setContentTitle("Error en la conversión")
                .setContentText("Revisa el video e inténtalo de nuevo")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun registerFileInMediaStore(filePath: String, format: String): Uri? {
        val file = File(filePath)
        if (!file.exists()) return null
        val isGif = format.lowercase() == "gif"
        val mimeType = when (format.lowercase()) {
            "gif"  -> "image/gif"
            "webm" -> "video/webm"
            "3gp"  -> "video/3gpp"
            else   -> "video/mp4"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                if (isGif) {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VideoConvert")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoConvert")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val collection = if (isGif)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val uri = contentResolver.insert(collection, values) ?: return null
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                val upd = ContentValues().apply {
                    if (isGif) put(MediaStore.Images.Media.IS_PENDING, 0)
                    else put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, upd, null, null)
                file.delete()
                uri
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                null
            }
        } else {
            val dirType = if (isGif) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
            val dest = File(
                Environment.getExternalStoragePublicDirectory(dirType),
                "VideoConvert/${file.name}"
            )
            dest.parentFile?.mkdirs()
            file.copyTo(dest, overwrite = true)
            file.delete()
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), null, null)
            null
        }
    }

    private fun stopAndReturn(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    /**
     * Combina el track de video del archivo interpolado con el audio del video original.
     * Si el original no tiene audio, el resultado solo tendrá video.
     */
    private fun muxVideoWithOriginalAudio(
        videoFile: File,
        originalUri: Uri,
        outputFile: File
    ): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            var videoFormat: MediaFormat? = null
            var vidIdx = -1
            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    vidIdx = i; videoFormat = fmt; break
                }
            }
            if (vidIdx < 0 || videoFormat == null) return false
            videoExtractor.selectTrack(vidIdx)

            audioExtractor.setDataSource(applicationContext, originalUri, null)
            var audioFormat: MediaFormat? = null
            var audIdx = -1
            for (i in 0 until audioExtractor.trackCount) {
                val fmt = audioExtractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audIdx = i; audioFormat = fmt; break
                }
            }
            if (audIdx >= 0) audioExtractor.selectTrack(audIdx)

            outputFile.parentFile?.mkdirs()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxVid = muxer!!.addTrack(videoFormat)
            val muxAud = if (audIdx >= 0 && audioFormat != null) muxer!!.addTrack(audioFormat) else -1
            muxer!!.start()

            val buf = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            while (true) {
                buf.clear()
                val sz = videoExtractor.readSampleData(buf, 0)
                if (sz < 0) break
                info.set(0, sz, videoExtractor.sampleTime, videoExtractor.sampleFlags)
                muxer!!.writeSampleData(muxVid, buf, info)
                videoExtractor.advance()
            }

            if (muxAud >= 0) {
                while (true) {
                    buf.clear()
                    val sz = audioExtractor.readSampleData(buf, 0)
                    if (sz < 0) break
                    info.set(0, sz, audioExtractor.sampleTime, audioExtractor.sampleFlags)
                    muxer!!.writeSampleData(muxAud, buf, info)
                    audioExtractor.advance()
                }
            }

            muxer!!.stop()
            muxer!!.release()
            muxer = null
            outputFile.exists() && outputFile.length() > 0
        } catch (e: Exception) {
            android.util.Log.e("ConversionService", "muxVideoWithOriginalAudio failed", e)
            false
        } finally {
            videoExtractor.release()
            audioExtractor.release()
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Construye MediaFormats explícitos para la transcodificación.
     * Siempre usa H.264/AVC + AAC independientemente del formato de salida.
     *
     * Razón: MIMETYPE_AUDIO_VORBIS (para WebM) y VP8 no tienen encoder hardware
     * en la mayoría de dispositivos Android modernos → causa "No encoder found".
     * H.264/AAC está garantizado en todos los dispositivos Android (requerido por CDD).
     */
    private fun buildTargetFormats(
        inputUri: Uri,
        format: String,
        targetWidth: Int = -1,
        targetHeight: Int = -1,
        targetFps: Int = -1
    ): Pair<MediaFormat, MediaFormat> {
        val retriever = MediaMetadataRetriever()
        var srcWidth  = 1920
        var srcHeight = 1080
        var frameRate = 30

        try {
            retriever.setDataSource(applicationContext, inputUri)
            srcWidth  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.takeIf { it > 0 } ?: 1920
            srcHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.takeIf { it > 0 } ?: 1080
            val fpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val fps = fpsStr?.toFloatOrNull()?.toInt()?.takeIf { it in 1..120 }
            if (fps != null) frameRate = fps
        } catch (_: Exception) {
            // usar defaults 1920x1080@30fps
        } finally {
            retriever.release()
        }

        // Si el usuario especificó resolución destino, respetarla; si no, mantener la original
        val width  = if (targetWidth  > 0) targetWidth  else srcWidth
        val height = if (targetHeight > 0) targetHeight else srcHeight
        // FPS: usar el especificado por el usuario, si no el del fuente
        if (targetFps in 1..120) frameRate = targetFps

        val pixels = width.toLong() * height.toLong()
        val videoBitRate = when {
            pixels >= 3840L * 2160 -> 15_000_000  // 4K
            pixels >= 1920L * 1080 ->  6_000_000  // 1080p
            pixels >= 1280L *  720 ->  3_000_000  // 720p
            else                   ->  1_500_000  // 480p o menos
        }

        // Siempre H.264 (AVC) + AAC: únicos codecs con encoder hardware garantizado
        // en todos los dispositivos Android (requerimiento del CDD de Android).
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width.toInt(), height.toInt()).apply {
            setInteger(MediaFormat.KEY_BIT_RATE,         videoBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE,       frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }

        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE,    128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }

        return Pair(videoFormat, audioFormat)
    }
}
