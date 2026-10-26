package com.cloneforge.app

import java.io.File

data class DexDescriptorVerification(
    val sourceDescriptor: String,
    val cloneDescriptor: String,
    val sourceOccurrences: Int,
    val cloneOccurrences: Int,
    val verified: Boolean
)

class DexDescriptorVerifier {
    fun verify(
        dexFile: File,
        sourcePackage: String,
        clonePackage: String
    ): DexDescriptorVerification {
        require(dexFile.exists() && dexFile.isFile) { "DEX file does not exist." }
        return verifyBytes(dexFile.readBytes(), sourcePackage, clonePackage)
    }

    internal fun verifyBytes(
        data: ByteArray,
        sourcePackage: String,
        clonePackage: String
    ): DexDescriptorVerification {
        require(sourcePackage.isNotBlank()) { "Source package is empty." }
        require(clonePackage.isNotBlank()) { "Clone package is empty." }

        val sourcePath = sourcePackage.replace('.', '/')
        val clonePath = clonePackage.replace('.', '/')
        val sourceDescriptor = "L$sourcePath;"
        val cloneDescriptor = "L$clonePath;"
        val sourcePrefix = "L$sourcePath/"
        val clonePrefix = "L$clonePath/"

        val cloneExact = countAscii(data, cloneDescriptor)
        val cloneNested = countAscii(data, clonePrefix)
        val cloneOccurrences = cloneExact + cloneNested

        val sourceExact = countAscii(data, sourceDescriptor)
        val sourceNestedTotal = countAscii(data, sourcePrefix)
        val overlappingCloneInSource =
            (if (cloneDescriptor.startsWith(sourcePrefix)) cloneExact else 0) +
                (if (clonePrefix.startsWith(sourcePrefix)) cloneNested else 0)
        val sourceOccurrences = sourceExact + (sourceNestedTotal - overlappingCloneInSource).coerceAtLeast(0)

        return DexDescriptorVerification(
            sourceDescriptor = sourceDescriptor,
            cloneDescriptor = cloneDescriptor,
            sourceOccurrences = sourceOccurrences,
            cloneOccurrences = cloneOccurrences,
            verified = sourceOccurrences == 0 && cloneOccurrences > 0
        )
    }

    private fun countAscii(data: ByteArray, value: String): Int {
        val needle = value.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty() || data.size < needle.size) return 0

        var count = 0
        var index = 0
        while (index <= data.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (data[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                count++
                index += needle.size
            } else {
                index++
            }
        }
        return count
    }
}
