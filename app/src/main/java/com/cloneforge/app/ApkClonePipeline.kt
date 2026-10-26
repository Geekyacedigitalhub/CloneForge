package com.cloneforge.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class UnsignedCloneArtifact(
    val apkFile: File,
    val sourcePackage: String,
    val clonePackage: String,
    val cloneName: String,
    val manifestChanged: Boolean
)

class ApkClonePipeline(
    private val workspaceManager: ApkWorkspaceManager = ApkWorkspaceManager(),
    private val manifestTransformer: BinaryManifestTransformer = BinaryManifestTransformer(),
    private val arscPackageRewriter: ArscPackageRewriter = ArscPackageRewriter(),
    private val plainTextRewriter: ApkPlainTextReferenceRewriter = ApkPlainTextReferenceRewriter(),
    private val dexPackageRewriter: DexPackageRewriter = DexPackageRewriter(),
    private val apkCloneVerifier: ApkCloneVerifier = ApkCloneVerifier()
) {

    fun buildUnsignedClone(
        sourceApk: File,
        workspaceRoot: File,
        plan: ClonePlan
    ): Result<UnsignedCloneArtifact> = runCatching {
        require(sourceApk.exists() && sourceApk.isFile) { "Source APK does not exist." }
        require(plan.sourcePackage.isNotBlank()) { "Source package is empty." }
        require(plan.clonePackage.isNotBlank()) { "Clone package is empty." }
        require(plan.cloneName.isNotBlank()) { "Clone name is empty." }

        ManifestTransformer().validate(
            ManifestTransformRequest(
                sourceApk = sourceApk,
                sourcePackage = plan.sourcePackage,
                clonePackage = plan.clonePackage,
                cloneLabel = plan.cloneName
            )
        ).getOrThrow()

        val workspace = workspaceManager.create(workspaceRoot, sourceApk)
        workspaceManager.unpack(workspace)

        val manifestFile = File(workspace.unpackedDir, "AndroidManifest.xml")
        require(manifestFile.exists() && manifestFile.isFile) {
            "AndroidManifest.xml was not found in the APK."
        }

        val originalManifest = manifestFile.readBytes()
        val rewrite = manifestTransformer.rewrite(
            manifest = originalManifest,
            sourcePackage = plan.sourcePackage,
            clonePackage = plan.clonePackage,
            cloneLabel = plan.cloneName,
            versionName = plan.customVersionName,
            versionCode = plan.customVersionCode
        ).getOrThrow()

        manifestFile.writeBytes(rewrite.data)

        val resourcesFile = File(workspace.unpackedDir, "resources.arsc")
        val arscRewriteCount = if (resourcesFile.exists() && resourcesFile.isFile) {
            val arscRewrite = arscPackageRewriter.rewrite(
                arscBytes = resourcesFile.readBytes(),
                sourcePackage = plan.sourcePackage,
                clonePackage = plan.clonePackage
            )
            if (arscRewrite.packagesRewritten > 0) {
                resourcesFile.writeBytes(arscRewrite.data)
            }
            arscRewrite.packagesRewritten
        } else {
            0
        }

        val textRewrite = plainTextRewriter.rewrite(
            unpackedDir = workspace.unpackedDir,
            sourcePackage = plan.sourcePackage,
            clonePackage = plan.clonePackage
        )

        val dexRewriteCount = rewriteDexFiles(workspace.unpackedDir, plan)

        val output = workspaceManager.outputApk(
            workspace,
            plan.cloneName + "-unsigned.apk"
        )
        packUnsignedApk(workspace.unpackedDir, output)

        val verification = apkCloneVerifier.verify(
            apkFile = output,
            sourcePackage = plan.sourcePackage,
            clonePackage = plan.clonePackage
        )
        require(verification.verified) {
            "Final APK verification failed: manifestPackage=${verification.manifestPackage}, " +
                "sourceDescriptorOccurrences=${verification.sourceDescriptorOccurrences}, " +
                "cloneDescriptorOccurrences=${verification.cloneDescriptorOccurrences}, " +
                "dexFilesChecked=${verification.dexFilesChecked}"
        }

        UnsignedCloneArtifact(
            apkFile = output,
            sourcePackage = plan.sourcePackage,
            clonePackage = plan.clonePackage,
            cloneName = plan.cloneName,
            manifestChanged = rewrite.packageChanged ||
                rewrite.labelChanged ||
                arscRewriteCount > 0 ||
                textRewrite.replacements > 0 ||
                dexRewriteCount > 0
        )
    }

    private fun rewriteDexFiles(unpackedDir: File, plan: ClonePlan): Int {
        var replacements = 0
        unpackedDir.walkTopDown()
            .filter { it.isFile && it.name.matches(Regex("classes([2-9]|[1-9][0-9]+)?\\.dex")) }
            .forEach { dex ->
                replacements += dexPackageRewriter.rewrite(
                    dex,
                    plan.sourcePackage,
                    plan.clonePackage
                ).getOrThrow().replacements
            }
        return replacements
    }

    private fun packUnsignedApk(sourceDir: File, outputApk: File) {
        require(sourceDir.exists() && sourceDir.isDirectory) { "Unpacked APK directory is missing." }
        outputApk.parentFile?.mkdirs()

        val countingOut = CountingOutputStream(FileOutputStream(outputApk))
        ZipOutputStream(countingOut).use { zip ->
            sourceDir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(sourceDir).invariantSeparatorsPath }
                .forEach { file ->
                    val relative = file.relativeTo(sourceDir).invariantSeparatorsPath
                    if (isOldSignatureEntry(relative)) return@forEach

                    val entry = ZipEntry(relative).apply {
                        time = 0L
                    }
                    if (shouldStoreUncompressed(relative)) {
                        val bytes = file.readBytes()
                        val crc = CRC32().apply { update(bytes) }.value
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = bytes.size.toLong()
                        entry.crc = crc
                        val nameBytes = relative.toByteArray(Charsets.UTF_8)
                        val dataStart = countingOut.bytesWritten + 30L + nameBytes.size
                        val padding = ((4L - (dataStart % 4L)) % 4L).toInt()
                        if (padding > 0) {
                            entry.extra = ByteArray(padding)
                        }
                        zip.putNextEntry(entry)
                        zip.write(bytes)
                        zip.closeEntry()
                    } else {
                        zip.putNextEntry(entry)
                        FileInputStream(file).use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
        }
    }

    private fun shouldStoreUncompressed(path: String): Boolean =
        path == "resources.arsc" ||
            (path.startsWith("lib/") && path.endsWith(".so"))

    private fun isOldSignatureEntry(path: String): Boolean {
        if (!path.startsWith("META-INF/", ignoreCase = true)) return false
        val name = path.substringAfterLast('/').uppercase()
        return name.endsWith(".SF") ||
            name.endsWith(".RSA") ||
            name.endsWith(".DSA") ||
            name.endsWith(".EC") ||
            name == "MANIFEST.MF"
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten += 1L
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len.toLong()
        }
    }
}
