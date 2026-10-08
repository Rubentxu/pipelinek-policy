package com.pipelinek.policy.bundle

import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.ir.PolicyIrDocument

/** Fail-closed admission gate. */
object BundleVerifier {
    fun verify(bundle: PolicyBundle, capabilities: RuntimeCapabilities = RuntimeCapabilities()): VerifiedBundle {
        if (bundle.document.irVersion !in capabilities.irVersions) throw BundleRefusal.UnsupportedIrVersion("IR ${bundle.document.irVersion} is not supported")
        val missing = bundle.document.functions.filterNot { it in capabilities.functions }
        if (missing.isNotEmpty()) throw BundleRefusal.UnsupportedFunction(missing.joinToString())
        val semantic = CanonicalPolicyJson.semanticDigest(bundle.document)
        if (semantic != bundle.manifest.semanticDigest) throw BundleRefusal.ArtifactDigestMismatch("semantic digest mismatch")
        return VerifiedBundle(bundle, semantic)
    }
    fun verifyPacked(bundle: PolicyBundle, bytes: ByteArray, capabilities: RuntimeCapabilities = RuntimeCapabilities()): VerifiedBundle {
        if (!bytes.contentEquals(bundle.pack())) throw BundleRefusal.CorruptBundle("bundle bytes do not match deterministic pack")
        return verify(bundle, capabilities)
    }
}

data class VerifiedBundle(val bundle: PolicyBundle, val semanticDigest: String)
