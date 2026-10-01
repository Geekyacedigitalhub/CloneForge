package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DexReferenceScannerTest {
    @Test
    fun scansExactPackageDescriptorsAcrossDexFiles() {
        val apk = File.createTempFile("cloneforge-dex", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write("Lcom/example/source/Feature; Lcom/example/source;".toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("classes2.dex"))
                zip.write("Lcom/example/source/Other;".toByteArray())
                zip.closeEntry()
            }

            val result = DexReferenceScanner().scan(apk, "com.example.source")

            assertEquals(2, result.references.size)
            assertEquals(3, result.totalReferences)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun ignoresUnrelatedPackageText() {
        val apk = File.createTempFile("cloneforge-dex", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write("com.example.sourceful com.other.app".toByteArray())
                zip.closeEntry()
            }

            val result = DexReferenceScanner().scan(apk, "com.example.source")

            assertEquals(0, result.totalReferences)
        } finally {
            apk.delete()
        }
    }
}
