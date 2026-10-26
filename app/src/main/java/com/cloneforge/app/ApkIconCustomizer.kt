package com.cloneforge.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

enum class IconTintOption(
    val label: String,
    val red: Int,
    val green: Int,
    val blue: Int
) {
    NONE("Original", 0, 0, 0),
    CYAN("Cyan", 0, 188, 212),
    AMBER("Amber", 255, 179, 0),
    EMERALD("Emerald", 46, 204, 113),
    VIOLET("Violet", 155, 89, 182),
    ROSE("Rose", 233, 30, 99);

    companion object {
        fun fromLabel(label: String?): IconTintOption =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) || it.name.equals(label, ignoreCase = true) }
                ?: NONE
    }
}

data class IconCustomizationResult(
    val iconsModified: Int
)

/**
 * Applies a visual color tint to launcher PNG icons inside an unpacked APK
 * directory (`res/mipmap*` and `ic_launcher*.png`) using pure JVM/Android
 * byte manipulation so clones are visually distinct on the home screen.
 */
class ApkIconCustomizer {

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }

    fun customize(unpackedDir: File, tint: IconTintOption): IconCustomizationResult {
        if (tint == IconTintOption.NONE) return IconCustomizationResult(0)
        val resDir = File(unpackedDir, "res")
        if (!resDir.exists() || !resDir.isDirectory) return IconCustomizationResult(0)

        var modified = 0
        resDir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.name.endsWith(".png", ignoreCase = true) &&
                    !file.name.endsWith(".9.png", ignoreCase = true) &&
                    isLauncherIconCandidate(file, resDir)
            }
            .forEach { file ->
                val original = file.readBytes()
                val tinted = runCatching { tintPng(original, tint) }.getOrNull()
                if (tinted != null && !tinted.contentEquals(original)) {
                    file.writeBytes(tinted)
                    modified++
                }
            }

        return IconCustomizationResult(modified)
    }

    private fun isLauncherIconCandidate(file: File, resDir: File): Boolean {
        val relative = file.relativeTo(resDir).invariantSeparatorsPath.lowercase()
        val fileName = file.name.lowercase()
        return relative.startsWith("mipmap") ||
            fileName.contains("ic_launcher") ||
            fileName.contains("app_icon")
    }

    internal fun tintPng(pngBytes: ByteArray, tint: IconTintOption): ByteArray {
        if (tint == IconTintOption.NONE || pngBytes.size < 33) return pngBytes
        for (i in PNG_SIGNATURE.indices) {
            if (pngBytes[i] != PNG_SIGNATURE[i]) return pngBytes
        }

        data class PngChunk(val type: String, val data: ByteArray)

        val chunks = mutableListOf<PngChunk>()
        var offset = 8
        while (offset + 12 <= pngBytes.size) {
            val length = ByteBuffer.wrap(pngBytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            if (length < 0 || offset + 12 + length > pngBytes.size) return pngBytes
            val type = String(pngBytes, offset + 4, 4, Charsets.US_ASCII)
            val data = pngBytes.copyOfRange(offset + 8, offset + 8 + length)
            chunks += PngChunk(type, data)
            offset += 12 + length
            if (type == "IEND") break
        }

        val ihdr = chunks.firstOrNull { it.type == "IHDR" }?.data ?: return pngBytes
        if (ihdr.size < 13) return pngBytes

        val width = ByteBuffer.wrap(ihdr, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        val height = ByteBuffer.wrap(ihdr, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val bitDepth = ihdr[8].toInt() and 0xff
        val colorType = ihdr[9].toInt() and 0xff
        val compression = ihdr[10].toInt() and 0xff
        val filter = ihdr[11].toInt() and 0xff
        val interlace = ihdr[12].toInt() and 0xff

        if (width <= 0 || height <= 0 || compression != 0 || filter != 0 || interlace != 0) {
            return pngBytes
        }

        // Case 1: Indexed-color PNG (colorType == 3) -> tint the PLTE palette chunk directly
        if (colorType == 3) {
            var changed = false
            val updatedChunks = chunks.map { chunk ->
                if (chunk.type == "PLTE" && chunk.data.size >= 3) {
                    val plte = chunk.data.copyOf()
                    var i = 0
                    while (i + 2 < plte.size) {
                        val r = plte[i].toInt() and 0xff
                        val g = plte[i + 1].toInt() and 0xff
                        val b = plte[i + 2].toInt() and 0xff
                        plte[i] = blendChannel(r, tint.red).toByte()
                        plte[i + 1] = blendChannel(g, tint.green).toByte()
                        plte[i + 2] = blendChannel(b, tint.blue).toByte()
                        i += 3
                    }
                    changed = true
                    PngChunk("PLTE", plte)
                } else {
                    chunk
                }
            }
            return if (changed) encodePngChunks(updatedChunks.map { it.type to it.data }) else pngBytes
        }

        // Case 2: 8-bit RGB (colorType == 2) or RGBA (colorType == 6) -> tint scanlines in IDAT
        if (bitDepth == 8 && (colorType == 2 || colorType == 6)) {
            val bytesPerPixel = if (colorType == 6) 4 else 3
            val stride = width * bytesPerPixel
            val expectedRawSize = height * (1 + stride)

            val combinedIdat = ByteArrayOutputStream()
            chunks.filter { it.type == "IDAT" }.forEach { combinedIdat.write(it.data) }
            val decompressed = inflateBytes(combinedIdat.toByteArray(), expectedRawSize)
                ?: return pngBytes
            if (decompressed.size < expectedRawSize) return pngBytes

            val unfiltered = unfilterScanlines(decompressed, width, height, bytesPerPixel)
                ?: return pngBytes

            val tintedRaw = ByteArray(expectedRawSize)
            for (y in 0 until height) {
                val rawRowStart = y * (1 + stride)
                val pixRowStart = y * stride
                tintedRaw[rawRowStart] = 0 // Filter type 0 (None)
                var xByte = 0
                while (xByte < stride) {
                    val r = unfiltered[pixRowStart + xByte].toInt() and 0xff
                    val g = unfiltered[pixRowStart + xByte + 1].toInt() and 0xff
                    val b = unfiltered[pixRowStart + xByte + 2].toInt() and 0xff
                    val alpha = if (bytesPerPixel == 4) {
                        unfiltered[pixRowStart + xByte + 3].toInt() and 0xff
                    } else {
                        255
                    }

                    if (alpha > 0) {
                        tintedRaw[rawRowStart + 1 + xByte] = blendChannel(r, tint.red).toByte()
                        tintedRaw[rawRowStart + 1 + xByte + 1] = blendChannel(g, tint.green).toByte()
                        tintedRaw[rawRowStart + 1 + xByte + 2] = blendChannel(b, tint.blue).toByte()
                    } else {
                        tintedRaw[rawRowStart + 1 + xByte] = r.toByte()
                        tintedRaw[rawRowStart + 1 + xByte + 1] = g.toByte()
                        tintedRaw[rawRowStart + 1 + xByte + 2] = b.toByte()
                    }
                    if (bytesPerPixel == 4) {
                        tintedRaw[rawRowStart + 1 + xByte + 3] = alpha.toByte()
                    }
                    xByte += bytesPerPixel
                }
            }

            val recompressedIdat = deflateBytes(tintedRaw)
            val newChunks = mutableListOf<Pair<String, ByteArray>>()
            var idatInserted = false
            for (chunk in chunks) {
                if (chunk.type == "IDAT") {
                    if (!idatInserted) {
                        newChunks += "IDAT" to recompressedIdat
                        idatInserted = true
                    }
                } else {
                    newChunks += chunk.type to chunk.data
                }
            }
            return encodePngChunks(newChunks)
        }

        return pngBytes
    }

    private fun blendChannel(original: Int, tint: Int): Int =
        ((original * 60 + tint * 40) / 100).coerceIn(0, 255)

    private fun unfilterScanlines(
        raw: ByteArray,
        width: Int,
        height: Int,
        bpp: Int
    ): ByteArray? {
        val stride = width * bpp
        val out = ByteArray(height * stride)
        for (y in 0 until height) {
            val filterType = raw[y * (1 + stride)].toInt() and 0xff
            val inStart = y * (1 + stride) + 1
            val outStart = y * stride
            for (i in 0 until stride) {
                val x = raw[inStart + i].toInt() and 0xff
                val a = if (i >= bpp) out[outStart + i - bpp].toInt() and 0xff else 0
                val b = if (y > 0) out[(y - 1) * stride + i].toInt() and 0xff else 0
                val c = if (y > 0 && i >= bpp) out[(y - 1) * stride + i - bpp].toInt() and 0xff else 0
                val recon = when (filterType) {
                    0 -> x
                    1 -> (x + a) and 0xff
                    2 -> (x + b) and 0xff
                    3 -> (x + ((a + b) ushr 1)) and 0xff
                    4 -> (x + paethPredictor(a, b, c)) and 0xff
                    else -> return null
                }
                out[outStart + i] = recon.toByte()
            }
        }
        return out
    }

    private fun paethPredictor(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun inflateBytes(compressed: ByteArray, maxExpected: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream(maxExpected)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buffer, 0, count)
                }
            }
            out.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun deflateBytes(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        return try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size)
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count > 0) out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun encodePngChunks(chunks: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        for ((type, data) in chunks) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            val lenBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array()
            val crc = CRC32().apply {
                update(typeBytes)
                update(data)
            }.value
            val crcBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.toInt()).array()
            out.write(lenBytes)
            out.write(typeBytes)
            out.write(data)
            out.write(crcBytes)
        }
        return out.toByteArray()
    }
}
