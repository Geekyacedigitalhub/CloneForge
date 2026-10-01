package com.cloneforge.app

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.Rewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.rewriter.Rewriters
import org.jf.dexlib2.rewriter.TypeRewriter
import java.io.File

data class DexRewriteResult(val changed: Boolean, val outputFile: File)

class DexPackageRewriter {
    fun rewrite(dexFile: File, sourcePackage: String, clonePackage: String): Result<DexRewriteResult> = runCatching {
        val sourceDescriptor = "L" + sourcePackage.replace('.', '/') + ";"
        val cloneDescriptor = "L" + clonePackage.replace('.', '/') + ";"
        val sourcePrefix = sourceDescriptor.dropLast(1) + "/"
        val clonePrefix = cloneDescriptor.dropLast(1) + "/"

        val original = DexFileFactory.loadDexFile(dexFile, null)
        val rewriter = DexRewriter(object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters): Rewriter<String> {
                return object : TypeRewriter() {
                    override fun rewriteUnwrappedType(value: String): String {
                        return when {
                            value == sourceDescriptor -> cloneDescriptor
                            value.startsWith(sourcePrefix) -> clonePrefix + value.removePrefix(sourcePrefix)
                            else -> value
                        }
                    }
                }
            }
        })

        val rewritten = rewriter.rewriteDexFile(original)
        DexFileFactory.writeDexFile(dexFile.absolutePath, rewritten)
        DexRewriteResult(true, dexFile)
    }
}
