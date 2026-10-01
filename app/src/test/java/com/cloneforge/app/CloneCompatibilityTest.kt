package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneCompatibilityTest {
    private fun inspection(
        hasManifest: Boolean = true,
        hasResources: Boolean = true,
        hasDex: Boolean = true,
        dexCount: Int = 1,
        nativeLibraryCount: Int = 0
    ) = ApkInspection(
        "test.apk", hasManifest, hasResources, hasDex,
        dexCount, nativeLibraryCount, 0
    )

    @Test
    fun acceptsNormalSingleApkWithWarnings() {
        val result = CloneCompatibilityChecker.check(inspection())
        assertTrue(result.supported)
        assertTrue(result.issues.isEmpty())
        assertTrue(result.hasWarnings)
        assertEquals("Ready with warnings", result.title)
    }

    @Test
    fun rejectsMissingManifest() {
        val result = CloneCompatibilityChecker.check(inspection(hasManifest = false))
        assertFalse(result.supported)
        assertTrue(result.issues.any { it.contains("AndroidManifest") })
        assertEquals("Special handling required", result.title)
    }

    @Test
    fun rejectsSplitApk() {
        val result = CloneCompatibilityChecker.check(inspection(), isSplitApk = true)
        assertFalse(result.supported)
        assertTrue(result.issues.any { it.contains("split APKs") })
    }

    @Test
    fun warnsAboutNativeLibraries() {
        val result = CloneCompatibilityChecker.check(inspection(nativeLibraryCount = 4))
        assertTrue(result.supported)
        assertTrue(result.warnings.any { it.contains("native libraries") })
    }

    @Test
    fun reportsRawPackageReferences() {
        val result = CloneCompatibilityChecker.check(
            inspection(),
            packageReferenceCount = 7
        )
        assertEquals(7, result.packageReferenceCount)
        assertTrue(result.warnings.any { it.contains("7 raw references") })
    }

    @Test
    fun reportsMissingResourcesAsWarningOnly() {
        val result = CloneCompatibilityChecker.check(inspection(hasResources = false))
        assertTrue(result.supported)
        assertTrue(result.warnings.any { it.contains("resources.arsc is missing") })
    }
}
