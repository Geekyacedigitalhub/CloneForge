package com.cloneforge.app

import java.security.MessageDigest

object PackageIdentity {

    private const val MAX_PACKAGE_LENGTH = 255
    private const val MAX_SEGMENT_LENGTH = 255
    private val segmentRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun validate(basePackage: String, suffix: String): Result<String> {
        if (basePackage.isBlank()) {
            return Result.failure(IllegalArgumentException("Base package cannot be empty."))
        }

        val normalizedBase = basePackage.trim().removeSuffix(".")
        val baseParts = normalizedBase.split(".")
        if (!isValidParts(baseParts)) {
            return Result.failure(
                IllegalArgumentException("Invalid Android package name: $basePackage")
            )
        }

        val cleanSuffix = suffix.trim().removePrefix(".").removeSuffix(".")
        if (cleanSuffix.isBlank()) {
            return Result.failure(IllegalArgumentException("Clone suffix cannot be empty."))
        }

        val suffixParts = cleanSuffix.split(".")
        if (!isValidParts(suffixParts)) {
            return Result.failure(
                IllegalArgumentException("Invalid clone suffix: $suffix")
            )
        }

        val clonePackage = "$normalizedBase.$cleanSuffix"
        if (clonePackage.length > MAX_PACKAGE_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Clone package name is too long (maximum $MAX_PACKAGE_LENGTH characters).")
            )
        }

        return Result.success(clonePackage)
    }

    fun deterministicSuffix(sourcePackage: String, cloneIndex: Int): String {
        require(cloneIndex > 0) { "cloneIndex must be greater than zero." }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourcePackage.toByteArray(Charsets.UTF_8))

        val hash = digest.take(4).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

        return "clone$cloneIndex$hash"
    }

    fun createClonePackage(sourcePackage: String, cloneIndex: Int): String {
        val suffix = deterministicSuffix(sourcePackage, cloneIndex)
        return validate(sourcePackage, suffix).getOrThrow()
    }

    private fun isValidParts(parts: List<String>): Boolean =
        parts.isNotEmpty() &&
            parts.all { it.isNotEmpty() && it.length <= MAX_SEGMENT_LENGTH && segmentRegex.matches(it) }
}
