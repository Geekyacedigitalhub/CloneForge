package com.cloneforge.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipOutputStream

class ApkFileVerifierTest {

    @Test
    fun verifiesReadableApkStructure() {
        val apk = File.createTempFile("cloneforge-verify-", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()

                zip.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
                zip.write(byteArrayOf(4, 5, 6))
                zip.closeEntry()
            }

            val verifier = ApkFileVerifier { data ->
                BinaryManifestInfo("com.example.clone", "Example", data.size)
            }

            val result = verifier.verify(apk)

            assertTrue(result.hasManifest)
            assertTrue(result.hasDex)
            assertTrue(result.dexCount == 1)
            assertTrue(result.verified)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun rejectsNonZipFile() {
        val apk = File.createTempFile("cloneforge-invalid-", ".apk")
        try {
            apk.writeText("not an apk")
            val result = ApkFileVerifier().verify(apk)
            assertFalse(result.verified)
            assertTrue(!result.error.isNullOrBlank())
        } finally {
            apk.delete()
        }
    }
}
