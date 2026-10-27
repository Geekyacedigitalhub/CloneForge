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
    fun injectsExtractNativeLibsTrueWhenMissingFromApplicationWithResourceMap() {
        val manifestBytes = buildTestBinaryManifestWithResIds(
            packageName = "com.openai.chatgpt",
            appLabel = "ChatGPT",
            activityName = "com.openai.chatgpt.MainActivity"
        )

        val rewrite = BinaryManifestTransformer().rewrite(
            manifest = manifestBytes,
            sourcePackage = "com.openai.chatgpt",
            clonePackage = "com.openai.chatgpt.clone1",
            cloneLabel = "ChatGPT Clone"
        ).getOrThrow()

        val rewrittenInfo = BinaryManifestReader().read(rewrite.data)
        assertEquals("com.openai.chatgpt.clone1", rewrittenInfo.packageName)
        assertEquals("ChatGPT Clone", rewrittenInfo.applicationLabel)
        assertTrue(
            "Expected rewritten activity name in manifest bytes",
            String(rewrite.data, Charsets.UTF_16LE).contains("com.openai.chatgpt.clone1.MainActivity")
        )
        assertTrue("Expected extractNativeLibs=true on <application>", hasExtractNativeLibsTrue(rewrite.data))
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

    @Test
    fun `rewrite with preserveClasses expands relative activity name and preserves source classes`() {
        val manifestBytes = buildTestBinaryManifest(
            packageName = "com.openai.chatgpt",
            appLabel = "ChatGPT",
            activityName = ".MainActivity"
        )

        val result = BinaryManifestTransformer().rewrite(
            manifest = manifestBytes,
            sourcePackage = "com.openai.chatgpt",
            clonePackage = "com.openai.chatgpt.clone1",
            cloneLabel = "ChatGPT Clone",
            preserveClasses = true
        ).getOrThrow()

        assertTrue(result.packageChanged)
        val parsed = BinaryManifestReader().read(result.data)
        assertEquals("com.openai.chatgpt.clone1", parsed.packageName)
        assertEquals("ChatGPT Clone", parsed.applicationLabel)
        val utf16Text = String(result.data, Charsets.UTF_16LE)
        assertTrue(utf16Text.contains("com.openai.chatgpt.MainActivity"))
        assertEquals(
            "com.openai.chatgpt.clone1.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            result.stringReplacements["com.openai.chatgpt.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"]
        )
    }

    @Test
    fun `preserveClasses keeps provider class name when authorities shares same StringPool index`() {
        val manifestBytes = buildProviderSharedStringIndexManifest(
            packageName = "com.zhiliaoapp.musically",
            appLabel = "TikTok",
            sharedClassAndAuthority = "com.zhiliaoapp.musically.SharedProvider",
            processName = "com.zhiliaoapp.musically:push"
        )

        val result = BinaryManifestTransformer().rewrite(
            manifest = manifestBytes,
            sourcePackage = "com.zhiliaoapp.musically",
            clonePackage = "com.zhiliaoapp.musically.clone1",
            cloneLabel = "TikTok Clone",
            preserveClasses = true
        ).getOrThrow()

        val utf16Text = String(result.data, Charsets.UTF_16LE)
        assertTrue(
            "Original provider class name must remain in StringPool",
            utf16Text.contains("com.zhiliaoapp.musically.SharedProvider")
        )
        assertTrue(
            "Rewritten provider authority must be added to StringPool",
            utf16Text.contains("com.zhiliaoapp.musically.clone1.SharedProvider")
        )
        assertTrue(
            "Rewritten process name must be added to StringPool",
            utf16Text.contains("com.zhiliaoapp.musically.clone1:push")
        )

        val rewriter = DexPackageRewriter()
        assertEquals(
            "com.zhiliaoapp.musically.MainActivity",
            rewriter.rewritePreservingClassNames(
                "com.zhiliaoapp.musically.MainActivity",
                "com.zhiliaoapp.musically",
                "com.zhiliaoapp.musically.clone1"
            )
        )
        assertEquals(
            "com.zhiliaoapp.musically.clone1",
            rewriter.rewritePreservingClassNames(
                "com.zhiliaoapp.musically",
                "com.zhiliaoapp.musically",
                "com.zhiliaoapp.musically.clone1"
            )
        )
        assertEquals(
            "com.zhiliaoapp.musically.clone1.fileprovider",
            rewriter.rewritePreservingClassNames(
                "com.zhiliaoapp.musically.fileprovider",
                "com.zhiliaoapp.musically",
                "com.zhiliaoapp.musically.clone1"
            )
        )
        assertEquals(
            "com.zhiliaoapp.musically.clone1:miniapp0",
            rewriter.rewritePreservingClassNames(
                "com.zhiliaoapp.musically:miniapp0",
                "com.zhiliaoapp.musically",
                "com.zhiliaoapp.musically.clone1"
            )
        )
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
                var prevResId = 0L
                for (i in 0 until attrCount) {
                    val attrNameIdx = u32(attr + 4)
                    val resId = resIds.getOrNull(attrNameIdx) ?: 0
                    val unsignedResId = resId.toLong() and 0xffffffffL
                    if (resId != 0) {
                        assertTrue("Attributes must remain sorted by resource ID", unsignedResId >= prevResId)
                        prevResId = unsignedResId
                    }
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

    private fun buildTestBinaryManifestWithResIds(
        packageName: String,
        appLabel: String,
        activityName: String
    ): ByteArray {
        // Indices 0..2 have resource IDs in 0x0180:
        // 0 -> label (0x01010001)
        // 1 -> name (0x01010003)
        // 2 -> appComponentFactory (0x01010578) -- higher than extractNativeLibs (0x010104ea)!
        val strings = listOf(
            "label",
            "name",
            "appComponentFactory",
            "http://schemas.android.com/apk/res/android",
            "manifest",
            "package",
            packageName,
            "application",
            appLabel,
            "androidx.core.app.CoreComponentFactory",
            "activity",
            activityName
        )
        val resIds = intArrayOf(0x01010001, 0x01010003, 0x01010578)

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

        val resMapSize = 8 + resIds.size * 4
        val resMapChunk = ByteBuffer.allocate(resMapSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0180)
            putShort(8)
            putInt(resMapSize)
            resIds.forEach { putInt(it) }
        }.array()

        val manifestTag = buildStartTag(
            tagIndex = 4,
            attrNamespace = -1,
            attrName = 5,
            attrValue = 6
        )
        // <application android:label="ChatGPT" android:appComponentFactory="androidx.core.app.CoreComponentFactory">
        val applicationTag = ByteBuffer.allocate(16 + 20 + 40).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0102)
            putShort(16)
            putInt(16 + 20 + 40)
            putInt(1)
            putInt(-1)
            putInt(-1)
            putInt(7) // "application"
            putShort(20)
            putShort(20)
            putShort(2) // 2 attributes
            putShort(0)
            putShort(0)
            putShort(0)
            // Attr 0: label (0) = appLabel (8)
            putInt(3)
            putInt(0)
            putInt(8)
            putShort(8)
            put(0)
            put(0x03)
            putInt(8)
            // Attr 1: appComponentFactory (2) = CoreComponentFactory (9)
            putInt(3)
            putInt(2)
            putInt(9)
            putShort(8)
            put(0)
            put(0x03)
            putInt(9)
        }.array()

        val activityTag = buildStartTag(
            tagIndex = 10,
            attrNamespace = 3,
            attrName = 1,
            attrValue = 11
        )

        val totalSize = 8 + poolChunk.size + resMapChunk.size + manifestTag.size + applicationTag.size + activityTag.size
        return ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0003)
            putShort(8)
            putInt(totalSize)
            put(poolChunk)
            put(resMapChunk)
            put(manifestTag)
            put(applicationTag)
            put(activityTag)
        }.array()
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

    private fun buildProviderSharedStringIndexManifest(
        packageName: String,
        appLabel: String,
        sharedClassAndAuthority: String,
        processName: String
    ): ByteArray {
        val strings = listOf(
            "http://schemas.android.com/apk/res/android", // 0
            "manifest",                                    // 1
            "package",                                     // 2
            packageName,                                   // 3
            "application",                                 // 4
            "label",                                       // 5
            appLabel,                                      // 6
            "provider",                                    // 7
            "name",                                        // 8
            "authorities",                                 // 9
            sharedClassAndAuthority,                       // 10 (shared by both name and authorities!)
            "process",                                     // 11
            processName                                    // 12
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

        val manifestTag = buildStartTag(tagIndex = 1, attrNamespace = -1, attrName = 2, attrValue = 3)
        val applicationTag = buildStartTag(tagIndex = 4, attrNamespace = 0, attrName = 5, attrValue = 6)

        // <provider android:name="@10" android:authorities="@10" android:process="@12">
        val providerChunkSize = 16 + 20 + 3 * 20
        val providerTag = ByteBuffer.allocate(providerChunkSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0102)
            putShort(16)
            putInt(providerChunkSize)
            putInt(1)
            putInt(-1)
            putInt(-1)
            putInt(7) // "provider"
            putShort(20)
            putShort(20)
            putShort(3) // 3 attributes
            putShort(0)
            putShort(0)
            putShort(0)
            // Attr 0: android:name = 10
            putInt(0)
            putInt(8)
            putInt(10)
            putShort(8)
            put(0)
            put(0x03)
            putInt(10)
            // Attr 1: android:authorities = 10 (same StringPool index!)
            putInt(0)
            putInt(9)
            putInt(10)
            putShort(8)
            put(0)
            put(0x03)
            putInt(10)
            // Attr 2: android:process = 12
            putInt(0)
            putInt(11)
            putInt(12)
            putShort(8)
            put(0)
            put(0x03)
            putInt(12)
        }.array()

        val totalSize = 8 + poolChunk.size + manifestTag.size + applicationTag.size + providerTag.size
        return ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0003)
            putShort(8)
            putInt(totalSize)
            put(poolChunk)
            put(manifestTag)
            put(applicationTag)
            put(providerTag)
        }.array()
    }
}
