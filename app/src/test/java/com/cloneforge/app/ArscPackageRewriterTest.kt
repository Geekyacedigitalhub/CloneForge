package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ArscPackageRewriterTest {

    @Test
    fun rewritesMatchingPackageHeaderInResourcesArsc() {
        val original = buildMinimalResourcesArsc("com.example.source")

        val result = ArscPackageRewriter().rewrite(
            arscBytes = original,
            sourcePackage = "com.example.source",
            clonePackage = "com.example.source.clone1"
        )

        assertEquals(1, result.packagesRewritten)
        assertEquals(original.size, result.data.size)

        // Rewriting a second time against sourcePackage should find 0 matches
        // and rewriting against clonePackage should find 1 match.
        val verify = ArscPackageRewriter().rewrite(
            arscBytes = result.data,
            sourcePackage = "com.example.source.clone1",
            clonePackage = "com.example.source.clone2"
        )
        assertEquals(1, verify.packagesRewritten)
    }

    @Test
    fun leavesTooSmallOrUnrelatedResourceTableUntouched() {
        val tiny = byteArrayOf(0x02, 0x00)
        val result = ArscPackageRewriter().rewrite(
            arscBytes = tiny,
            sourcePackage = "com.example.source",
            clonePackage = "com.example.source.clone1"
        )
        assertEquals(0, result.packagesRewritten)
        assertEquals(2, result.data.size)
    }

    private fun buildMinimalResourcesArsc(packageName: String): ByteArray {
        val rootHeaderSize = 12
        val pkgHeaderSize = 288
        val totalSize = rootHeaderSize + pkgHeaderSize
        return ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            // ResTable_header (type = 0x0002)
            putShort(0x0002)
            putShort(rootHeaderSize.toShort())
            putInt(totalSize)
            putInt(1) // packageCount

            // ResTable_package (type = 0x0200)
            putShort(0x0200)
            putShort(pkgHeaderSize.toShort())
            putInt(pkgHeaderSize)
            putInt(0x7f) // id

            // char16_t name[128] at offset 12 + 12 = 24
            val chars = packageName.toCharArray()
            for (i in 0 until 128) {
                putShort(if (i < chars.size) chars[i].code.toShort() else 0)
            }
            // Remaining 20 bytes of ResTable_package header
            putInt(0)
            putInt(0)
            putInt(0)
            putInt(0)
            putInt(0)
        }.array()
    }
}
