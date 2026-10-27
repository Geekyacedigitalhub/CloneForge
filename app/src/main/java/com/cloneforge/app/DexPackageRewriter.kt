package com.cloneforge.app

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.ReferenceType
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.formats.Instruction21c
import org.jf.dexlib2.iface.instruction.formats.Instruction31c
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.value.EncodedValue
import org.jf.dexlib2.iface.value.StringEncodedValue
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31c
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.EncodedValueRewriter
import org.jf.dexlib2.rewriter.InstructionRewriter
import org.jf.dexlib2.rewriter.Rewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.rewriter.Rewriters
import org.jf.dexlib2.rewriter.TypeRewriter
import java.io.File

data class DexRewriteResult(
    val changed: Boolean,
    val replacements: Int,
    val verification: DexDescriptorVerification,
    val outputFile: File,
    val descriptorReplacements: Int = 0,
    val stringReplacements: Int = 0
)

class DexPackageRewriter(
    private val verifier: DexDescriptorVerifier = DexDescriptorVerifier()
) {
    fun rewrite(
        dexFile: File,
        sourcePackage: String,
        clonePackage: String,
        preserveClasses: Boolean = false,
        exactStringReplacements: Map<String, String> = emptyMap()
    ): Result<DexRewriteResult> = runCatching {
        require(dexFile.exists() && dexFile.isFile) { "DEX file does not exist." }
        require(sourcePackage.isNotBlank()) { "Source package is empty." }
        require(clonePackage.isNotBlank()) { "Clone package is empty." }

        val sourcePath = sourcePackage.replace('.', '/')
        val sourceDescriptor = "L$sourcePath;"
        val cloneDescriptor = "L" + clonePackage.replace('.', '/') + ";"
        val sourcePrefix = "L$sourcePath/"
        val clonePrefix = cloneDescriptor.dropLast(1) + "/"

        val initialBytes = dexFile.readBytes()
        val initialVerification = if (preserveClasses) {
            DexDescriptorVerification(
                sourceDescriptor = sourceDescriptor,
                cloneDescriptor = cloneDescriptor,
                sourceOccurrences = 0,
                cloneOccurrences = 0,
                verified = true
            )
        } else {
            verifier.verifyBytes(initialBytes, sourcePackage, clonePackage)
        }
        val hasSourcePackageBytes = if (preserveClasses) {
            containsAscii(initialBytes, sourcePackage) ||
                exactStringReplacements.entries.any { (key, value) ->
                    key.isNotEmpty() && key != value && containsAscii(initialBytes, key)
                }
        } else {
            initialVerification.sourceOccurrences > 0 ||
                containsAscii(initialBytes, sourcePackage) ||
                containsAscii(initialBytes, sourcePath)
        }

        if (!hasSourcePackageBytes) {
            return@runCatching DexRewriteResult(
                changed = false,
                replacements = 0,
                verification = initialVerification,
                outputFile = dexFile,
                descriptorReplacements = 0,
                stringReplacements = 0
            )
        }

        val isDexMagic = initialBytes.size >= 8 &&
            initialBytes[0] == 'd'.code.toByte() &&
            initialBytes[1] == 'e'.code.toByte() &&
            initialBytes[2] == 'x'.code.toByte() &&
            initialBytes[3] == '\n'.code.toByte()
        val parsedDexVersion = if (isDexMagic) {
            String(initialBytes, 4, 3, Charsets.US_ASCII).toIntOrNull() ?: 39
        } else {
            39
        }
        // dexlib2 2.5.2 only recognizes DEX header versions 035..039 in DexUtil.verifyDexHeader;
        // DEX 040/041 share the exact binary layout of DEX 039.
        if (isDexMagic && parsedDexVersion > 39) {
            initialBytes[4] = '0'.code.toByte()
            initialBytes[5] = '3'.code.toByte()
            initialBytes[6] = '9'.code.toByte()
            dexFile.writeBytes(initialBytes)
        }

        val opcodes = runCatching {
            Opcodes.forDexVersion(parsedDexVersion.coerceIn(35, 39))
        }.getOrElse {
            Opcodes.forDexVersion(39)
        }

        val original = runCatching {
            DexFileFactory.loadDexFile(dexFile, opcodes)
        }.getOrElse {
            DexFileFactory.loadDexFile(dexFile, Opcodes.forDexVersion(39))
        }

        var descriptorReplacements = 0
        var stringReplacements = 0

        fun transformString(raw: String): String {
            return if (preserveClasses) {
                exactStringReplacements[raw]
                    ?: rewritePreservingClassNames(raw, sourcePackage, clonePackage)
            } else {
                rewritePackageString(raw, sourcePackage, clonePackage)
            }
        }

        val rewriter = DexRewriter(object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters): Rewriter<String> =
                object : TypeRewriter() {
                    override fun rewriteUnwrappedType(value: String): String {
                        if (preserveClasses) return value
                        val rewritten = when {
                            value == sourceDescriptor -> cloneDescriptor
                            value.startsWith(sourcePrefix) -> clonePrefix + value.removePrefix(sourcePrefix)
                            else -> value
                        }
                        if (rewritten != value) descriptorReplacements++
                        return rewritten
                    }
                }

            override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> =
                object : InstructionRewriter(rewriters) {
                    override fun rewrite(instruction: Instruction): Instruction {
                        val base = super.rewrite(instruction)
                        if (instruction is Instruction21c && instruction.referenceType == ReferenceType.STRING) {
                            val ref = instruction.reference as? StringReference ?: return base
                            val updated = transformString(ref.string)
                            if (updated != ref.string) {
                                stringReplacements++
                                return ImmutableInstruction21c(
                                    instruction.opcode,
                                    instruction.registerA,
                                    ImmutableStringReference(updated)
                                )
                            }
                        } else if (instruction is Instruction31c && instruction.referenceType == ReferenceType.STRING) {
                            val ref = instruction.reference as? StringReference ?: return base
                            val updated = transformString(ref.string)
                            if (updated != ref.string) {
                                stringReplacements++
                                return ImmutableInstruction31c(
                                    instruction.opcode,
                                    instruction.registerA,
                                    ImmutableStringReference(updated)
                                )
                            }
                        }
                        return base
                    }
                }

            override fun getEncodedValueRewriter(rewriters: Rewriters): Rewriter<EncodedValue> =
                object : EncodedValueRewriter(rewriters) {
                    override fun rewrite(encodedValue: EncodedValue): EncodedValue {
                        if (encodedValue is StringEncodedValue) {
                            val updated = transformString(encodedValue.value)
                            if (updated != encodedValue.value) {
                                stringReplacements++
                                return ImmutableStringEncodedValue(updated)
                            }
                        }
                        return super.rewrite(encodedValue)
                    }
                }
        })

        val rewritten = rewriter.dexFileRewriter.rewrite(original)
        val tempOutput = File(dexFile.parentFile, dexFile.name + ".rewritten")
        try {
            DexFileFactory.writeDexFile(tempOutput.absolutePath, rewritten)
            val totalReplacements = descriptorReplacements + stringReplacements
            if (totalReplacements > 0) {
                tempOutput.copyTo(dexFile, overwrite = true)
            }
        } finally {
            if (tempOutput.exists()) {
                tempOutput.delete()
            }
        }

        val totalReplacements = descriptorReplacements + stringReplacements
        val verification = if (preserveClasses) {
            DexDescriptorVerification(
                sourceDescriptor = sourceDescriptor,
                cloneDescriptor = cloneDescriptor,
                sourceOccurrences = 0,
                cloneOccurrences = 0,
                verified = true
            )
        } else {
            verifier.verify(dexFile, sourcePackage, clonePackage)
        }
        val dexVerified = preserveClasses || (
            verification.sourceOccurrences == 0 &&
                (descriptorReplacements == 0 || verification.cloneOccurrences > 0)
            )
        if (!dexVerified) {
            error(
                "DEX rewrite verification failed: original descriptor occurrences=" +
                    verification.sourceOccurrences +
                    ", clone descriptor occurrences=" +
                    verification.cloneOccurrences
            )
        }

        DexRewriteResult(
            changed = totalReplacements > 0,
            replacements = totalReplacements,
            verification = verification,
            outputFile = dexFile,
            descriptorReplacements = descriptorReplacements,
            stringReplacements = stringReplacements
        )
    }

    internal fun rewritePackageString(
        value: String,
        sourcePackage: String,
        clonePackage: String
    ): String {
        if (value.contains(';')) {
            val parts = value.split(';')
            var changed = false
            val updatedParts = parts.map { part ->
                val rewritten = rewriteSinglePackageString(part, sourcePackage, clonePackage)
                if (rewritten != part) changed = true
                rewritten
            }
            if (changed) return updatedParts.joinToString(";")
        }
        return rewriteSinglePackageString(value, sourcePackage, clonePackage)
    }

    private fun rewriteSinglePackageString(
        value: String,
        sourcePackage: String,
        clonePackage: String
    ): String {
        val sourceDotPrefix = "$sourcePackage."
        val cloneDotPrefix = "$clonePackage."
        val sourceContentUri = "content://$sourcePackage"
        val cloneContentUri = "content://$clonePackage"
        val sourceContentPrefix = "content://$sourcePackage."
        val cloneContentPrefix = "content://$clonePackage."

        return when {
            value == clonePackage || value.startsWith(cloneDotPrefix) ||
                value == cloneContentUri || value.startsWith(cloneContentPrefix) -> value
            value == sourcePackage -> clonePackage
            value.startsWith(sourceDotPrefix) -> cloneDotPrefix + value.removePrefix(sourceDotPrefix)
            value == sourceContentUri -> cloneContentUri
            value.startsWith(sourceContentPrefix) -> cloneContentPrefix + value.removePrefix(sourceContentPrefix)
            else -> value
        }
    }

    internal fun rewritePreservingClassNames(
        value: String,
        sourcePackage: String,
        clonePackage: String
    ): String {
        val sourceDotPrefix = "$sourcePackage."
        val cloneDotPrefix = "$clonePackage."
        val sourceColonPrefix = "$sourcePackage:"
        val cloneColonPrefix = "$clonePackage:"
        val sourceContentUri = "content://$sourcePackage"
        val cloneContentUri = "content://$clonePackage"
        val sourceContentPrefix = "content://$sourcePackage."
        val cloneContentPrefix = "content://$clonePackage."

        return when {
            value == clonePackage ||
                value.startsWith(cloneDotPrefix) ||
                value.startsWith(cloneColonPrefix) ||
                value == cloneContentUri ||
                value.startsWith(cloneContentPrefix) -> value
            value == sourcePackage -> clonePackage
            value.startsWith(sourceColonPrefix) -> cloneColonPrefix + value.removePrefix(sourceColonPrefix)
            value == sourceContentUri -> cloneContentUri
            value.startsWith(sourceContentPrefix) -> cloneContentPrefix + value.removePrefix(sourceContentPrefix)
            value.startsWith(sourceDotPrefix) -> {
                val suffix = value.removePrefix(sourceDotPrefix)
                if (suffix.isNotEmpty() && !looksLikeJavaClassSuffix(suffix)) {
                    cloneDotPrefix + suffix
                } else {
                    value
                }
            }
            else -> value
        }
    }

    private fun looksLikeJavaClassSuffix(suffix: String): Boolean {
        val segments = suffix.split('.')
        if (segments.any { it.isEmpty() }) return true
        for (seg in segments) {
            val first = seg[0]
            if (first in 'A'..'Z') {
                val isAllCapsConstant = seg.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
                if (!isAllCapsConstant) {
                    return true
                }
            }
        }
        return false
    }

    private fun containsAscii(data: ByteArray, value: String): Boolean {
        val needle = value.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty() || data.size < needle.size) return false
        var index = 0
        while (index <= data.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (data[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
            index++
        }
        return false
    }
}
