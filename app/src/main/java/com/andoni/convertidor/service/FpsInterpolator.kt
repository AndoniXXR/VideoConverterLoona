package com.andoni.convertidor.service

import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.net.Uri
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.video.Video
import java.io.File
import java.nio.ByteBuffer

/**
 * Interpolador de FPS usando Optical Flow clásico (Farneback) de OpenCV.
 *
 * Pipeline:
 *  1. Decodificar todos los frames del video fuente con MediaCodec → Bitmap.
 *  2. Para cada par de frames consecutivos, calcular optical flow bidireccional.
 *  3. Generar N-1 frames intermedios usando warp + mezcla ponderada.
 *  4. Re-codificar la secuencia nueva con MediaCodec encoder → MP4 con MediaMuxer.
 *
 * Limitaciones documentadas:
 *  - Falla visualmente en cortes de escena abruptos (se detectan y se duplica el frame anterior).
 *  - Para movimientos muy rápidos (>80px/frame) el resultado puede tener ghosting ligero.
 *  - Solo salida MP4/H.264 independientemente del formato final (LiTr hace la conversión después).
 */
object FpsInterpolator {

    private const val TAG = "FpsInterpolator"
    private const val TIMEOUT_US = 10_000L

    /** Devuelve true si OpenCV pudo inicializarse. */
    fun isAvailable(): Boolean = OpenCVLoader.initLocal()

    /**
     * @param context       Contexto Android.
     * @param inputUri      URI del video fuente (content://).
     * @param outputFile    Archivo de salida temporal (debe ser .mp4).
     * @param targetFps     FPS de destino (ej. 60).
     * @param targetWidth   Ancho final (-1 = mantener original).
     * @param targetHeight  Alto final (-1 = mantener original).
     * @param onProgress    Callback 0..100 para actualizar la notificación.
     * @return true si tuvo éxito.
     */
    fun interpolate(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        targetFps: Int,
        targetWidth: Int = -1,
        targetHeight: Int = -1,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        if (!isAvailable()) {
            Log.e(TAG, "OpenCV no disponible")
            return false
        }

        // ── 1. Leer metadatos ─────────────────────────────────────────────────
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, inputUri)
        val srcFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toFloatOrNull()?.toInt()?.takeIf { it in 1..120 } ?: 30
        val srcW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()?.takeIf { it > 0 } ?: 1280
        val srcH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()?.takeIf { it > 0 } ?: 720
        val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.let { it * 1000L } ?: 0L
        retriever.release()

        if (targetFps <= srcFps) {
            // Bajar FPS: solo cambiar KEY_FRAME_RATE en LiTr es suficiente.
            // Este interpolador solo actúa para subir FPS.
            Log.w(TAG, "targetFps ($targetFps) <= srcFps ($srcFps), interpolación no necesaria")
            return false
        }

        val outW = if (targetWidth  > 0) targetWidth  else srcW
        val outH = if (targetHeight > 0) targetHeight else srcH

        // Factor de interpolación: cuántos frames intermedios generar por par de frames fuente
        // Ej: 30→60 = 1 frame intermedio; 30→120 = 3 frames intermedios
        val interpFactor = targetFps / srcFps   // entero; ej 2 para 30→60
        // Intervalos de tiempo entre frames de destino (microsegundos)
        val frameDurationUs = 1_000_000L / targetFps

        Log.d(TAG, "Interpolando $srcFps→${targetFps}fps ($srcW×$srcH → ${outW}×${outH}), factor=$interpFactor")

        // ── 2. Decodificar frames fuente ──────────────────────────────────────
        val frames = decodeAllFrames(context, inputUri, srcW, srcH) ?: return false
        if (frames.size < 2) return false
        onProgress(20)

        // ── 3. Generar frames interpolados ────────────────────────────────────
        val outputFrames = mutableListOf<Bitmap>()
        val totalPairs = frames.size - 1

        for (i in 0 until frames.size) {
            // Añadir el frame original
            val bmp = if (outW != srcW || outH != srcH)
                Bitmap.createScaledBitmap(frames[i], outW, outH, true)
            else frames[i]
            outputFrames.add(bmp)

            // Generar frames intermedios entre frame[i] y frame[i+1]
            if (i < frames.size - 1 && interpFactor > 1) {
                val frameA = frames[i]
                val frameB = frames[i + 1]

                // Detectar corte de escena: si la diferencia de brillo entre frames es muy grande
                // no interpolar (duplicar en su lugar) para evitar ghosting en cortes.
                if (!isSceneCut(frameA, frameB)) {
                    val matA = bitmapToMat(frameA)
                    val matB = bitmapToMat(frameB)

                    // Escalar para optical flow si la resolución es grande (mejora velocidad)
                    val flowScale = if (srcW > 1280) 0.5f else 1.0f
                    val flowW = (srcW * flowScale).toInt()
                    val flowH = (srcH * flowScale).toInt()
                    val smallA = Mat(); val smallB = Mat()
                    org.opencv.imgproc.Imgproc.resize(matA, smallA, Size(flowW.toDouble(), flowH.toDouble()))
                    org.opencv.imgproc.Imgproc.resize(matB, smallB, Size(flowW.toDouble(), flowH.toDouble()))

                    val grayA = Mat(); val grayB = Mat()
                    org.opencv.imgproc.Imgproc.cvtColor(smallA, grayA, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
                    org.opencv.imgproc.Imgproc.cvtColor(smallB, grayB, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)

                    // Optical flow Farneback A→B
                    val flowAB = Mat()
                    Video.calcOpticalFlowFarneback(grayA, grayB, flowAB,
                        0.5, 3, 15, 3, 5, 1.2, 0)

                    // Escalar flow de vuelta a resolución original si se redujo
                    val fullFlowAB = if (flowScale < 1.0f) {
                        val scaled = Mat()
                        org.opencv.imgproc.Imgproc.resize(flowAB, scaled, Size(srcW.toDouble(), srcH.toDouble()))
                        Core.multiply(scaled, Scalar(1.0 / flowScale, 1.0 / flowScale), scaled)
                        scaled
                    } else flowAB

                    for (k in 1 until interpFactor) {
                        val alpha = k.toFloat() / interpFactor   // 0..1
                        val interpolated = interpolateFrame(matA, matB, fullFlowAB, alpha, outW, outH)
                        outputFrames.add(interpolated)
                    }

                    matA.release(); matB.release()
                    smallA.release(); smallB.release()
                    grayA.release(); grayB.release()
                    flowAB.release()
                    if (flowScale < 1.0f) fullFlowAB.release()
                } else {
                    // Corte de escena: duplicar el frame A en los huecos
                    repeat(interpFactor - 1) { outputFrames.add(bmp) }
                }
            }

            val progressPct = 20 + (i.toFloat() / totalPairs * 50).toInt()
            onProgress(progressPct)
        }

        // Liberar frames fuente de memoria
        frames.forEach { if (!it.isRecycled) it.recycle() }
        onProgress(70)

        // ── 4. Codificar frames de salida → MP4 ──────────────────────────────
        val success = encodeFrames(outputFrames, outputFile, outW, outH, targetFps, frameDurationUs, onProgress)

        outputFrames.forEach { if (!it.isRecycled) it.recycle() }
        return success
    }

    // ── Decodificar todos los frames como Bitmap ──────────────────────────────

    private fun decodeAllFrames(context: Context, uri: Uri, w: Int, h: Int): List<Bitmap>? {
        val frames = mutableListOf<Bitmap>()
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var videoTrack = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                videoTrack = i
                videoFormat = fmt
                break
            }
        }
        if (videoTrack < 0 || videoFormat == null) {
            extractor.release()
            return null
        }
        extractor.selectTrack(videoTrack)

        val mime = videoFormat.getString(MediaFormat.KEY_MIME)!!
        val decoder = MediaCodec.createDecoderByType(mime)

        // Surface nula → salida en ByteBuffer (YUV/RGB dependiendo del dispositivo)
        decoder.configure(videoFormat, null, null, 0)
        decoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var eos = false

        while (true) {
            if (!eos) {
                val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outIdx >= 0) {
                // Pedir el frame como imagen al decoder
                val image = decoder.getOutputImage(outIdx)
                if (image != null) {
                    val bmp = imageToBitmap(image, w, h)
                    image.close()
                    if (bmp != null) frames.add(bmp)
                }
                decoder.releaseOutputBuffer(outIdx, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // ignorar
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()
        return frames
    }

    // ── Convertir Image (YUV_420_888) a Bitmap RGBA ───────────────────────────

    private fun imageToBitmap(image: android.media.Image, targetW: Int, targetH: Int): Bitmap? {
        return try {
            // Usar YuvImage para convertir YUV_420_888 → JPEG → Bitmap
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = android.graphics.YuvImage(
                nv21, android.graphics.ImageFormat.NV21,
                image.width, image.height, null
            )
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 90, out)
            val jpegBytes = out.toByteArray()
            val raw = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            if (raw.width == targetW && raw.height == targetH) raw
            else {
                val scaled = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
                raw.recycle()
                scaled
            }
        } catch (e: Exception) {
            Log.w(TAG, "imageToBitmap error: ${e.message}")
            null
        }
    }

    // ── Bitmap ↔ Mat ──────────────────────────────────────────────────────────

    private fun bitmapToMat(bmp: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bmp, mat)
        return mat
    }

    private fun matToBitmap(mat: Mat, w: Int, h: Int): Bitmap {
        val out = if (mat.cols() == w && mat.rows() == h) mat
        else {
            val resized = Mat()
            org.opencv.imgproc.Imgproc.resize(mat, resized, Size(w.toDouble(), h.toDouble()))
            resized
        }
        val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bmp)
        return bmp
    }

    // ── Detectar cortes de escena (diferencia de brillo media > umbral) ───────

    private fun isSceneCut(a: Bitmap, b: Bitmap): Boolean {
        // Muestra 100 píxeles aleatorios y mide diferencia de luminancia media
        var diff = 0.0
        val samples = 100
        val w = a.width; val h = a.height
        for (i in 0 until samples) {
            val x = (w * i / samples)
            val y = (h * i / samples)
            val pA = a.getPixel(x, y)
            val pB = b.getPixel(x, y)
            val lumaA = (0.299 * android.graphics.Color.red(pA) +
                         0.587 * android.graphics.Color.green(pA) +
                         0.114 * android.graphics.Color.blue(pA))
            val lumaB = (0.299 * android.graphics.Color.red(pB) +
                         0.587 * android.graphics.Color.green(pB) +
                         0.114 * android.graphics.Color.blue(pB))
            diff += Math.abs(lumaA - lumaB)
        }
        return (diff / samples) > 60.0  // umbral empírico
    }

    // ── Interpolar un frame intermedio con optical flow + warp ────────────────

    /**
     * Genera el frame en la posición [alpha] ∈ (0,1) entre frameA y frameB.
     * Usa warp bidireccional: mueve A hacia adelante [alpha] y B hacia atrás [1-alpha],
     * luego mezcla los dos warps ponderados.
     */
    private fun interpolateFrame(
        matA: Mat, matB: Mat, flowAB: Mat,
        alpha: Float, outW: Int, outH: Int
    ): Bitmap {
        val h = matA.rows(); val w = matA.cols()

        // Construir mapa de remapeo A → posición intermedia (mover alpha * flow)
        val mapXf = Mat(h, w, CvType.CV_32FC1)
        val mapYf = Mat(h, w, CvType.CV_32FC1)
        val mapXb = Mat(h, w, CvType.CV_32FC1)
        val mapYb = Mat(h, w, CvType.CV_32FC1)

        for (row in 0 until h) {
            for (col in 0 until w) {
                val fv = flowAB.get(row, col)  // [dx, dy]
                val dx = fv[0].toFloat()
                val dy = fv[1].toFloat()
                mapXf.put(row, col, floatArrayOf(col + alpha * dx))
                mapYf.put(row, col, floatArrayOf(row + alpha * dy))
                mapXb.put(row, col, floatArrayOf(col - (1f - alpha) * dx))
                mapYb.put(row, col, floatArrayOf(row - (1f - alpha) * dy))
            }
        }

        val warpedA = Mat(); val warpedB = Mat()
        org.opencv.imgproc.Imgproc.remap(matA, warpedA, mapXf, mapYf,
            org.opencv.imgproc.Imgproc.INTER_LINEAR)
        org.opencv.imgproc.Imgproc.remap(matB, warpedB, mapXb, mapYb,
            org.opencv.imgproc.Imgproc.INTER_LINEAR)

        // Mezcla ponderada
        val blended = Mat()
        Core.addWeighted(warpedA, (1.0 - alpha).toDouble(), warpedB, alpha.toDouble(), 0.0, blended)

        val result = matToBitmap(blended, outW, outH)
        mapXf.release(); mapYf.release(); mapXb.release(); mapYb.release()
        warpedA.release(); warpedB.release(); blended.release()
        return result
    }

    // ── Codificar lista de Bitmaps → MP4 con MediaCodec ──────────────────────

    private fun encodeFrames(
        frames: List<Bitmap>,
        outputFile: File,
        w: Int, h: Int,
        fps: Int,
        frameDurationUs: Long,
        onProgress: (Int) -> Unit
    ): Boolean {
        outputFile.parentFile?.mkdirs()

        val pixels = w.toLong() * h
        val bitRate = when {
            pixels >= 3840L * 2160 -> 15_000_000
            pixels >= 1920L * 1080 ->  8_000_000
            pixels >= 1280L *  720 ->  4_000_000
            else                   ->  2_000_000
        }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_BIT_RATE,         bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE,       fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrack = -1
        var muxerStarted = false
        var presentationTimeUs = 0L
        val bufferInfo = MediaCodec.BufferInfo()

        for ((idx, bmp) in frames.withIndex()) {
            // Enviar frame al encoder
            val inputIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIdx >= 0) {
                val inputBuf = encoder.getInputBuffer(inputIdx)!!
                inputBuf.clear()
                val yuv = bitmapToYuv420(bmp, w, h)
                inputBuf.put(yuv)
                encoder.queueInputBuffer(inputIdx, 0, yuv.size, presentationTimeUs, 0)
                presentationTimeUs += frameDurationUs
            }

            // Drenar encoder
            drainEncoder(encoder, muxer, bufferInfo, {
                if (muxerTrack < 0) {
                    muxerTrack = muxer.addTrack(it)
                    muxer.start()
                    muxerStarted = true
                }
            }, muxerTrack, muxerStarted)

            val prog = 70 + (idx.toFloat() / frames.size * 25).toInt()
            onProgress(prog)
        }

        // Señal de fin de stream
        val eosIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (eosIdx >= 0) {
            encoder.queueInputBuffer(eosIdx, 0, 0, presentationTimeUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }

        // Drenar hasta EOS
        var eos = false
        while (!eos) {
            val outIdx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerTrack < 0) {
                        muxerTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                outIdx >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        encoder.releaseOutputBuffer(outIdx, false)
                    } else {
                        val buf = encoder.getOutputBuffer(outIdx)!!
                        if (muxerStarted && muxerTrack >= 0)
                            muxer.writeSampleData(muxerTrack, buf, bufferInfo)
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                            eos = true
                    }
                }
            }
        }

        encoder.stop(); encoder.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
        onProgress(100)
        return outputFile.exists() && outputFile.length() > 0
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        onFormatChanged: (MediaFormat) -> Unit,
        muxerTrack: Int,
        muxerStarted: Boolean
    ) {
        var outIdx = encoder.dequeueOutputBuffer(bufferInfo, 0)
        while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onFormatChanged(encoder.outputFormat)
            } else if (outIdx >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val buf = encoder.getOutputBuffer(outIdx)!!
                    if (muxerStarted && muxerTrack >= 0)
                        muxer.writeSampleData(muxerTrack, buf, bufferInfo)
                }
                encoder.releaseOutputBuffer(outIdx, false)
            }
            outIdx = encoder.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    // ── Convertir Bitmap ARGB_8888 → YUV 420 Flexible (NV12) ─────────────────

    private fun bitmapToYuv420(bmp: Bitmap, w: Int, h: Int): ByteArray {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val yuv = ByteArray(w * h * 3 / 2)
        val uvOffset = w * h
        var uvIdx = uvOffset
        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = pixels[j * w + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8)  and 0xFF
                val b =  p         and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[j * w + i] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIdx++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIdx++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }
}
