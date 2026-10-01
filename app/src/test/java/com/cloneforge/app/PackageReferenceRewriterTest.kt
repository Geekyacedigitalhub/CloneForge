package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PackageReferenceRewriterTest {
    @Test
    fun rewritesPlainTextPackageReferences() {
        val file = File.createTempFile("package-rewrite", ".txt")
        try {
            file.writeText("api=com.example.source\nname=com.example.source")
            val count = PackageReferenceRewriter().rewritePlainTextFile(
                file,
                "com.example.source",
                "com.example.clone1"
            )
            assertEquals(2, count)
            assertTrue(file.readText().contains("com.example.clone1"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun leavesUnrelatedFileUntouched() {
        val file = File.createTempFile("package-rewrite", ".txt")
        try {
            file.writeText("com.other.app")
            val count = PackageReferenceRewriter().rewritePlainTextFile(
                file,
                "com.example.source",
                "com.example.clone1"
            )
            assertEquals(0, count)
            assertEquals("com.other.app", file.readText())
        } finally {
            file.delete()
        }
    }
}
