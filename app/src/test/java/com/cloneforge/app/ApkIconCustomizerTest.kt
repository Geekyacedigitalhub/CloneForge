package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater

class ApkIconCustomizerTest {

    @Test
    fun tintsRgbaLauncherPngInMipmapDirectory() {
        val unpackedDir = Files.createTempDirectory("cloneforge-icon-test-").toFile()
        try {
            val mipmapDir = unpackedDir.resolve("res/mipmap-hdpi").apply { mkdirs() }
            val iconFile = mipmapDir.resolve("ic_launcher.png")
            val originalPng = buildTestRgbaPng(r = 200, g = 50, b = 50, a = 255)
            iconFile.writeBytes(originalPng)

            val result = ApkIconCustomizer().customize(unpackedDir, IconTintOption.CYAN)

            assertEquals(1, result.iconsModified)
            val updatedPng = iconFile.readBytes()
            assertFalse(updatedPng.contentEquals(originalPng))
            assertTrue(updatedPng.size > 33)
        } finally {
            unpackedDir.deleteRecursively()
        }
    }

    @Test
    fun leavesIconsUntouchedWhenTintIsNone() {
        val unpackedDir = Files.createTempDirectory("cloneforge-icon-none-").toFile()
        try {
            val mipmapDir = unpackedDir.resolve("res/mipmap-hdpi").apply { mkdirs() }
            val iconFile = mipmapDir.resolve("ic_launcher.png")
            val originalPng = buildTestRgbaPng(r = 100, g = 150, b = 200, a = 255)
            iconFile.writeBytes(originalPng)

            val result = ApkIconCustomizer().customize(unpackedDir, IconTintOption.NONE)

            assertEquals(0, result.iconsModified)
            assertTrue(iconFile.readBytes().contentEquals(originalPng))
        } finally {
            unpackedDir.deleteRecursively()
        }
    }

    private fun buildTestRgbaPng(r: Int, g: Int, b: Int, a: Int): ByteArray {
        val ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(1) // width = 1
            putInt(1) // height = 1
            put(8) // bitDepth = 8
            put(6) // colorType = 6 (RGBA)
            put(0)
            put(0)
            put(0)
        }.array()

        // 1x1 scanline: filter byte 0 + R, G, B, A
        val rawScanline = byteArrayOf(0, r.toByte(), g.toByte(), b.toByte(), a.toByte())
        val deflater = Deflater()
        val idat = try {
            deflater.setInput(rawScanline)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(out, "IHDR", ihdr)
        writeChunk(out, "IDAT", idat)
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
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
}
