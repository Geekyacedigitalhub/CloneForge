package com.cloneforge.app

import com.android.apksig.ApkVerifier
import java.io.File

data class ApkSignatureVerification(
    val verified: Boolean,
    val verifiedUsingV2: Boolean,
    val verifiedUsingV3: Boolean,
    val error: String? = null
)

class ApkSignatureVerifier {
    fun verify(apkFile: File): ApkSignatureVerification {
        if (!apkFile.exists() || !apkFile.isFile) {
            return ApkSignatureVerification(
                verified = false,
                verifiedUsingV2 = false,
                verifiedUsingV3 = false,
                error = "APK file does not exist."
            )
        }

        return runCatching {
            val result = ApkVerifier.Builder(apkFile)
                .setMinCheckedPlatformVersion(26)
                .build()
                .verify()

            val verifiedUsingV2 = result.isVerifiedUsingV2Scheme()
            val verifiedUsingV3 = result.isVerifiedUsingV3Scheme()

            ApkSignatureVerification(
                verified = result.isVerified() && (verifiedUsingV2 || verifiedUsingV3),
                verifiedUsingV2 = verifiedUsingV2,
                verifiedUsingV3 = verifiedUsingV3,
                error = if (result.isVerified()) null
                else "APK signature verification failed."
            )
        }.getOrElse { throwable ->
            ApkSignatureVerification(
                verified = false,
                verifiedUsingV2 = false,
                verifiedUsingV3 = false,
                error = throwable.message ?: "APK signature verification failed."
            )
        }
    }
}
