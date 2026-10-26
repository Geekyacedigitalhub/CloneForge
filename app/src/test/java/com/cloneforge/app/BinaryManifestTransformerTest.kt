package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryManifestTransformerTest {

    @Test
    fun rewritesPackageLabelAndPrefixedComponentStrings() {
        val manifestBytes = buildTestBinaryManifest(
            packageName = "com.example.source",
            appLabel = "Source App",
            activityName = "com.example.source.MainActivity"
        )

        val initialInfo = BinaryManifestReader().read(manifestBytes)
        assertEquals("com.example.source", initialInfo.packageName)
        assertEquals("Source App", initialInfo.applicationLabel)

        val rewrite = BinaryManifestTransformer().rewrite(
            manifest = manifestBytes,
            sourcePackage = "com.example.source",
            clonePackage = "com.example.source.clone1",
            cloneLabel = "Cloned App"
        ).getOrThrow()

        assertTrue(rewrite.packageChanged)
        assertTrue(rewrite.labelChanged)

        val totalSizeHeader = ByteBuffer.wrap(rewrite.data, 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        assertEquals(rewrite.data.size, totalSizeHeader)

        val rewrittenInfo = BinaryManifestReader().read(rewrite.data)
        assertEquals("com.example.source.clone1", rewrittenInfo.packageName)
        assertEquals("Cloned App", rewrittenInfo.applicationLabel)
    }

    @Test
    fun failsWhenSourcePackageIsMissingFromManifest() {
        val manifestBytes = buildTestBinaryManifest(
            packageName = "com.example.other",
            appLabel = "Other App",
            activityName = "com.example.other.MainActivity"
        )

        val result = BinaryManifestTransformer().rewrite(
            manifest = manifestBytes,
            sourcePackage = "com.example.source",
            clonePackage = "com.example.source.clone1",
            cloneLabel = "Cloned App"
        )

        assertTrue(result.isFailure)
    }

    private fun buildTestBinaryManifest(
        packageName: String,
        appLabel: String,
        activityName: String
    ): ByteArray {
        val strings = listOf(
            "http://schemas.android.com/apk/res/android",
            "manifest",
            "package",
            packageName,
            "application",
            "label",
            appLabel,
            "activity",
            "name",
            activityName
        )

        val encodedStrings = strings.map { value ->
            val chars = value.toCharArray()
            ByteBuffer.allocate(2 + chars.size * 2 + 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(chars.size.toShort())
                chars.forEach { putShort(it.code.toShort()) }
                putShort(0)
            }.array()
        }

        val headerSize = 28
        val offsetsSize = strings.size * 4
        val stringsStart = headerSize + offsetsSize
        val stringsDataSize = encodedStrings.sumOf { it.size }
        val poolSize = (stringsStart + stringsDataSize + 3) and -4

        val poolChunk = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0001)
            putShort(headerSize.toShort())
            putInt(poolSize)
            putInt(strings.size)
            putInt(0)
            putInt(0)
            putInt(stringsStart)
            putInt(0)

            var cursor = 0
            encodedStrings.forEach { bytes ->
                putInt(cursor)
                cursor += bytes.size
            }
            encodedStrings.forEach { bytes ->
                put(bytes)
            }
        }.array()

        val manifestTag = buildStartTag(
            tagIndex = 1,
            attrNamespace = -1,
            attrName = 2,
            attrValue = 3
        )
        val applicationTag = buildStartTag(
            tagIndex = 4,
            attrNamespace = 0,
            attrName = 5,
            attrValue = 6
        )
        val activityTag = buildStartTag(
            tagIndex = 7,
            attrNamespace = 0,
            attrName = 8,
            attrValue = 9
        )

        val totalSize = 8 + poolChunk.size + manifestTag.size + applicationTag.size + activityTag.size
        return ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0003)
            putShort(8)
            putInt(totalSize)
            put(poolChunk)
            put(manifestTag)
            put(applicationTag)
            put(activityTag)
        }.array()
    }

    private fun buildStartTag(
        tagIndex: Int,
        attrNamespace: Int,
        attrName: Int,
        attrValue: Int
    ): ByteArray {
        val chunkSize = 16 + 20 + 20
        return ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0102)
            putShort(16)
            putInt(chunkSize)
            putInt(1)
            putInt(-1)
            putInt(-1)
            putInt(tagIndex)
            putShort(20)
            putShort(20)
            putShort(1)
            putShort(0)
            putShort(0)
            putShort(0)
            putInt(attrNamespace)
            putInt(attrName)
            putInt(attrValue)
            putShort(8)
            put(0)
            put(0x03)
            putInt(attrValue)
        }.array()
    }
}
