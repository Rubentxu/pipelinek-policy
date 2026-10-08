package com.pipelinek.policy.bundle

import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.ir.PolicyIrDocument
import java.security.MessageDigest

data class RuntimeCapabilities(val functions: Set<String> = emptySet(), val irVersions: Set<Int> = setOf(1))
data class BundleManifest(val bundleId: String, val bundleVersion: String, val semanticDigest: String, val artifactDigest: String, val requiredFunctions: List<String>, val irVersion: Int = 1)
data class PolicySourceMap(val refs: Map<String, com.pipelinek.policy.ir.PolicySourceRef>) {
    fun lookup(id: String) = refs[id]
}
data class PolicyBundle(val document: PolicyIrDocument, val sourceMap: PolicySourceMap = PolicySourceMap(emptyMap()), val metadata: Map<String, String> = emptyMap()) {
    val manifest: BundleManifest
        get() = BundleManifest(document.policySet.id, "1", CanonicalPolicyJson.semanticDigest(document), artifactDigest(), document.functions, document.irVersion)
    fun pack(): ByteArray {
        val ir = CanonicalPolicyJson.encode(document)
        val source = sourceMap.refs.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value.file}:${it.value.symbol}" }.toByteArray()
        val meta = metadata.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray()
        return listOf("policy.ir.json" to ir, "policy-source-map.txt" to source, "metadata.txt" to meta)
            .sortedBy { it.first }.joinToString("\n") { (name, bytes) -> "$name:${bytes.size}:${sha256(bytes)}" }.toByteArray()
    }
    private fun artifactDigest() = sha256(packWithoutManifest())
    private fun packWithoutManifest(): ByteArray = CanonicalPolicyJson.encode(document) + metadata.entries.sortedBy { it.key }.joinToString("\n").toByteArray()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

sealed class BundleRefusal(message: String) : IllegalArgumentException(message) {
    class CorruptBundle(message: String) : BundleRefusal(message)
    class UnsupportedIrVersion(message: String) : BundleRefusal(message)
    class UnsupportedFunction(message: String) : BundleRefusal(message)
    class ArtifactDigestMismatch(message: String) : BundleRefusal(message)
}
