package com.pipelinek.policy.bundle

import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.ir.LegacyPolicyIrJsonV1
import com.pipelinek.policy.ir.PolicyIrDocument
import java.security.MessageDigest

internal enum class BundleIrEncoding { CURRENT, LEGACY_V1 }

internal object BundleIrCanonicality {
    /**
     * Admission order is part of the contract, not an implementation detail.
     *
     * CURRENT is the canonical encoding and MUST be recognised on its own
     * bytes. LEGACY_V1 is consulted only when CURRENT does not match, because
     * `LegacyPolicyIrJsonV1.encode` legitimately refuses documents it cannot
     * reproduce byte-for-byte (collection predicates, structurally unstable
     * selectors). Evaluating it first made a perfectly valid CURRENT bundle
     * inadmissible whenever no historical encoding could be produced.
     */
    fun identify(bytes: ByteArray, document: PolicyIrDocument): BundleIrEncoding? {
        if (bytes.contentEquals(CanonicalPolicyJson.encode(document))) {
            return BundleIrEncoding.CURRENT
        }
        val legacyBytes: ByteArray? = LegacyPolicyIrJsonV1.encode(document)
        return when {
            legacyBytes == null -> null
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
