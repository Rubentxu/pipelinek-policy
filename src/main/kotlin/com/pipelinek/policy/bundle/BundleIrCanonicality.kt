package com.pipelinek.policy.bundle

import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.ir.LegacyPolicyIrJsonV1
import com.pipelinek.policy.ir.PolicyIrDocument
import java.security.MessageDigest

internal enum class BundleIrEncoding { CURRENT, LEGACY_V1 }

internal object BundleIrCanonicality {
    fun identify(bytes: ByteArray, document: PolicyIrDocument): BundleIrEncoding? {
        val legacyBytes = LegacyPolicyIrJsonV1.encode(document) ?: return null
        return when {
            bytes.contentEquals(CanonicalPolicyJson.encode(document)) -> BundleIrEncoding.CURRENT
            bytes.contentEquals(legacyBytes) -> BundleIrEncoding.LEGACY_V1
            else -> null
        }
    }

    fun manifestMatches(
        encoding: BundleIrEncoding,
        bytes: ByteArray,
        manifestBytes: ByteArray,
        bundle: PolicyBundle,
    ): Boolean = when (encoding) {
        BundleIrEncoding.CURRENT -> manifestBytes.contentEquals(bundle.manifestJson().toByteArray(Charsets.UTF_8))
        BundleIrEncoding.LEGACY_V1 -> legacyManifestMatches(bytes, manifestBytes, bundle)
    }

    private fun legacyManifestMatches(
        irBytes: ByteArray,
        manifestBytes: ByteArray,
        bundle: PolicyBundle,
    ): Boolean {
        val legacyManifest = bundle.manifest.copy(
            semanticDigest = sha256(irBytes),
            artifactDigest = sha256(irBytes + bundle.canonicalMetadataBytes()),
        )
        return manifestBytes.contentEquals(bundle.manifestJson(legacyManifest).toByteArray(Charsets.UTF_8))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
