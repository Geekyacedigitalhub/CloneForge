package com.cloneforge.app

data class ClonePlan(
    val sourcePackage: String,
    val clonePackage: String,
    val cloneName: String,
    val cloneIndex: Int
)

object ClonePlanner {
    fun create(sourcePackage: String, cloneName: String, cloneIndex: Int): ClonePlan {
        require(cloneName.isNotBlank()) { "Clone name cannot be empty." }
        require(cloneIndex > 0) { "Clone index must be greater than zero." }

        val clonePackage = PackageIdentity.createClonePackage(sourcePackage, cloneIndex)

        return ClonePlan(
            sourcePackage = sourcePackage,
            clonePackage = clonePackage,
            cloneName = cloneName.trim(),
            cloneIndex = cloneIndex
        )
    }

    fun createWithSuffix(
        sourcePackage: String,
        cloneName: String,
        suffix: String,
        cloneIndex: Int
    ): ClonePlan {
        require(cloneName.isNotBlank()) { "Clone name cannot be empty." }
        require(cloneIndex > 0) { "Clone index must be greater than zero." }

        val clonePackage = PackageIdentity.validate(sourcePackage, suffix).getOrThrow()

        return ClonePlan(
            sourcePackage = sourcePackage,
            clonePackage = clonePackage,
            cloneName = cloneName.trim(),
            cloneIndex = cloneIndex
        )
    }
}
