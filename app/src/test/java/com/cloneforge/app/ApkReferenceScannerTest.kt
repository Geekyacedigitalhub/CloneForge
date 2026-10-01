package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkReferenceScannerTest {
    @Test
    fun findsSourcePackageReferences() {
        val file = File.createTempFile("reference-scan", ".apk")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write("com.example.source".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/config.txt"))
                zip.write("package=com.example.source".toByteArray())
                zip.closeEntry()
            }
            val result = ApkReferenceScanner().scan(file, "com.example.source")
            assertEquals(1, result.dexReferences)
            assertEquals(1, result.assetReferences)
            assertEquals(2, result.totalReferences)
        } finally {
            file.delete()
        }
    }

    @Test
    fun ignoresUnrelatedPackages() {
        val file = File.createTempFile("reference-scan", ".apk")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write("com.other.app".toByteArray())
                zip.closeEntry()
            }
            val result = ApkReferenceScanner().scan(file, "com.example.source")
            assertEquals(0, result.totalReferences)
            assertTrue(result.dexReferences == 0)
        } finally {
            file.delete()
        }
    }
}
