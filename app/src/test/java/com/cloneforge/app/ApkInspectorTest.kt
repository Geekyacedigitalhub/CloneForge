package com.cloneforge.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipOutputStream

class ApkInspectorTest {

    @Test
    fun detectsDexAndManifestInZipApk() {
        val apk = File.createTempFile("cloneforge-test-", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()

                zip.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
                zip.write(byteArrayOf(4, 5, 6))
                zip.closeEntry()
            }

            val inspection = ApkInspector().inspect(apk)

            assertTrue(inspection.hasManifest)
            assertTrue(inspection.dexCount == 1)
        } finally {
            apk.delete()
        }
    }
}
