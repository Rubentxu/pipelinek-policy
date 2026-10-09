package com.pipelinek.policy.bundle

import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.ir.IrAdmissionLimits
import com.pipelinek.policy.ir.PolicyIrDocument
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

private const val JSON_CONTROL_CHARACTER_LIMIT = 0x20

private const val NAME_LEN_SIZE = 4
private const val SIZE_LEN_SIZE = 8

data class RuntimeCapabilities(val functions: Set<String> = emptySet(), val irVersions: Set<Int> = setOf(1))

/** Configurable limits for untrusted PKB1 bundles, bounded by hard ceilings. */
data class BundleAdmissionLimits(
    val maxPackedBytes: Int = DEFAULT_MAX_PACKED_BYTES,
    val maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
    val ir: IrAdmissionLimits = IrAdmissionLimits.DEFAULT,
) {
    init {
        require(maxPackedBytes in 1..MAX_PACKED_BYTES) { "maxPackedBytes out of bounds" }
        require(maxEntryBytes in 1..MAX_ENTRY_BYTES) { "maxEntryBytes out of bounds" }
        require(maxEntryBytes <= maxPackedBytes) { "maxEntryBytes cannot exceed maxPackedBytes" }
    }

    companion object {
        private const val DEFAULT_MAX_PACKED_BYTES = 32 * 1024 * 1024
        private const val DEFAULT_MAX_ENTRY_BYTES = 16 * 1024 * 1024
        const val MAX_PACKED_BYTES = 128 * 1024 * 1024
        const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
        val DEFAULT = BundleAdmissionLimits()
    }
}

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
            "manifest.json" to manifestJson().toByteArray(Charsets.UTF_8),
            "policy.ir.json" to CanonicalPolicyJson.encode(document),
            "policy-source-map.txt" to canonicalSourceBytes(),
            "metadata.txt" to canonicalMetadataBytes(),
        )
        val out = ByteArrayOutputStream()
        out.write("PKB1".toByteArray(Charsets.UTF_8))
        entries.forEach { (name, bytes) ->
            val n = name.toByteArray(Charsets.UTF_8)
            out.write(ByteBuffer.allocate(NAME_LEN_SIZE).putInt(n.size).array())
            out.write(n)
            out.write(ByteBuffer.allocate(SIZE_LEN_SIZE).putLong(bytes.size.toLong()).array())
            out.write(bytes)
        }
        return out.toByteArray()
    }

    internal fun manifestJson(current: BundleManifest = manifest): String = buildString {
        append("{\"bundleId\":").append(jsonQuote(current.bundleId))
        append(",\"bundleVersion\":").append(jsonQuote(current.bundleVersion))
        append(",\"semanticDigest\":").append(jsonQuote(current.semanticDigest))
        append(",\"artifactDigest\":").append(jsonQuote(current.artifactDigest))
        append(",\"requiredFunctions\":[")
        append(current.requiredFunctions.joinToString(",", transform = ::jsonQuote))
        append("],\"irVersion\":").append(current.irVersion).append('}')
    }

    internal fun canonicalSourceBytes() = sourceMap.refs.toSortedMap().entries.joinToString("\n") {
        "${it.key}=${it.value.file}:${it.value.startLine}:${it.value.startColumn}" +
            "-${it.value.endLine}:${it.value.endColumn}:${it.value.symbol}"
    }.toByteArray(Charsets.UTF_8)

    internal fun canonicalMetadataBytes() = metadata.toSortedMap().entries.joinToString("\n") {
        require(it.key.isNotEmpty() && it.key.none { char -> char == '=' || char == '\n' || char == '\r' }) {
            "metadata keys must be nonempty single-line text without '='"
        }
        require(it.value.none { char -> char == '\n' || char == '\r' }) {
            "metadata values must be single-line text"
        }
        "${it.key}=${it.value}"
    }.toByteArray(Charsets.UTF_8)

    private fun artifactDigest() = sha256(CanonicalPolicyJson.encode(document) + canonicalMetadataBytes())

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun jsonQuote(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < JSON_CONTROL_CHARACTER_LIMIT) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}

sealed class BundleRefusal(message: String) : IllegalArgumentException(message) {
    class CorruptBundle(message: String, cause: Throwable? = null) : BundleRefusal(message) {
        init { cause?.let { initCause(it) } }
    }
    class UnsupportedIrVersion(message: String) : BundleRefusal(message)
    class UnsupportedFunction(message: String) : BundleRefusal(message)
    class ArtifactDigestMismatch(message: String) : BundleRefusal(message)
}
