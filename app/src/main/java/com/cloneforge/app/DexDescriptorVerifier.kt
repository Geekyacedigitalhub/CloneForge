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

        val sourceDescriptor = "L${sourcePackage.replace('.', '/')};"
        val cloneDescriptor = "L${clonePackage.replace('.', '/')};"
        val sourceOccurrences = countAscii(data, sourceDescriptor)
        val cloneOccurrences = countAscii(data, cloneDescriptor)

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
