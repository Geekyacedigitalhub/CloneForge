package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SplitApkMergerTest {

    @Test
    fun mergesNativeLibrariesAssetsAndSecondaryDexFromSplits() {
        val baseApk = File.createTempFile("cloneforge-base-", ".apk")
        val abiSplit = File.createTempFile("cloneforge-split-abi-", ".apk")
        val featureSplit = File.createTempFile("cloneforge-split-feature-", ".apk")
        val mergedApk = File.createTempFile("cloneforge-merged-", ".apk")

        try {
            ZipOutputStream(baseApk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write("base-dex".toByteArray(Charsets.US_ASCII))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("resources.arsc"))
                zip.write(byteArrayOf(0x02, 0x00))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("META-INF/CERT.SF"))
                zip.write("sig".toByteArray(Charsets.US_ASCII))
                zip.closeEntry()
            }

            ZipOutputStream(abiSplit.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(0x03, 0x00))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libnative.so"))
                zip.write(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
                zip.closeEntry()
            }

            ZipOutputStream(featureSplit.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(0x03, 0x00))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write("feature-dex".toByteArray(Charsets.US_ASCII))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("assets/feature.json"))
                zip.write("{}".toByteArray(Charsets.US_ASCII))
                zip.closeEntry()
            }

            val result = SplitApkMerger().merge(
                baseApk = baseApk,
                splitApks = listOf(abiSplit, featureSplit),
                outputApk = mergedApk
            ).getOrThrow()

            assertEquals(2, result.splitsMerged)
            assertEquals(3, result.entriesAdded)

            ZipFile(mergedApk).use { zip ->
                assertNotNull(zip.getEntry("AndroidManifest.xml"))
                assertNotNull(zip.getEntry("classes.dex"))
                assertNotNull(zip.getEntry("classes2.dex"))
                assertNotNull(zip.getEntry("lib/arm64-v8a/libnative.so"))
                assertNotNull(zip.getEntry("assets/feature.json"))
                assertNull(zip.getEntry("META-INF/CERT.SF"))
            }
        } finally {
            baseApk.delete()
            abiSplit.delete()
            featureSplit.delete()
            mergedApk.delete()
        }
    }
}
