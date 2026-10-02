package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneBackupPathValidatorTest {

    @Test
    fun normalizeEntryName_convertsWindowsSeparators() {
        assertEquals(
            "apks/0.apk",
            CloneBackupPathValidator.normalizeEntryName("apks\\0.apk")
        )
    }

    @Test
    fun normalizeEntryName_rejectsTraversal() {
        try {
            CloneBackupPathValidator.normalizeEntryName("apks/../escape.apk")
            throw AssertionError("Expected unsafe path rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("unsafe path"))
        }
    }

    @Test
    fun isApkEntry_acceptsOnlyIndexedApkEntries() {
        assertTrue(CloneBackupPathValidator.isApkEntry("apks/0.apk"))
        assertTrue(CloneBackupPathValidator.isApkEntry("apks/123.apk"))
        assertFalse(CloneBackupPathValidator.isApkEntry("apks/app.apk"))
        assertFalse(CloneBackupPathValidator.isApkEntry("other/0.apk"))
        assertFalse(CloneBackupPathValidator.isApkEntry("apks/0.apk/extra"))
    }
}
