package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ClonePreflightServiceTest {

    @Test
    fun inspect_reportsSupportedForStandardSingleApk() {
        val apk = File.createTempFile("cloneforge-preflight-", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write("Lcom/example/app/MainActivity;".toByteArray(Charsets.US_ASCII))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("resources.arsc"))
                zip.write(byteArrayOf(0x02, 0x00))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("assets/config.txt"))
                zip.write("pkg=com.example.app".toByteArray(Charsets.US_ASCII))
                zip.closeEntry()
            }

            val preflight = ClonePreflightService().inspect(apk, "com.example.app").getOrThrow()

            assertTrue(preflight.compatibility.supported)
            assertEquals(1, preflight.referenceScan.assetReferences)
            assertEquals(1, preflight.dexReferenceScan.totalReferences)
            assertEquals(2, preflight.compatibility.packageReferenceCount)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun inspect_blocksSplitApkArchive() {
        val apk = File.createTempFile("cloneforge-preflight-split-", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write("Lcom/example/app/MainActivity;".toByteArray(Charsets.US_ASCII))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("split_config.arm64_v8a.apk"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }

            val preflight = ClonePreflightService().inspect(apk, "com.example.app").getOrThrow()

            assertFalse(preflight.compatibility.supported)
            assertTrue(preflight.compatibility.issues.any { it.contains("split APKs") })
        } finally {
            apk.delete()
        }
    }
}
