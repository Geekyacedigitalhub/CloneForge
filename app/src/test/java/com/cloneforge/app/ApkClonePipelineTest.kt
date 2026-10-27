package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkClonePipelineTest {

    @Test
    fun buildUnsignedClone_rewritesManifestDexAndTextReferences() {
        val fixturePath = System.getProperty("cloneforge.fixtureApk")
            ?: error("cloneforge.fixtureApk test property is not configured.")
        val sourceApk = File(fixturePath)
        assertTrue("Fixture APK must exist: $fixturePath", sourceApk.isFile)

        val workspaceRoot = Files.createTempDirectory("cloneforge-pipeline-").toFile()
        try {
            val splitNative = File(workspaceRoot, "split_config.arm64_v8a.apk")
            ZipOutputStream(FileOutputStream(splitNative)).use { zip ->
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libnative.so"))
                zip.write(ByteArray(256) { 0x7f })
                zip.closeEntry()
            }
            val mergedSource = File(workspaceRoot, "merged-source.apk")
            SplitApkMerger().merge(sourceApk, listOf(splitNative), mergedSource).getOrThrow()

            val plan = ClonePlan(
                sourcePackage = "com.cloneforge.fixture",
                clonePackage = "com.cloneforge.fixture.clone1",
                cloneName = "Fixture Clone",
                cloneIndex = 1
            )

            val result = ApkClonePipeline().buildUnsignedClone(
                sourceApk = mergedSource,
                workspaceRoot = File(workspaceRoot, "build"),
                plan = plan
            )

            assertTrue("Pipeline failed: ${result.exceptionOrNull()?.message}", result.isSuccess)

            val artifact = result.getOrThrow()
            assertTrue(artifact.apkFile.isFile)
            assertEquals(plan.sourcePackage, artifact.sourcePackage)
            assertEquals(plan.clonePackage, artifact.clonePackage)
            assertTrue(artifact.manifestChanged)

            ZipFile(artifact.apkFile).use { zip ->
                val reference = zip.getEntry("assets/package-reference.txt")
                    ?: error("Fixture text reference is missing from the clone.")
                val text = zip.getInputStream(reference).bufferedReader().use { it.readText() }
                assertTrue(text.contains(plan.clonePackage))
                assertTrue(text.contains("${plan.clonePackage}.FixtureActivity"))
                assertTrue(!text.replace(plan.clonePackage, "").contains(plan.sourcePackage))

                val nativeLib = zip.getEntry("lib/arm64-v8a/libnative.so")
                assertNotNull("Merged native library must exist in clone APK", nativeLib)

                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                    ?: error("AndroidManifest.xml is missing from the clone.")
                val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                assertTrue(
                    "Expected android:extractNativeLibs=true (0x010104ea) on <application>",
                    hasExtractNativeLibsTrue(manifestBytes)
                )
            }

            val verification = ApkCloneVerifier().verify(
                artifact.apkFile,
                plan.sourcePackage,
                plan.clonePackage
            )
            assertTrue("Final artifact was not verified", verification.verified)
            assertEquals(plan.clonePackage, verification.manifestPackage)
            assertEquals(0, verification.sourceDescriptorOccurrences)
            assertTrue(verification.cloneDescriptorOccurrences > 0)

            val inspectCopy = File(System.getProperty("user.dir"), "build/test-clone.apk")
            inspectCopy.parentFile?.mkdirs()
            artifact.apkFile.copyTo(inspectCopy, overwrite = true)
        } finally {
            workspaceRoot.deleteRecursively()
        }
    }

    private fun hasExtractNativeLibsTrue(manifest: ByteArray): Boolean {
        val little = ByteOrder.LITTLE_ENDIAN
        fun u16(off: Int): Int = ByteBuffer.wrap(manifest, off, 2).order(little).short.toInt() and 0xffff
        fun u32(off: Int): Int = ByteBuffer.wrap(manifest, off, 4).order(little).int

        val resIds = mutableListOf<Int>()
        var offset = u16(2)
        while (offset + 8 <= manifest.size) {
            val type = u16(offset)
            val headerSize = u16(offset + 2)
            val size = u32(offset + 4)
            if (size <= 0 || offset + size > manifest.size) break
            if (type == 0x0180) {
                val count = (size - headerSize) / 4
                for (i in 0 until count) {
                    resIds.add(u32(offset + headerSize + i * 4))
                }
            } else if (type == 0x0102 && offset + 36 <= manifest.size) {
                val attrStart = u16(offset + 24)
                val attrSize = u16(offset + 26)
                val attrCount = u16(offset + 28)
                var attr = offset + headerSize + attrStart
                for (i in 0 until attrCount) {
                    val attrNameIdx = u32(attr + 4)
                    val resId = resIds.getOrNull(attrNameIdx) ?: 0
                    if (resId == 0x010104ea) {
                        val dataType = manifest[attr + 15].toInt() and 0xff
                        val data = u32(attr + 16)
                        if (dataType == 0x12 && data == -1) return true
                    }
                    attr += attrSize
                }
            }
            offset += size
        }
        return false
    }
}
