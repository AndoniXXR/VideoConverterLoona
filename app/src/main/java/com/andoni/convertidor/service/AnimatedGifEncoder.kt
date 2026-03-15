package com.andoni.convertidor.service

import android.graphics.Bitmap
import android.graphics.Color
import java.io.OutputStream

/**
 * Encoder de GIF animado — escribe formato GIF89a frame a frame a un OutputStream.
 *
 * Basado en el algoritmo clásico de NeuQuant + LZW para GIF.
 * Diseñado para uso streaming: se añaden frames uno a uno y se escriben al disco inmediatamente.
 *
 * Uso:
 *   val encoder = AnimatedGifEncoder()
 *   encoder.start(outputStream)
 *   encoder.setRepeat(0) // 0 = infinito
 *   encoder.setDelay(100) // ms entre frames
 *   encoder.addFrame(bitmap1)
 *   encoder.addFrame(bitmap2)
 *   encoder.finish()
 */
class AnimatedGifEncoder {

    private var width = 0
    private var height = 0
    private var delay = 100        // ms entre frames
    private var repeat = -1        // -1 = no repeat, 0 = infinito, N = N veces
    private var started = false
    private var out: OutputStream? = null
    private var firstFrame = true
    private var sizeSet = false

    private var colorTab: ByteArray? = null   // paleta activa (256 * 3 bytes)
    private var indexedPixels: ByteArray? = null
    private var colorDepth = 0
    private var palSize = 7        // 2^(palSize+1) = 256

    private var sample = 10        // Calidad NeuQuant: 1 (mejor) – 30 (más rápido)
    private var dispose = -1       // Disposal code (-1 = usar default)
    private var transparent: Int? = null

    fun setDelay(ms: Int): AnimatedGifEncoder { delay = ms; return this }
    fun setRepeat(count: Int): AnimatedGifEncoder { repeat = count; return this }
    fun setQuality(quality: Int): AnimatedGifEncoder { sample = quality.coerceIn(1, 30); return this }
    fun setTransparent(color: Int?): AnimatedGifEncoder { transparent = color; return this }
    fun setDispose(code: Int): AnimatedGifEncoder { dispose = code; return this }

    fun setSize(w: Int, h: Int): AnimatedGifEncoder {
        width = w; height = h; sizeSet = true; return this
    }

    fun start(os: OutputStream): Boolean {
        out = os
        started = true
        firstFrame = true
        writeString("GIF89a")
        return true
    }

    fun addFrame(bitmap: Bitmap): Boolean {
        if (!started) return false
        val bmp = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else bitmap

        if (!sizeSet) {
            width = bmp.width; height = bmp.height; sizeSet = true
        }

        val pixels = getImagePixels(bmp)
        analyzePixels(pixels)

        if (firstFrame) {
            writeLSD()
            writePalette()
            if (repeat >= 0) writeNetscapeExt()
        }

        writeGraphicCtrlExt()
        writeImageDesc(firstFrame)
        if (!firstFrame) writePalette()
        writePixels()

        firstFrame = false
        if (bmp !== bitmap) bmp.recycle()
        return true
    }

    fun finish(): Boolean {
        if (!started) return false
        started = false
        out?.write(0x3B) // GIF Trailer
        out?.flush()
        return true
    }

    // ── Pixel extraction ─────────────────────────────────────────────────────

    private fun getImagePixels(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return pixels
    }

    // ── Color quantization (Median Cut simplified) ───────────────────────────

    private fun analyzePixels(pixels: IntArray) {
        val nPix = pixels.size
        val nqPixels = ByteArray(nPix * 3)
        for (i in 0 until nPix) {
            val c = pixels[i]
            nqPixels[i * 3]     = Color.red(c).toByte()
            nqPixels[i * 3 + 1] = Color.green(c).toByte()
            nqPixels[i * 3 + 2] = Color.blue(c).toByte()
        }

        val nq = NeuQuant(nqPixels, nPix * 3, sample)
        colorTab = nq.process()
        colorDepth = 8
        palSize = 7

        // Map pixels to palette indices
        indexedPixels = ByteArray(nPix)
        for (i in 0 until nPix) {
            indexedPixels!![i] = nq.map(
                Color.red(pixels[i]),
                Color.green(pixels[i]),
                Color.blue(pixels[i])
            ).toByte()
        }
    }

    // ── GIF block writers ────────────────────────────────────────────────────

    private fun writeLSD() {
        val os = out ?: return
        // Logical Screen Descriptor
        writeShort(width)
        writeShort(height)
        // Packed: GCT flag=1, color res=7, sort=0, GCT size=7 (256 colors)
        os.write(0xF0 or palSize)
        os.write(0) // bg color index
        os.write(0) // pixel aspect ratio
    }

    private fun writePalette() {
        val os = out ?: return
        val tab = colorTab ?: return
        os.write(tab, 0, tab.size)
        // Pad to 256*3
        val pad = 3 * 256 - tab.size
        for (i in 0 until pad) os.write(0)
    }

    private fun writeNetscapeExt() {
        val os = out ?: return
        os.write(0x21)         // Extension Introducer
        os.write(0xFF)         // Application Extension
        os.write(11)           // Block size
        writeString("NETSCAPE2.0")
        os.write(3)            // Sub-block size
        os.write(1)            // Loop sub-block id
        writeShort(repeat)     // Loop count (0 = infinite)
        os.write(0)            // Block terminator
    }

    private fun writeGraphicCtrlExt() {
        val os = out ?: return
        os.write(0x21)   // Extension Introducer
        os.write(0xF9)   // Graphic Control Label
        os.write(4)      // Block size

        val dispVal = if (dispose >= 0) (dispose and 7) shl 2 else 0
        val transp = if (transparent != null) 1 else 0
        os.write(dispVal or transp)  // packed

        val d = delay / 10   // GIF delay is in centiseconds
        writeShort(d)
        os.write(transparent?.let { findClosest(it) } ?: 0) // transparent color index
        os.write(0) // block terminator
    }

    private fun writeImageDesc(isFirst: Boolean) {
        val os = out ?: return
        os.write(0x2C) // Image Separator
        writeShort(0)  // left
        writeShort(0)  // top
        writeShort(width)
        writeShort(height)
        if (isFirst) {
            os.write(0) // No LCT for first frame (use GCT)
        } else {
            os.write(0x80 or palSize) // LCT flag + size
        }
    }

    private fun writePixels() {
        val encoder = LZWEncoder(width, height, indexedPixels!!, colorDepth)
        encoder.encode(out!!)
    }

    private fun writeShort(value: Int) {
        val os = out ?: return
        os.write(value and 0xFF)
        os.write((value shr 8) and 0xFF)
    }

    private fun writeString(s: String) {
        val os = out ?: return
        for (c in s) os.write(c.code)
    }

    private fun findClosest(color: Int): Int {
        val tab = colorTab ?: return 0
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        var minDist = Int.MAX_VALUE
        var minIdx = 0
        var idx = 0
        while (idx < tab.size - 2) {
            val dr = r - (tab[idx].toInt() and 0xFF)
            val dg = g - (tab[idx + 1].toInt() and 0xFF)
            val db = b - (tab[idx + 2].toInt() and 0xFF)
            val dist = dr * dr + dg * dg + db * db
            if (dist < minDist) {
                minDist = dist
                minIdx = idx / 3
            }
            idx += 3
        }
        return minIdx
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// NeuQuant Neural-Net Quantization Algorithm
// Based on: Anthony Dekker, 1994 — adapted to Kotlin
// ═══════════════════════════════════════════════════════════════════════════════

private class NeuQuant(private val thePicture: ByteArray, private val lengthCount: Int, private val sampleFac: Int) {

    companion object {
        private const val NET_SIZE = 256
        private const val PRIME_1 = 499
        private const val PRIME_2 = 491
        private const val PRIME_3 = 487
        private const val PRIME_4 = 503

        private const val MIN_PICTURE_BYTES = 3 * PRIME_4
        private const val MAX_NET_POS = NET_SIZE - 1
        private const val NET_BIAS_SHIFT = 4
        private const val N_CYCLES = 100

        private const val INT_BIAS_SHIFT = 16
        private const val INT_BIAS = 1 shl INT_BIAS_SHIFT
        private const val GAMMA_SHIFT = 10
        private const val BETA_SHIFT = 10
        private const val BETA = INT_BIAS shr BETA_SHIFT
        private const val BETA_GAMMA = INT_BIAS shl (GAMMA_SHIFT - BETA_SHIFT)

        private const val INIT_RAD = NET_SIZE shr 3
        private const val RADIUS_BIAS_SHIFT = 6
        private const val RADIUS_BIAS = 1 shl RADIUS_BIAS_SHIFT
        private const val INIT_RADIUS = INIT_RAD * RADIUS_BIAS
        private const val RADIUS_DEC = 30

        private const val ALPHA_DEC = 30
        private const val ALPHA_BIAS_SHIFT = 10
        private const val INIT_ALPHA = 1 shl ALPHA_BIAS_SHIFT
    }

    private val network = Array(NET_SIZE) { IntArray(4) }
    private val netIndex = IntArray(256)
    private val bias = IntArray(NET_SIZE)
    private val freq = IntArray(NET_SIZE)
    private val radPower = IntArray(INIT_RAD)

    init {
        for (i in 0 until NET_SIZE) {
            val v = (i shl (NET_BIAS_SHIFT + 8)) / NET_SIZE
            network[i][0] = v
            network[i][1] = v
            network[i][2] = v
            freq[i] = INT_BIAS / NET_SIZE
            bias[i] = 0
        }
    }

    fun process(): ByteArray {
        learn()
        unbiasNet()
        buildIndex()
        val map = ByteArray(NET_SIZE * 3)
        for (i in 0 until NET_SIZE) {
            map[i * 3]     = network[i][0].toByte()
            map[i * 3 + 1] = network[i][1].toByte()
            map[i * 3 + 2] = network[i][2].toByte()
        }
        return map
    }

    fun map(r: Int, g: Int, b: Int): Int {
        var bestd = 1000
        var best = 0
        val bi = netIndex[g.coerceIn(0, 255)]
        var i = bi
        var j = bi - 1

        while (i < NET_SIZE || j >= 0) {
            if (i < NET_SIZE) {
                val n = network[i]
                val gdist = n[1] - g
                if (gdist >= bestd) {
                    i = NET_SIZE
                } else {
                    var dist = if (gdist < 0) -gdist else gdist
                    var a = n[0] - r; if (a < 0) a = -a; dist += a
                    if (dist < bestd) {
                        a = n[2] - b; if (a < 0) a = -a; dist += a
                        if (dist < bestd) { bestd = dist; best = i }
                    }
                    i++
                }
            }
            if (j >= 0) {
                val n = network[j]
                val gdist = g - n[1]
                if (gdist >= bestd) {
                    j = -1
                } else {
                    var dist = if (gdist < 0) -gdist else gdist
                    var a = n[0] - r; if (a < 0) a = -a; dist += a
                    if (dist < bestd) {
                        a = n[2] - b; if (a < 0) a = -a; dist += a
                        if (dist < bestd) { bestd = dist; best = j }
                    }
                    j--
                }
            }
        }
        return best
    }

    private fun learn() {
        if (lengthCount < MIN_PICTURE_BYTES) return
        val alphadec = 30 + ((sampleFac - 1) / 3)
        val samplepixels = lengthCount / (3 * sampleFac)
        var alpha = INIT_ALPHA
        var radius = INIT_RADIUS
        var rad = radius shr RADIUS_BIAS_SHIFT
        if (rad <= 1) rad = 0
        for (i in 0 until rad) radPower[i] = alpha * ((rad * rad - i * i) * RADIUS_BIAS / (rad * rad))

        val step = when {
            lengthCount < MIN_PICTURE_BYTES -> 3
            lengthCount % PRIME_1 != 0 -> 3 * PRIME_1
            lengthCount % PRIME_2 != 0 -> 3 * PRIME_2
            lengthCount % PRIME_3 != 0 -> 3 * PRIME_3
            else -> 3 * PRIME_4
        }

        var pix = 0
        for (i in 0 until samplepixels) {
            val r = (thePicture[pix].toInt() and 0xFF) shl NET_BIAS_SHIFT
            val g = (thePicture[pix + 1].toInt() and 0xFF) shl NET_BIAS_SHIFT
            val b = (thePicture[pix + 2].toInt() and 0xFF) shl NET_BIAS_SHIFT

            val j = contest(r, g, b)
            alterSingle(alpha, j, r, g, b)
            if (rad != 0) alterNeigh(rad, j, r, g, b)

            pix += step
            if (pix >= lengthCount) pix -= lengthCount

            if (i % (samplepixels / N_CYCLES.coerceAtLeast(1)).coerceAtLeast(1) == 0) {
                alpha -= alpha / alphadec
                radius -= radius / RADIUS_DEC
                rad = radius shr RADIUS_BIAS_SHIFT
                if (rad <= 1) rad = 0
                for (k in 0 until rad) radPower[k] = alpha * ((rad * rad - k * k) * RADIUS_BIAS / (rad * rad))
            }
        }
    }

    private fun contest(r: Int, g: Int, b: Int): Int {
        var bestd = Int.MAX_VALUE.toLong()
        var bestbiasd = bestd
        var bestpos = -1
        var bestbiaspos = bestpos

        for (i in 0 until NET_SIZE) {
            val n = network[i]
            val dist = (Math.abs(n[0] - r) + Math.abs(n[1] - g) + Math.abs(n[2] - b)).toLong()
            if (dist < bestd) { bestd = dist; bestpos = i }
            val biasDist = dist - (bias[i] shr (INT_BIAS_SHIFT - NET_BIAS_SHIFT)).toLong()
            if (biasDist < bestbiasd) { bestbiasd = biasDist; bestbiaspos = i }
            val betaFreq = freq[i] shr BETA_SHIFT
            freq[i] -= betaFreq
            bias[i] += betaFreq shl GAMMA_SHIFT
        }
        freq[bestpos] += BETA
        bias[bestpos] -= BETA_GAMMA
        return bestbiaspos
    }

    private fun alterSingle(alpha: Int, i: Int, r: Int, g: Int, b: Int) {
        val n = network[i]
        n[0] -= (alpha * (n[0] - r)) / INIT_ALPHA
        n[1] -= (alpha * (n[1] - g)) / INIT_ALPHA
        n[2] -= (alpha * (n[2] - b)) / INIT_ALPHA
    }

    private fun alterNeigh(rad: Int, i: Int, r: Int, g: Int, b: Int) {
        val lo = (i - rad).coerceAtLeast(0)
        val hi = (i + rad).coerceAtMost(NET_SIZE - 1)
        var j = i + 1
        var k = i - 1
        var m = 1
        while (j <= hi || k >= lo) {
            if (m >= radPower.size) break
            val a = radPower[m++]
            if (j <= hi) {
                val n = network[j++]
                n[0] -= (a * (n[0] - r)) / (INIT_ALPHA * RADIUS_BIAS)
                n[1] -= (a * (n[1] - g)) / (INIT_ALPHA * RADIUS_BIAS)
                n[2] -= (a * (n[2] - b)) / (INIT_ALPHA * RADIUS_BIAS)
            }
            if (k >= lo) {
                val n = network[k--]
                n[0] -= (a * (n[0] - r)) / (INIT_ALPHA * RADIUS_BIAS)
                n[1] -= (a * (n[1] - g)) / (INIT_ALPHA * RADIUS_BIAS)
                n[2] -= (a * (n[2] - b)) / (INIT_ALPHA * RADIUS_BIAS)
            }
        }
    }

    private fun unbiasNet() {
        for (i in 0 until NET_SIZE) {
            network[i][0] = (network[i][0] + (1 shl (NET_BIAS_SHIFT - 1))) shr NET_BIAS_SHIFT
            network[i][1] = (network[i][1] + (1 shl (NET_BIAS_SHIFT - 1))) shr NET_BIAS_SHIFT
            network[i][2] = (network[i][2] + (1 shl (NET_BIAS_SHIFT - 1))) shr NET_BIAS_SHIFT
            network[i][3] = i
        }
    }

    private fun buildIndex() {
        // Sort network by green component (selection sort)
        var previouscol = 0
        var startpos = 0
        for (i in 0 until NET_SIZE) {
            var smallpos = i
            var smallval = network[i][1]
            for (j in i + 1 until NET_SIZE) {
                if (network[j][1] < smallval) {
                    smallpos = j; smallval = network[j][1]
                }
            }
            if (i != smallpos) {
                val tmp = network[i]; network[i] = network[smallpos]; network[smallpos] = tmp
            }
            if (smallval != previouscol) {
                netIndex[previouscol.coerceIn(0, 255)] = (startpos + i) shr 1
                for (k in previouscol + 1 until smallval) {
                    netIndex[k.coerceIn(0, 255)] = i
                }
                previouscol = smallval
                startpos = i
            }
        }
        netIndex[previouscol.coerceIn(0, 255)] = (startpos + MAX_NET_POS) shr 1
        for (k in previouscol + 1..255) {
            netIndex[k] = MAX_NET_POS
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LZW Encoder for GIF
// ═══════════════════════════════════════════════════════════════════════════════

private class LZWEncoder(
    private val imgW: Int,
    private val imgH: Int,
    private val pixels: ByteArray,
    private val colorDepth: Int
) {
    companion object {
        private const val EOF = -1
        private const val BITS = 12
        private const val HSIZE = 5003
    }

    private val initCodeSize = colorDepth.coerceAtLeast(2)

    // GIF-specific
    private var nBits = 0
    private var maxBits = BITS
    private var maxCode = 0
    private var maxMaxCode = 1 shl BITS

    private var clearCode = 0
    private var eofCode = 0
    private var freeEntry = 0

    private var clearFlag = false
    private var globalInitBits = 0

    // Block output
    private var curAccum = 0
    private var curBits = 0
    private val accum = ByteArray(256)
    private var aCount = 0

    // LZW tables
    private val htab = IntArray(HSIZE)
    private val codeTab = IntArray(HSIZE)

    private var remaining = 0
    private var curPixel = 0

    fun encode(os: OutputStream) {
        os.write(initCodeSize) // initial code size byte

        remaining = imgW * imgH
        curPixel = 0

        compress(initCodeSize + 1, os)

        os.write(0) // block terminator
    }

    private fun compress(initBits: Int, os: OutputStream) {
        globalInitBits = initBits
        clearFlag = false
        nBits = globalInitBits
        maxCode = maxCode(nBits)
        clearCode = 1 shl (initBits - 1)
        eofCode = clearCode + 1
        freeEntry = clearCode + 2
        aCount = 0

        var ent = nextPixel()

        var hshift = 0
        var fcode = HSIZE
        while (fcode < 65536) { hshift++; fcode *= 2 }
        hshift = 8 - hshift

        htab.fill(-1)
        output(clearCode, os)

        var c: Int
        outerLoop@ while (true) {
            c = nextPixel()
            if (c == EOF) break

            fcode = (c shl maxBits) + ent
            var i = (c shl hshift) xor ent

            if (htab[i] == fcode) {
                ent = codeTab[i]
                continue
            } else if (htab[i] >= 0) {
                var disp = HSIZE - i
                if (i == 0) disp = 1
                do {
                    i -= disp
                    if (i < 0) i += HSIZE
                    if (htab[i] == fcode) { ent = codeTab[i]; continue@outerLoop }
                } while (htab[i] >= 0)
            }

            output(ent, os)
            ent = c
            if (freeEntry < maxMaxCode) {
                codeTab[i] = freeEntry++
                htab[i] = fcode
            } else {
                clearBlock(os)
            }
        }

        output(ent, os)
        output(eofCode, os)

        // Flush remaining bits and buffered bytes
        if (curBits > 0) {
            charOut((curAccum and 0xFF).toByte(), os)
        }
        flushChar(os)
    }

    private fun maxCode(nBits: Int): Int = (1 shl nBits) - 1

    private fun nextPixel(): Int {
        if (remaining == 0) return EOF
        remaining--
        return pixels[curPixel++].toInt() and 0xFF
    }

    private fun output(code: Int, os: OutputStream) {
        curAccum = curAccum and ((1 shl curBits) - 1)
        curAccum = if (curBits > 0) curAccum or (code shl curBits) else code
        curBits += nBits

        while (curBits >= 8) {
            charOut((curAccum and 0xFF).toByte(), os)
            curAccum = curAccum shr 8
            curBits -= 8
        }

        if (freeEntry > maxCode || clearFlag) {
            if (clearFlag) {
                nBits = globalInitBits
                maxCode = maxCode(nBits)
                clearFlag = false
            } else {
                nBits++
                maxCode = if (nBits == maxBits) maxMaxCode else maxCode(nBits)
            }
        }
    }

    private fun clearBlock(os: OutputStream) {
        htab.fill(-1)
        freeEntry = clearCode + 2
        clearFlag = true
        output(clearCode, os)
    }

    private fun charOut(c: Byte, os: OutputStream) {
        accum[aCount++] = c
        if (aCount >= 254) flushChar(os)
    }

    private fun flushChar(os: OutputStream) {
        if (aCount > 0) {
            os.write(aCount)
            os.write(accum, 0, aCount)
            aCount = 0
        }
    }
}
