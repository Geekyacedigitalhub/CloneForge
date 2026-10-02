package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class ApkClonePipelineTest {

    @Test
    fun buildUnsignedClone_rewritesManifestDexAndTextReferences() {
        val fixturePath = System.getProperty("cloneforge.fixtureApk")
            ?: error("cloneforge.fixtureApk test property is not configured.")
        val sourceApk = File(fixturePath)
        assertTrue("Fixture APK must exist: $fixturePath", sourceApk.isFile)

        val workspaceRoot = Files.createTempDirectory("cloneforge-pipeline-").toFile()
        try {
            val plan = ClonePlan(
                sourcePackage = "com.cloneforge.fixture",
                clonePackage = "com.cloneforge.fixture.clone1",
                cloneName = "Fixture Clone",
                cloneIndex = 1
            )

            val result = ApkClonePipeline().buildUnsignedClone(
                sourceApk = sourceApk,
                workspaceRoot = workspaceRoot,
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
                assertTrue(!text.contains(plan.sourcePackage))
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
        } finally {
            workspaceRoot.deleteRecursively()
        }
    }
}
