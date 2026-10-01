package com.cloneforge.app

import java.security.MessageDigest

object PackageIdentity {

    private val segmentRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun validate(basePackage: String, suffix: String): Result<String> {
        if (basePackage.isBlank()) {
            return Result.failure(IllegalArgumentException("Base package cannot be empty."))
        }

        val baseParts = basePackage.split(".")
        if (baseParts.size < 2 || baseParts.any { !segmentRegex.matches(it) }) {
            return Result.failure(
                IllegalArgumentException("Invalid Android package name: $basePackage")
            )
        }

        val cleanSuffix = suffix.trim().removePrefix(".")
        if (cleanSuffix.isBlank()) {
            return Result.failure(IllegalArgumentException("Clone suffix cannot be empty."))
        }

        val suffixParts = cleanSuffix.split(".")
        if (suffixParts.any { !segmentRegex.matches(it) }) {
            return Result.failure(
                IllegalArgumentException("Invalid clone suffix: $suffix")
            )
        }

        return Result.success("$basePackage.$cleanSuffix")
    }

    fun deterministicSuffix(sourcePackage: String, cloneIndex: Int): String {
        require(cloneIndex > 0) { "cloneIndex must be greater than zero" }

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
}
