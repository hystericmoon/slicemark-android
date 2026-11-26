package com.example.media

import android.graphics.Bitmap
import android.graphics.Color
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Lightweight, zero-dependency pure-Kotlin Animated GIF89a encoder with
 * 256-color studio-safe quantized palette (6x6x6 RGB cube + 40 monochrome grays) & LZW compression.
 */
class GifEncoder(private val outputFile: File) {

    private var out: OutputStream? = null
    private var width = 0
    private var height = 0
    private var started = false
    private val palette = buildStudioPalette()

    fun start(w: Int, h: Int) {
        width = w
        height = h
        val stream = BufferedOutputStream(FileOutputStream(outputFile), 32768)
        out = stream
        started = true

        // Header: GIF89a
        stream.write("GIF89a".toByteArray())
        // Logical Screen Descriptor
        writeShort(stream, width)
        writeShort(stream, height)
        stream.write(0xF7) // Global Color Table Flag=1, ColorRes=7, Sort=0, Size=7 (256 colors)
        stream.write(0)    // Background Color Index
        stream.write(0)    // Pixel Aspect Ratio
        // Global Color Table (256 * 3 = 768 bytes)
        stream.write(palette)
        // Netscape 2.0 Infinite Loop Application Extension
        stream.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        stream.write("NETSCAPE2.0".toByteArray())
        stream.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
    }

    fun addFrame(bitmap: Bitmap, delayMs: Int) {
        val stream = out ?: return
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else bitmap

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled != bitmap) scaled.recycle()

        val indexed = ByteArray(pixels.size)
        for (i in pixels.indices) {
            indexed[i] = quantizePixel(pixels[i]).toByte()
        }

        // Graphic Control Extension
        val delayCs = (delayMs / 10).coerceIn(2, 600)
        stream.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00))
        writeShort(stream, delayCs)
        stream.write(byteArrayOf(0x00, 0x00))

        // Image Descriptor
        stream.write(0x2C)
        writeShort(stream, 0)
        writeShort(stream, 0)
        writeShort(stream, width)
        writeShort(stream, height)
        stream.write(0x00) // Use Global Color Table

        // LZW Minimum Code Size = 8
        writeLzwData(stream, indexed)
    }

    fun finish() {
        val stream = out ?: return
        if (started) {
            stream.write(0x3B) // GIF Trailer
            stream.flush()
        }
        runCatching { stream.close() }
        out = null
        started = false
    }

    private fun buildStudioPalette(): ByteArray {
        val pal = ByteArray(256 * 3)
        var idx = 0
        // 0..215: 6x6x6 RGB cube
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    pal[idx++] = (r * 51).toByte()
                    pal[idx++] = (g * 51).toByte()
                    pal[idx++] = (b * 51).toByte()
                }
            }
        }
        // 216..255: 40 monochrome studio grayscale steps
        for (k in 0 until 40) {
            val v = ((k * 255) / 39).toByte()
            pal[idx++] = v
            pal[idx++] = v
            pal[idx++] = v
        }
        return pal
    }

    private fun quantizePixel(argb: Int): Int {
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        if (maxC - minC <= 10) {
            val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            return 216 + ((gray * 39) / 255).coerceIn(0, 39)
        }
        val ri = ((r * 5 + 127) / 255).coerceIn(0, 5)
        val gi = ((g * 5 + 127) / 255).coerceIn(0, 5)
        val bi = ((b * 5 + 127) / 255).coerceIn(0, 5)
        return ri * 36 + gi * 6 + bi
    }

    private fun writeLzwData(stream: OutputStream, pixels: ByteArray) {
        val minCodeSize = 8
        stream.write(minCodeSize)

        val clearCode = 1 shl minCodeSize // 256
        val eoiCode = clearCode + 1       // 257
        var codeSize = minCodeSize + 1
        var nextCode = eoiCode + 1

        // Compact hash-table LZW dictionary (size 4096)
        val tablePrefix = IntArray(4096) { -1 }
        val tableSuffix = IntArray(4096)
        val hashKey = IntArray(8192) { -1 }
        val hashVal = IntArray(8192)

        fun resetTable() {
            hashKey.fill(-1)
            codeSize = minCodeSize + 1
            nextCode = eoiCode + 1
        }

        val blockBuf = ByteArray(255)
        var blockLen = 0
        var bitAcc = 0
        var bitCount = 0

        fun flushBlock() {
            if (blockLen > 0) {
                stream.write(blockLen)
                stream.write(blockBuf, 0, blockLen)
                blockLen = 0
            }
        }

        fun emitCode(code: Int) {
            bitAcc = bitAcc or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                blockBuf[blockLen++] = (bitAcc and 0xFF).toByte()
                if (blockLen == 255) flushBlock()
                bitAcc = bitAcc ushr 8
                bitCount -= 8
            }
        }

        emitCode(clearCode)
        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val k = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            var slot = ((key * 2654435761L).toInt() ushr 19) and 8191
            var found = -1
            while (hashKey[slot] != -1) {
                if (hashKey[slot] == key) {
                    found = hashVal[slot]
                    break
                }
                slot = (slot + 1) and 8191
            }

            if (found != -1) {
                prefix = found
            } else {
                emitCode(prefix)
                if (nextCode < 4096) {
                    tablePrefix[nextCode] = prefix
                    tableSuffix[nextCode] = k
                    hashKey[slot] = key
                    hashVal[slot] = nextCode
                    nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                    }
                } else {
                    emitCode(clearCode)
                    resetTable()
                }
                prefix = k
            }
        }
        emitCode(prefix)
        emitCode(eoiCode)

        if (bitCount > 0) {
            blockBuf[blockLen++] = (bitAcc and 0xFF).toByte()
        }
        flushBlock()
        stream.write(0) // Block terminator
    }

    private fun writeShort(stream: OutputStream, v: Int) {
        stream.write(v and 0xFF)
        stream.write((v ushr 8) and 0xFF)
    }
}
