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
 * Interpolador de FPS — arquitectura STREAMING.
 *
 * Principios de diseño (evitar OOM/ANR):
 *  • Nunca se cargan todos los frames en RAM a la vez. Se procesan de a UN PAR por vez.
 *  • El mapa de remapeo se construye con operaciones vectorizadas de OpenCV (sin bucle pixel).
 *  • Encoder + Muxer se mantienen abiertos durante todo el proceso; cada frame se escribe
 *    al disco inmediatamente y el Bitmap/Mat se libera antes de pasar al siguiente par.
 *  • Este método BLOQUEA el hilo que lo invoca — llamarlo siempre desde un Thread/Coroutine
 *    dedicado (NO desde onStartCommand directamente).
 *
 *  Para bajar FPS: no usar este objeto. LiTr lo maneja con KEY_FRAME_RATE.
 *  Para subir  FPS: llamar interpolate() que devuelve true si tuvo éxito.
 */
object FpsInterpolator {

    private const val TAG        = "FpsInterpolator"
    private const val TIMEOUT_US = 10_000L

    // Resolución máxima para el cálculo de optical flow.
    // Si el video es más grande se escala antes de Farneback y el flow se re-escala.
    // Esto reduce el tiempo de cálculo de O(W×H) a O(FLOW_MAX_W×FLOW_MAX_H).
    private const val FLOW_MAX_W = 480
    private const val FLOW_MAX_H = 270

    fun isAvailable(): Boolean = OpenCVLoader.initLocal()

    // ──────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Interpola frames para pasar de [srcFps] a [targetFps] usando Farneback optical flow.
     * Pipeline streaming: decodifica, interpola y codifica un par de frames a la vez.
     *
     * @param onProgress  Callback llamado con valores 0..100. Se puede llamar frecuentemente.
     * @return  true si el archivo de salida fue creado con éxito.
     */
    fun interpolate(
        context:     Context,
        inputUri:    Uri,
        outputFile:  File,
        targetFps:   Int,
        targetWidth: Int = -1,
        targetHeight: Int = -1,
        onProgress:  (Int) -> Unit = {}
    ): Boolean {
        if (!isAvailable()) {
            Log.e(TAG, "OpenCV no disponible")
            return false
        }

        // ── Metadatos del video fuente ────────────────────────────────────────
        val meta = readVideoMeta(context, inputUri)
        val srcFps = meta.fps
        val srcW   = meta.width
        val srcH   = meta.height

        if (targetFps <= srcFps) {
            Log.w(TAG, "targetFps ($targetFps) <= srcFps ($srcFps): no se necesita interpolación")
            return false
        }

        // interpFactor = cuántos frames de salida por cada frame fuente
        // 30→60: factor 2 (1 original + 1 interpolado)
        // 30→120: factor 4
        val interpFactor  = targetFps / srcFps
        val outW          = if (targetWidth  > 0) targetWidth  else srcW
        val outH          = if (targetHeight > 0) targetHeight else srcH
        val frameDurUs    = 1_000_000L / targetFps

        Log.d(TAG, "Interpolando: $srcFps→${targetFps}fps, ${srcW}×${srcH}→${outW}×${outH}, factor=$interpFactor")
        outputFile.parentFile?.mkdirs()

        // ── Inicializar encoder + muxer (se mantienen abiertos todo el tiempo) ─
        val encoder = createEncoder(outW, outH, targetFps)
        val muxer   = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrack     = -1
        var muxerStarted   = false
        var presentationUs = 0L
        val bufInfo        = MediaCodec.BufferInfo()

        // ── Decodificador + streaming ─────────────────────────────────────────
        val extractor   = MediaExtractor()
        val decoder     = prepareDecoder(context, inputUri, extractor) ?: run {
            encoder.stop(); encoder.release(); muxer.release()
            return false
        }
        decoder.start()

        // Estimamos el total de frames para el progreso
        val totalFrames = (meta.durationMs / 1000.0 * srcFps).toLong().coerceAtLeast(1)
        var decodedFrames = 0L
        var decoderEos    = false
        var inputEos      = false

        // Mantemos el Mat del frame ANTERIOR para calcular el flow entre pares
        var prevMat: Mat? = null
        var prevPts: Long = 0L

        try {
            while (!decoderEos) {
                // ── Alimentar el decodificador ────────────────────────────────
                if (!inputEos) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf  = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // ── Obtener frame decodificado ────────────────────────────────
                val outIdx = decoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (outIdx < 0) continue

                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    decoder.releaseOutputBuffer(outIdx, false)
                    decoderEos = true
                    // Escribir el último frame pendiente sin interpolar
                    prevMat?.let { m ->
                        val bmp = matToBitmap(m, outW, outH)
                        writeFrameToEncoder(bmp, encoder, muxer, bufInfo, outW, outH,
                            presentationUs, frameDurUs,
                            { trk -> muxerTrack = trk; muxerStarted = true },
                            muxerTrack, muxerStarted
                        )
                        presentationUs += frameDurUs
                        bmp.recycle()
                        m.release()
                        prevMat = null
                    }
                    break
                }

                val image = decoder.getOutputImage(outIdx)
                decoder.releaseOutputBuffer(outIdx, false)
                if (image == null) continue

                val currMat = imageToMat(image, srcW, srcH)
                image.close()
                if (currMat == null) continue

                decodedFrames++
                val prog = ((decodedFrames.toFloat() / totalFrames) * 90).toInt().coerceIn(0, 90)
                onProgress(prog)

                val curr = prevMat
                if (curr == null) {
                    // Primer frame: solo guardar como previo
                    prevMat = currMat
                    prevPts = bufInfo.presentationTimeUs
                    continue
                }

                // ── Tenemos un par (curr=previo, currMat=actual) ─────────────
                // 1. Escribir el frame ANTERIOR (original) al encoder
                val bmpA = matToBitmap(curr, outW, outH)
                writeFrameToEncoder(bmpA, encoder, muxer, bufInfo, outW, outH,
                    presentationUs, frameDurUs,
                    { trk -> muxerTrack = trk; muxerStarted = true },
                    muxerTrack, muxerStarted
                )
                presentationUs += frameDurUs
                bmpA.recycle()

                // 2. Calcular optical flow (a baja resolución) entre curr y currMat
                if (interpFactor > 1 && !isSceneCut(curr, currMat)) {
                    val flow = computeFlowLowRes(curr, currMat)

                    // 3. Generar (interpFactor-1) frames interpolados
                    for (k in 1 until interpFactor) {
                        val alpha = k.toFloat() / interpFactor
                        val interpolated = warpAndBlend(curr, currMat, flow, alpha, outW, outH)
                        writeFrameToEncoder(interpolated, encoder, muxer, bufInfo, outW, outH,
                            presentationUs, frameDurUs,
                            { trk -> muxerTrack = trk; muxerStarted = true },
                            muxerTrack, muxerStarted
                        )
                        presentationUs += frameDurUs
                        interpolated.recycle()
                    }
                    flow.release()
                }

                // 4. Liberar el Mat anterior y avanzar
                curr.release()
                prevMat = currMat
                prevPts = bufInfo.presentationTimeUs
            }
        } finally {
            prevMat?.release()
            decoder.stop()
            decoder.release()
            extractor.release()
        }

        // ── Señal EOS al encoder ──────────────────────────────────────────────
        flushEncoder(encoder, muxer, bufInfo,
            { trk -> muxerTrack = trk; muxerStarted = true },
            muxerTrack, muxerStarted, presentationUs
        )

        encoder.stop()
        encoder.release()
        if (muxerStarted) { try { muxer.stop() } catch (_: Exception) {} }
        muxer.release()

        onProgress(100)
        val ok = outputFile.exists() && outputFile.length() > 0
        Log.d(TAG, "Interpolación finalizada: ok=$ok, size=${outputFile.length()} bytes")
        return ok
    }

    // ──────────────────────────────────────────────────────────────────────────
    // INTERNAL HELPERS
    // ──────────────────────────────────────────────────────────────────────────

    private data class VideoMeta(
        val fps: Int, val width: Int, val height: Int, val durationMs: Long
    )

    private fun readVideoMeta(context: Context, uri: Uri): VideoMeta {
        // Leer FPS del track format (MediaExtractor) — mucho más fiable que METADATA_KEY_CAPTURE_FRAMERATE
        var extractorFps = -1
        try {
            val ex = MediaExtractor()
            ex.setDataSource(context, uri, null)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE))
                        extractorFps = fmt.getInteger(MediaFormat.KEY_FRAME_RATE)
                    break
                }
            }
            ex.release()
        } catch (_: Exception) {}

        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val fps = extractorFps.takeIf { it in 1..240 }
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.toInt()?.takeIf { it in 1..240 }
                ?: 30
            val w   = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.takeIf { it > 0 } ?: 1280
            val h   = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.takeIf { it > 0 } ?: 720
            val ms  = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            Log.d(TAG, "readVideoMeta: extractorFps=$extractorFps, final fps=$fps, ${w}x${h}, ${ms}ms")
            VideoMeta(fps, w, h, ms)
        } catch (_: Exception) {
            VideoMeta(30, 1280, 720, 0L)
        } finally {
            r.release()
        }
    }

    private fun createEncoder(w: Int, h: Int, fps: Int): MediaCodec {
        val pixels  = w.toLong() * h
        val bitRate = when {
            pixels >= 3840L * 2160 -> 12_000_000
            pixels >= 1920L * 1080 ->  6_000_000
            pixels >= 1280L *  720 ->  3_000_000
            else                   ->  1_500_000
        }
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_BIT_RATE,         bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE,       fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        return enc
    }

    /** Configura MediaExtractor + MediaCodec decoder para el track de video. */
    private fun prepareDecoder(
        context: Context, uri: Uri, extractor: MediaExtractor
    ): MediaCodec? {
        extractor.setDataSource(context, uri, null)
        var videoFmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                extractor.selectTrack(i)
                videoFmt = fmt
                break
            }
        }
        if (videoFmt == null) return null
        val mime    = videoFmt.getString(MediaFormat.KEY_MIME) ?: return null
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(videoFmt, null, null, 0)
        return decoder
    }

    // ── Convertir Image (YUV_420_888) → RGBA Mat sin pasar por JPEG ──────────

    private fun imageToMat(image: android.media.Image, targetW: Int, targetH: Int): Mat? {
        return try {
            val w = image.width
            val h = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yRowStride   = yPlane.rowStride
            val uvRowStride  = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            val nv21size = w * h * 3 / 2
            val nv21 = ByteArray(nv21size)

            // Copiar plano Y respetando rowStride
            val yBuf = yPlane.buffer
            for (row in 0 until h) {
                yBuf.position(row * yRowStride)
                yBuf.get(nv21, row * w, w)
            }

            // Copiar planos U/V intercalados como VU (NV21)
            val uvOffset = w * h
            val vBuf = vPlane.buffer
            val uBuf = uPlane.buffer

            if (uvPixelStride == 2) {
                // Semi-planar: V buffer ya tiene VU intercalado
                for (row in 0 until h / 2) {
                    vBuf.position(row * uvRowStride)
                    vBuf.get(nv21, uvOffset + row * w, w)
                }
            } else {
                // Planar: intercalar V y U manualmente
                var uvIdx = uvOffset
                for (row in 0 until h / 2) {
                    for (col in 0 until w / 2) {
                        nv21[uvIdx++] = vBuf.get(row * uvRowStride + col)
                        nv21[uvIdx++] = uBuf.get(row * uvRowStride + col)
                    }
                }
            }

            // NV21 → Mat RGBA vía OpenCV
            val nv21Mat = Mat(h + h / 2, w, CvType.CV_8UC1)
            nv21Mat.put(0, 0, nv21)
            val rgbaMat = Mat()
            org.opencv.imgproc.Imgproc.cvtColor(nv21Mat, rgbaMat, org.opencv.imgproc.Imgproc.COLOR_YUV2RGBA_NV21)
            nv21Mat.release()

            // Escalar si hace falta
            val result = if (rgbaMat.cols() == targetW && rgbaMat.rows() == targetH) {
                rgbaMat
            } else {
                val scaled = Mat()
                org.opencv.imgproc.Imgproc.resize(rgbaMat, scaled, Size(targetW.toDouble(), targetH.toDouble()))
                rgbaMat.release()
                scaled
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "imageToMat error: ${e.message}")
            null
        }
    }

    private fun matToBitmap(mat: Mat, w: Int, h: Int): Bitmap {
        val target = if (mat.cols() == w && mat.rows() == h) mat
        else {
            val r = Mat()
            org.opencv.imgproc.Imgproc.resize(mat, r, Size(w.toDouble(), h.toDouble()))
            r
        }
        val bmp = Bitmap.createBitmap(target.cols(), target.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(target, bmp)
        if (target !== mat) target.release()
        return bmp
    }

    // ── Optical flow a baja resolución (vectorizado, sin bucle pixel) ─────────

    /**
     * Calcula el optical flow Farneback entre [a] y [b].
     * Para reducir el tiempo de cómputo, ambos se escalan a ≤480×270 antes de Farneback,
     * y el flow resultante se re-escala en coordenadas al tamaño real.
     * El Mat devuelto tiene el mismo tamaño que [a] y [b], tipo CV_32FC2.
     */
    private fun computeFlowLowRes(a: Mat, b: Mat): Mat {
        val srcW  = a.cols(); val srcH = a.rows()
        val scale = minOf(FLOW_MAX_W.toDouble() / srcW, FLOW_MAX_H.toDouble() / srcH, 1.0)
        val flowW = (srcW * scale).toInt().coerceAtLeast(16)
        val flowH = (srcH * scale).toInt().coerceAtLeast(9)

        // Escalar ambos frames a resolución de flow
        val smallA = Mat(); val smallB = Mat()
        org.opencv.imgproc.Imgproc.resize(a, smallA, Size(flowW.toDouble(), flowH.toDouble()))
        org.opencv.imgproc.Imgproc.resize(b, smallB, Size(flowW.toDouble(), flowH.toDouble()))

        // Convertir a escala de grises
        val grayA = Mat(); val grayB = Mat()
        org.opencv.imgproc.Imgproc.cvtColor(smallA, grayA, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
        org.opencv.imgproc.Imgproc.cvtColor(smallB, grayB, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
        smallA.release(); smallB.release()

        // Farneback con parámetros conservadores (velocidad > calidad)
        // pyr_scale=0.5, levels=2, winsize=9, iterations=2, poly_n=5, poly_sigma=1.1
        val flowSmall = Mat()
        Video.calcOpticalFlowFarneback(grayA, grayB, flowSmall,
            0.5, 2, 9, 2, 5, 1.1, 0)
        grayA.release(); grayB.release()

        // Re-escalar el flow al tamaño original
        val flow = if (scale < 1.0) {
            val bigFlow = Mat()
            org.opencv.imgproc.Imgproc.resize(flowSmall, bigFlow, Size(srcW.toDouble(), srcH.toDouble()))
            flowSmall.release()
            // Escalar los valores de desplazamiento proporcionalmente
            Core.multiply(bigFlow, Scalar(1.0 / scale, 1.0 / scale), bigFlow)
            bigFlow
        } else {
            flowSmall
        }
        return flow
    }

    // ── Warp bidireccional + blend (vectorizado con OpenCV) ───────────────────

    /**
     * Genera el frame en la posición [alpha] ∈ (0,1) entre [a] y [b].
     * Construye los mapas de remapeo usando operaciones matriciales de OpenCV
     * (no hay bucles pixel a pixel → rendimiento de C++ nativo).
     */
    private fun warpAndBlend(
        a: Mat, b: Mat, flow: Mat,
        alpha: Float, outW: Int, outH: Int
    ): Bitmap {
        val h = a.rows(); val w = a.cols()

        // ── Construir coordenadas base (grid x,y) ─────────────────────────────
        // baseX(r,c) = c,  baseY(r,c) = r  (tipo CV_32F)
        val baseX = Mat(h, w, CvType.CV_32FC1)
        val baseY = Mat(h, w, CvType.CV_32FC1)
        for (row in 0 until h) {
            val rowDataX = FloatArray(w) { col -> col.toFloat() }
            val rowDataY = FloatArray(w) { row.toFloat() }
            baseX.put(row, 0, rowDataX)
            baseY.put(row, 0, rowDataY)
        }

        // Separar los canales del flow (dx, dy)
        val flowChannels = mutableListOf<Mat>()
        Core.split(flow, flowChannels)
        val flowDx = flowChannels[0]
        val flowDy = flowChannels[1]

        // mapXf = baseX + alpha * flowDx
        val mapXf = Mat(); val mapYf = Mat()
        Core.scaleAdd(flowDx, alpha.toDouble(), baseX, mapXf)
        Core.scaleAdd(flowDy, alpha.toDouble(), baseY, mapYf)

        // mapXb = baseX - (1-alpha) * flowDx
        val mapXb = Mat(); val mapYb = Mat()
        Core.scaleAdd(flowDx, -(1.0 - alpha).toDouble(), baseX, mapXb)
        Core.scaleAdd(flowDy, -(1.0 - alpha).toDouble(), baseY, mapYb)

        baseX.release(); baseY.release()
        flowDx.release(); flowDy.release()

        // Remap
        val warpedA = Mat(); val warpedB = Mat()
        org.opencv.imgproc.Imgproc.remap(a, warpedA, mapXf, mapYf,
            org.opencv.imgproc.Imgproc.INTER_LINEAR)
        org.opencv.imgproc.Imgproc.remap(b, warpedB, mapXb, mapYb,
            org.opencv.imgproc.Imgproc.INTER_LINEAR)
        mapXf.release(); mapYf.release(); mapXb.release(); mapYb.release()

        // Blend
        val blended = Mat()
        Core.addWeighted(warpedA, (1.0 - alpha).toDouble(), warpedB, alpha.toDouble(), 0.0, blended)
        warpedA.release(); warpedB.release()

        val result = matToBitmap(blended, outW, outH)
        blended.release()
        return result
    }

    // ── Detección de corte de escena (sampling vectorizado) ───────────────────

    /**
     * Devuelve true si la diferencia media de luminancia entre [a] y [b] supera el umbral.
     * Usa una versión muy reducida de ambos frames (32×18) para máxima velocidad.
     */
    private fun isSceneCut(a: Mat, b: Mat): Boolean {
        return try {
            val thumb = Size(32.0, 18.0)
            val ta = Mat(); val tb = Mat()
            org.opencv.imgproc.Imgproc.resize(a, ta, thumb)
            org.opencv.imgproc.Imgproc.resize(b, tb, thumb)
            val ga = Mat(); val gb = Mat()
            org.opencv.imgproc.Imgproc.cvtColor(ta, ga, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
            org.opencv.imgproc.Imgproc.cvtColor(tb, gb, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
            ta.release(); tb.release()
            val diff = Mat()
            Core.absdiff(ga, gb, diff)
            ga.release(); gb.release()
            val mean = Core.mean(diff)
            diff.release()
            mean.`val`[0] > 60.0
        } catch (_: Exception) { false }
    }

    // ── Escribir un frame Bitmap al encoder + muxer ───────────────────────────

    private fun writeFrameToEncoder(
        bmp:            Bitmap,
        encoder:        MediaCodec,
        muxer:          MediaMuxer,
        bufInfo:        MediaCodec.BufferInfo,
        w:              Int,
        h:              Int,
        presentationUs: Long,
        frameDurUs:     Long,
        onTrackAdded:   (Int) -> Unit,
        muxerTrack:     Int,
        muxerStarted:   Boolean
    ) {
        var track = muxerTrack
        var started = muxerStarted

        val inIdx = encoder.dequeueInputBuffer(TIMEOUT_US * 5)
        if (inIdx >= 0) {
            val buf = encoder.getInputBuffer(inIdx)!!
            buf.clear()
            val yuv = bitmapToNv12(bmp, w, h)
            buf.put(yuv)
            encoder.queueInputBuffer(inIdx, 0, yuv.size, presentationUs, 0)
        }

        // Drenar output del encoder
        var outIdx = encoder.dequeueOutputBuffer(bufInfo, 0)
        while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (track < 0) {
                    track = muxer.addTrack(encoder.outputFormat)
                    onTrackAdded(track)
                    muxer.start()
                    started = true
                }
            } else if (outIdx >= 0) {
                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                    started && track >= 0
                ) {
                    val buf = encoder.getOutputBuffer(outIdx)!!
                    muxer.writeSampleData(track, buf, bufInfo)
                }
                encoder.releaseOutputBuffer(outIdx, false)
            }
            outIdx = encoder.dequeueOutputBuffer(bufInfo, 0)
        }
    }

    private fun flushEncoder(
        encoder:        MediaCodec,
        muxer:          MediaMuxer,
        bufInfo:        MediaCodec.BufferInfo,
        onTrackAdded:   (Int) -> Unit,
        muxerTrackIn:   Int,
        muxerStartedIn: Boolean,
        presentationUs: Long
    ) {
        var muxerTrack   = muxerTrackIn
        var muxerStarted = muxerStartedIn

        val eosIdx = encoder.dequeueInputBuffer(TIMEOUT_US * 5)
        if (eosIdx >= 0) {
            encoder.queueInputBuffer(eosIdx, 0, 0, presentationUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        var eos = false
        while (!eos) {
            val outIdx = encoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerTrack < 0) {
                        muxerTrack = muxer.addTrack(encoder.outputFormat)
                        onTrackAdded(muxerTrack)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                outIdx >= 0 -> {
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        muxerStarted && muxerTrack >= 0
                    ) {
                        val buf = encoder.getOutputBuffer(outIdx)!!
                        muxer.writeSampleData(muxerTrack, buf, bufInfo)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }
        }
    }

    // ── Bitmap ARGB_8888 → NV12 (YUV420 semi-planar) ─────────────────────────

    private fun bitmapToNv12(bmp: Bitmap, w: Int, h: Int): ByteArray {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val nv12    = ByteArray(w * h * 3 / 2)
        val uvStart = w * h
        var uvIdx   = uvStart
        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = pixels[j * w + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8)  and 0xFF
                val b =  p         and 0xFF
                nv12[j * w + i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
                    .coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0 && uvIdx + 1 < nv12.size) {
                    nv12[uvIdx++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
                        .coerceIn(0, 255).toByte()
                    nv12[uvIdx++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
                        .coerceIn(0, 255).toByte()
                }
            }
        }
        return nv12
    }
}
