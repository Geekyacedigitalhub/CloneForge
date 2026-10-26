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
    val outputFile: File
)

class DexPackageRewriter(
    private val verifier: DexDescriptorVerifier = DexDescriptorVerifier()
) {
    fun rewrite(
        dexFile: File,
        sourcePackage: String,
        clonePackage: String
    ): Result<DexRewriteResult> = runCatching {
        require(dexFile.exists() && dexFile.isFile) { "DEX file does not exist." }
        require(sourcePackage.isNotBlank()) { "Source package is empty." }
        require(clonePackage.isNotBlank()) { "Clone package is empty." }

        val sourceDescriptor = "L" + sourcePackage.replace('.', '/') + ";"
        val cloneDescriptor = "L" + clonePackage.replace('.', '/') + ";"
        val sourcePrefix = sourceDescriptor.dropLast(1) + "/"
        val clonePrefix = cloneDescriptor.dropLast(1) + "/"

        val original = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
        var replacements = 0

        val rewriter = DexRewriter(object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters): Rewriter<String> =
                object : TypeRewriter() {
                    override fun rewriteUnwrappedType(value: String): String {
                        val rewritten = when {
                            value == sourceDescriptor -> cloneDescriptor
                            value.startsWith(sourcePrefix) -> clonePrefix + value.removePrefix(sourcePrefix)
                            else -> value
                        }
                        if (rewritten != value) replacements++
                        return rewritten
                    }
                }

            override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> =
                object : InstructionRewriter(rewriters) {
                    override fun rewrite(instruction: Instruction): Instruction {
                        val base = super.rewrite(instruction)
                        if (instruction is Instruction21c && instruction.referenceType == ReferenceType.STRING) {
                            val ref = instruction.reference as? StringReference ?: return base
                            val updated = rewritePackageString(ref.string, sourcePackage, clonePackage)
                            if (updated != ref.string) {
                                replacements++
                                return ImmutableInstruction21c(
                                    instruction.opcode,
                                    instruction.registerA,
                                    ImmutableStringReference(updated)
                                )
                            }
                        } else if (instruction is Instruction31c && instruction.referenceType == ReferenceType.STRING) {
                            val ref = instruction.reference as? StringReference ?: return base
                            val updated = rewritePackageString(ref.string, sourcePackage, clonePackage)
                            if (updated != ref.string) {
                                replacements++
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
                            val updated = rewritePackageString(encodedValue.value, sourcePackage, clonePackage)
                            if (updated != encodedValue.value) {
                                replacements++
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
            if (replacements > 0) {
                tempOutput.copyTo(dexFile, overwrite = true)
            }
        } finally {
            if (tempOutput.exists()) {
                tempOutput.delete()
            }
        }

        val verification = verifier.verify(dexFile, sourcePackage, clonePackage)
        if (replacements > 0 && !verification.verified) {
            error(
                "DEX rewrite verification failed: original descriptor occurrences=" +
                    verification.sourceOccurrences +
                    ", clone descriptor occurrences=" +
                    verification.cloneOccurrences
            )
        }

        DexRewriteResult(
            changed = replacements > 0,
            replacements = replacements,
            verification = verification,
            outputFile = dexFile
        )
    }

    internal fun rewritePackageString(
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
}
