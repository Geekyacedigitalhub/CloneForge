package com.cloneforge.app

data class ClonePlan(
    val sourcePackage: String,
    val clonePackage: String,
    val cloneName: String,
    val cloneIndex: Int,
    val customVersionName: String? = null,
    val customVersionCode: Int? = null,
    val iconTint: IconTintOption = IconTintOption.NONE
)

object ClonePlanner {
    fun create(
        sourcePackage: String,
        cloneName: String,
        cloneIndex: Int,
        customVersionName: String? = null,
        customVersionCode: Int? = null,
        iconTint: IconTintOption = IconTintOption.NONE
    ): ClonePlan {
        require(cloneName.isNotBlank()) { "Clone name cannot be empty." }
        require(cloneIndex > 0) { "Clone index must be greater than zero." }
        require(customVersionCode == null || customVersionCode > 0) {
            "Custom version code must be greater than zero."
        }

        val clonePackage = PackageIdentity.createClonePackage(sourcePackage, cloneIndex)

        return ClonePlan(
            sourcePackage = sourcePackage,
            clonePackage = clonePackage,
            cloneName = cloneName.trim(),
            cloneIndex = cloneIndex,
            customVersionName = customVersionName?.trim()?.ifBlank { null },
            customVersionCode = customVersionCode,
            iconTint = iconTint
        )
    }

    fun createWithSuffix(
        sourcePackage: String,
        cloneName: String,
        suffix: String,
        cloneIndex: Int,
        customVersionName: String? = null,
        customVersionCode: Int? = null,
        iconTint: IconTintOption = IconTintOption.NONE
    ): ClonePlan {
        require(cloneName.isNotBlank()) { "Clone name cannot be empty." }
        require(cloneIndex > 0) { "Clone index must be greater than zero." }
        require(customVersionCode == null || customVersionCode > 0) {
            "Custom version code must be greater than zero."
        }

        val clonePackage = PackageIdentity.validate(sourcePackage, suffix).getOrThrow()

        return ClonePlan(
            sourcePackage = sourcePackage,
            clonePackage = clonePackage,
            cloneName = cloneName.trim(),
            cloneIndex = cloneIndex,
            customVersionName = customVersionName?.trim()?.ifBlank { null },
            customVersionCode = customVersionCode,
            iconTint = iconTint
        )
    }
}
