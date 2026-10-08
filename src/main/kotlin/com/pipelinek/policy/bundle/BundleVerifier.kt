package com.pipelinek.policy.bundle

import com.pipelinek.policy.ir.CanonicalPolicyJson
import java.nio.ByteBuffer

/** Fail-closed admission gate for typed bundles and external packed bytes. */
object BundleVerifier {
    private const val MAGIC = "PKB1"
    private const val MAGIC_SIZE = 4
    private const val ENTRY_COUNT = 4
    private const val NAME_LEN_SIZE = 4
    private const val SIZE_LEN_SIZE = 8
    private const val MAX_NAME_LEN = 256

    private val ENTRY_NAMES = setOf("manifest.json", "metadata.txt", "policy-source-map.txt", "policy.ir.json")

    fun verify(bundle: PolicyBundle, capabilities: RuntimeCapabilities = RuntimeCapabilities()): VerifiedBundle {
        if (bundle.document.irVersion !in capabilities.irVersions) {
            throw BundleRefusal.UnsupportedIrVersion("IR ${bundle.document.irVersion} is not supported")
        }
        val missing = bundle.document.functions.filterNot { it in capabilities.functions }
        if (missing.isNotEmpty()) throw BundleRefusal.UnsupportedFunction(missing.joinToString())
        return verifiedOrMismatch(bundle)
    }

    private fun verifiedOrMismatch(bundle: PolicyBundle): VerifiedBundle {
        val semantic = CanonicalPolicyJson.semanticDigest(bundle.document)
        if (semantic != bundle.manifest.semanticDigest) {
            throw BundleRefusal.ArtifactDigestMismatch("semantic digest mismatch")
        }
        return VerifiedBundle(bundle, semantic, bundle.sourceMap)
    }

    fun verifyPacked(bytes: ByteArray, capabilities: RuntimeCapabilities = RuntimeCapabilities()): VerifiedBundle {
        try {
            return verifyPackedInternal(bytes, capabilities)
        } catch (e: BundleRefusal) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw BundleRefusal.CorruptBundle(e.message ?: "invalid packed bundle", e)
        }
    }

    private fun verifyPackedInternal(bytes: ByteArray, capabilities: RuntimeCapabilities): VerifiedBundle {
        require(
            bytes.size >= MAGIC_SIZE && bytes.copyOfRange(0, MAGIC_SIZE).contentEquals(MAGIC.toByteArray()),
        ) { "invalid pack magic" }
        var p = MAGIC_SIZE
        val entries = linkedMapOf<String, ByteArray>()
        repeat(ENTRY_COUNT) {
            val nameLen = readInt(bytes, p)
            p += NAME_LEN_SIZE
            require(nameLen in 1..MAX_NAME_LEN && p + nameLen <= bytes.size)
            val name = bytes.copyOfRange(p, p + nameLen).toString(Charsets.UTF_8)
            p += nameLen
            val size = readLong(bytes, p)
            p += SIZE_LEN_SIZE
            require(size >= 0 && size <= Int.MAX_VALUE.toLong() && p + size <= bytes.size)
            entries[name] = bytes.copyOfRange(p, p + size.toInt())
            p += size.toInt()
        }
        require(p == bytes.size && entries.keys == ENTRY_NAMES)
        val document = CanonicalPolicyJson.decode(entries.getValue("policy.ir.json"))
        val bundle = PolicyBundle(document, metadata = parseMetadata(entries.getValue("metadata.txt")))
        val manifest = entries.getValue("manifest.json").toString(Charsets.UTF_8)
        require(manifest.contains("\"semanticDigest\":\"${bundle.manifest.semanticDigest}\"")) {
            "manifest semantic digest mismatch"
        }
        require(manifest.contains("\"artifactDigest\":\"${bundle.manifest.artifactDigest}\"")) {
            "manifest artifact digest mismatch"
        }
        return verify(bundle, capabilities)
    }

    @Deprecated("Use verifyPacked(bytes, capabilities)")
    fun verifyPacked(
        bundle: PolicyBundle,
        bytes: ByteArray,
        capabilities: RuntimeCapabilities = RuntimeCapabilities(),
    ): VerifiedBundle {
        if (!bytes.contentEquals(bundle.pack())) {
            throw BundleRefusal.CorruptBundle("bundle bytes do not match deterministic pack")
        }
        return verify(bundle, capabilities)
    }

    private fun readInt(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, NAME_LEN_SIZE).int
    private fun readLong(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, SIZE_LEN_SIZE).long

    private fun parseMetadata(b: ByteArray) = b.toString(Charsets.UTF_8).lineSequence()
        .filter { it.isNotEmpty() }
        .associate { it.substringBefore('=') to it.substringAfter('=', "") }
}

data class VerifiedBundle(val bundle: PolicyBundle, val semanticDigest: String, val sourceMap: PolicySourceMap)
