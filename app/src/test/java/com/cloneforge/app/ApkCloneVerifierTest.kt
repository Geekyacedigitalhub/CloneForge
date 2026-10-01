package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipOutputStream

class ApkCloneVerifierTest {

    @Test
    fun descriptorVerificationRequiresNoOriginalReferences() {
        val verifier = DexDescriptorVerifier()
        val result = verifier.verifyBytes(
            "Lcom/example/clone1/Foo;".toByteArray(),
            "com.example",
            "com.example.clone1"
        )

        assertEquals(0, result.sourceOccurrences)
        assertTrue(result.cloneOccurrences > 0)
        assertTrue(result.verified)
    }

    @Test
    fun verify_reportsMissingManifestAndDex() {
        val apk = createZip("resources.arsc" to byteArrayOf(1, 2, 3))

        try {
            val result = ApkCloneVerifier().verify(
                apkFile = apk,
                sourcePackage = "com.example.source",
                clonePackage = "com.example.source.clone1"
            )

            assertFalse(result.hasManifest)
            assertFalse(result.hasDex)
            assertTrue(result.hasResources)
            assertEquals(0, result.dexFilesChecked)
            assertFalse(result.verified)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun verify_checksEveryDexFileAndCountsDescriptors() {
        val source = "Lcom/example/source;"
        val clone = "Lcom/example/source/clone1;"

        val apk = createZip(
            "classes.dex" to "$clone $clone".toByteArray(Charsets.US_ASCII),
            "classes2.dex" to clone.toByteArray(Charsets.US_ASCII),
            "classes3.dex" to source.toByteArray(Charsets.US_ASCII),
            "classes10.dex" to clone.toByteArray(Charsets.US_ASCII),
            "not-dex.bin" to source.toByteArray(Charsets.US_ASCII)
        )

        try {
            val result = ApkCloneVerifier().verify(
                apkFile = apk,
                sourcePackage = "com.example.source",
                clonePackage = "com.example.source.clone1"
            )

            assertTrue(result.hasDex)
            assertEquals(4, result.dexFilesChecked)
            assertEquals(1, result.sourceDescriptorOccurrences)
            assertEquals(4, result.cloneDescriptorOccurrences)
            assertFalse(result.verified)
        } finally {
            apk.delete()
        }
    }

    @Test(expected = java.util.zip.ZipException::class)
    fun verify_rejectsMalformedApk() {
        val apk = File.createTempFile("cloneforge-verifier-", ".apk")
        apk.writeBytes("not a zip".toByteArray())

        try {
            ApkCloneVerifier().verify(
                apkFile = apk,
                sourcePackage = "com.example.source",
                clonePackage = "com.example.source.clone1"
            )
        } finally {
            apk.delete()
        }
    }

    private fun createZip(vararg entries: Pair<String, ByteArray>): File {
        val apk = File.createTempFile("cloneforge-verifier-", ".apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return apk
    }
}
