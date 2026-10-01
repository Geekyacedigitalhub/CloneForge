package com.cloneforge.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

data class ApkSigningIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate
)

class ApkSignerService(private val context: Context) {

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "cloneforge-apk-signer"
    }

    fun sign(unsignedApk: File, signedApk: File): Result<File> = runCatching {
        require(unsignedApk.exists() && unsignedApk.isFile) { "Unsigned APK does not exist." }
        signedApk.parentFile?.mkdirs()

        val identity = getOrCreateIdentity()
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "CloneForge",
            identity.privateKey,
            listOf(identity.certificate)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .setOtherSignersSignaturesPreserved(false)
            .setCreatedBy("CloneForge")
            .build()
            .sign()

        require(signedApk.exists() && signedApk.length() > 0) {
            "APK signing completed without producing an output file."
        }

        val verification = ApkVerifier.Builder(signedApk)
            .setMinCheckedPlatformVersion(26)
            .build()
            .verify()
        require(verification.isVerified()) {
            "APK signature verification failed after signing."
        }
        require(verification.isVerifiedUsingV2Scheme() || verification.isVerifiedUsingV3Scheme()) {
            "APK does not have a verified v2 or v3 signature."
        }

        signedApk
    }

    private fun getOrCreateIdentity(): ApkSigningIdentity {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val now = System.currentTimeMillis()
            val start = Date(now - 24L * 60L * 60L * 1000L)
            val end = Date(now + 20L * 365L * 24L * 60L * 60L * 1000L)

            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                KEYSTORE
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setUserAuthenticationRequired(false)
                    .setKeyValidityStart(start)
                    .setKeyValidityForOriginationEnd(end)
                    .setCertificateSubject(X500Principal("CN=CloneForge"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(start)
                    .setCertificateNotAfter(end)
                    .build()
            )
            generator.generateKeyPair()
        }

        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: error("CloneForge signing private key is unavailable.")
        val certificate = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
            ?: error("CloneForge signing certificate is unavailable.")

        require(certificate.publicKey.algorithm.equals("RSA", ignoreCase = true)) {
            "CloneForge signing certificate is not RSA."
        }

        ApkSigningIdentity(privateKey, certificate)
    }
}