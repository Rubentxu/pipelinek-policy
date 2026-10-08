package com.pipelinek.policy.bundle

import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.ir.PolicyIrDocument
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

private const val NAME_LEN_SIZE = 4
private const val SIZE_LEN_SIZE = 8

data class RuntimeCapabilities(val functions: Set<String> = emptySet(), val irVersions: Set<Int> = setOf(1))
data class BundleManifest(
    val bundleId: String,
    val bundleVersion: String,
    val semanticDigest: String,
    val artifactDigest: String,
    val requiredFunctions: List<String>,
    val irVersion: Int = 1,
)

data class PolicySourceMap(val refs: Map<String, com.pipelinek.policy.ir.PolicySourceRef>) {
    fun lookup(id: String) = refs[id]
}

data class PolicyBundle(
    val document: PolicyIrDocument,
    val sourceMap: PolicySourceMap = PolicySourceMap(document.sourceRefs),
    val metadata: Map<String, String> = emptyMap(),
) {
    val manifest: BundleManifest
        get() = BundleManifest(
            document.policySet.id, "1",
            CanonicalPolicyJson.semanticDigest(document),
            artifactDigest(), document.functions.sorted(), document.irVersion,
        )

    fun pack(): ByteArray {
        val entries = sortedMapOf(
            "manifest.json" to manifestJson().toByteArray(),
            "policy.ir.json" to CanonicalPolicyJson.encode(document),
            "policy-source-map.txt" to sourceBytes(),
            "metadata.txt" to metadataBytes(),
        )
        val out = ByteArrayOutputStream()
        out.write("PKB1".toByteArray())
        entries.forEach { (name, bytes) ->
            val n = name.toByteArray()
            out.write(ByteBuffer.allocate(NAME_LEN_SIZE).putInt(n.size).array())
            out.write(n)
            out.write(ByteBuffer.allocate(SIZE_LEN_SIZE).putLong(bytes.size.toLong()).array())
            out.write(bytes)
        }
        return out.toByteArray()
    }

    internal fun manifestJson() =
        "{\"bundleId\":\"${manifest.bundleId}\",\"bundleVersion\":\"${manifest.bundleVersion}\"" +
            ",\"semanticDigest\":\"${manifest.semanticDigest}\",\"artifactDigest\":\"${manifest.artifactDigest}\"" +
            ",\"requiredFunctions\":[${manifest.requiredFunctions.joinToString(",") { "\"$it\"" }}]" +
            ",\"irVersion\":${manifest.irVersion}}"

    private fun sourceBytes() = sourceMap.refs.toSortedMap().entries.joinToString("\n") {
        "${it.key}=${it.value.file}:${it.value.startLine}:${it.value.startColumn}" +
            "-${it.value.endLine}:${it.value.endColumn}:${it.value.symbol}"
    }.toByteArray()

    private fun metadataBytes() = metadata.toSortedMap().entries.joinToString("\n") {
        "${it.key}=${it.value}"
    }.toByteArray()

    private fun artifactDigest() = sha256(CanonicalPolicyJson.encode(document) + metadataBytes())

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

sealed class BundleRefusal(message: String) : IllegalArgumentException(message) {
    class CorruptBundle(message: String, cause: Throwable? = null) : BundleRefusal(message) {
        init { cause?.let { initCause(it) } }
    }
    class UnsupportedIrVersion(message: String) : BundleRefusal(message)
    class UnsupportedFunction(message: String) : BundleRefusal(message)
    class ArtifactDigestMismatch(message: String) : BundleRefusal(message)
}
