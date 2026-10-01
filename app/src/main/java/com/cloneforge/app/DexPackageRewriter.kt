package com.cloneforge.app

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.Rewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.rewriter.Rewriters
import org.jf.dexlib2.rewriter.TypeRewriter
import java.io.File

data class DexRewriteResult(val changed: Boolean, val replacements: Int, val outputFile: File)

class DexPackageRewriter {
    fun rewrite(dexFile: File, sourcePackage: String, clonePackage: String): Result<DexRewriteResult> = runCatching {
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
        })

        val rewritten = rewriter.rewriteDexFile(original)
        if (replacements > 0) DexFileFactory.writeDexFile(dexFile.absolutePath, rewritten)

        DexRewriteResult(replacements > 0, replacements, dexFile)
    }
}
