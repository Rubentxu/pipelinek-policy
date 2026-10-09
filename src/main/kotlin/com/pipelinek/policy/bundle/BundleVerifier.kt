package com.pipelinek.policy.bundle

import com.pipelinek.policy.ir.CanonicalPolicyJson
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Fail-closed admission gate for typed bundles and external packed bytes. */
object BundleVerifier {
    fun verify(bundle: PolicyBundle, capabilities: RuntimeCapabilities = RuntimeCapabilities()): VerifiedBundle =
        verify(bundle, capabilities, BundleAdmissionLimits.DEFAULT)

    fun verify(
        bundle: PolicyBundle,
        capabilities: RuntimeCapabilities,
        limits: BundleAdmissionLimits,
    ): VerifiedBundle = TypedBundleVerifier.verify(bundle, capabilities, limits)

    fun verifyPacked(bytes: ByteArray, capabilities: RuntimeCapabilities = RuntimeCapabilities()): VerifiedBundle =
        verifyPacked(bytes, capabilities, BundleAdmissionLimits.DEFAULT)

    fun verifyPacked(bytes: ByteArray, limits: BundleAdmissionLimits): VerifiedBundle =
        verifyPacked(bytes, RuntimeCapabilities(), limits)

    fun verifyPacked(
        bytes: ByteArray,
        capabilities: RuntimeCapabilities,
        limits: BundleAdmissionLimits,
    ): VerifiedBundle = PackedBundleReader.verify(bytes, capabilities, limits)

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
}

private object TypedBundleVerifier {
    fun verify(
        bundle: PolicyBundle,
        capabilities: RuntimeCapabilities,
        limits: BundleAdmissionLimits,
    ): VerifiedBundle {
        validateIrVersion(bundle, capabilities)
        validateSourceMap(bundle)
        validateMetadata(bundle)
        encodeAndAdmitIr(bundle, limits)
        validateFunctions(bundle, capabilities)
        return verifySemanticDigest(bundle)
    }

    private fun validateIrVersion(bundle: PolicyBundle, capabilities: RuntimeCapabilities) {
        if (bundle.document.irVersion !in capabilities.irVersions) {
            throw BundleRefusal.UnsupportedIrVersion("IR ${bundle.document.irVersion} is not supported")
        }
    }

    private fun validateSourceMap(bundle: PolicyBundle) {
        if (bundle.sourceMap.refs != bundle.document.sourceRefs) {
            throw BundleRefusal.CorruptBundle("source map differs from canonical IR source references")
        }
    }

    private fun validateMetadata(bundle: PolicyBundle) {
        try {
            bundle.canonicalMetadataBytes()
        } catch (e: IllegalArgumentException) {
            throw BundleRefusal.CorruptBundle(e.message ?: "invalid bundle metadata", e)
        }
    }

    private fun encodeAndAdmitIr(bundle: PolicyBundle, limits: BundleAdmissionLimits) {
        val encodedDocument = try {
            CanonicalPolicyJson.encode(bundle.document)
        } catch (e: IllegalArgumentException) {
            throw BundleRefusal.CorruptBundle(e.message ?: "IR encoding failed", e)
        }
        require(encodedDocument.size <= limits.ir.maxEncodedBytes) { "IR exceeds encoded byte budget" }
        try {
            CanonicalPolicyJson.decode(encodedDocument, limits.ir)
        } catch (e: IllegalArgumentException) {
            throw BundleRefusal.CorruptBundle(e.message ?: "IR admission failed", e)
        }
    }

    private fun validateFunctions(bundle: PolicyBundle, capabilities: RuntimeCapabilities) {
        val missing = bundle.document.functions.filterNot { it in capabilities.functions }
        if (missing.isNotEmpty()) throw BundleRefusal.UnsupportedFunction(missing.joinToString())
    }

    private fun verifySemanticDigest(bundle: PolicyBundle): VerifiedBundle {
        val semantic = CanonicalPolicyJson.semanticDigest(bundle.document)
        if (semantic != bundle.manifest.semanticDigest) {
            throw BundleRefusal.ArtifactDigestMismatch("semantic digest mismatch")
        }
        return VerifiedBundle(bundle, semantic, bundle.sourceMap)
    }
}

private object PackedBundleReader {
    private const val MAGIC = "PKB1"
    private const val MAGIC_SIZE = 4
    private const val ENTRY_COUNT = 4
    private const val NAME_LEN_SIZE = 4
    private const val SIZE_LEN_SIZE = 8
    private const val MAX_NAME_LEN = 256
    private val ENTRY_NAMES = setOf("manifest.json", "metadata.txt", "policy-source-map.txt", "policy.ir.json")

    fun verify(
        bytes: ByteArray,
        capabilities: RuntimeCapabilities,
        limits: BundleAdmissionLimits,
    ): VerifiedBundle = try {
        verifyInternal(bytes, capabilities, limits)
    } catch (e: BundleRefusal) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw BundleRefusal.CorruptBundle(e.message ?: "invalid packed bundle", e)
    }

    private fun verifyInternal(
        bytes: ByteArray,
        capabilities: RuntimeCapabilities,
        limits: BundleAdmissionLimits,
    ): VerifiedBundle {
        require(bytes.size <= limits.maxPackedBytes) { "packed bundle exceeds byte budget" }
        require(
            bytes.size >= MAGIC_SIZE && bytes.copyOfRange(0, MAGIC_SIZE).contentEquals(MAGIC.toByteArray()),
        ) { "invalid pack magic" }
        var position = MAGIC_SIZE
        val entries = linkedMapOf<String, ByteArray>()
        val names = mutableListOf<String>()
        repeat(ENTRY_COUNT) {
            val nameLength = readInt(bytes, position)
            position += NAME_LEN_SIZE
            require(nameLength in 1..MAX_NAME_LEN && position + nameLength <= bytes.size)
            val nameBytes = bytes.copyOfRange(position, position + nameLength)
            val name = strictUtf8(nameBytes)
            require(name.toByteArray(Charsets.UTF_8).contentEquals(nameBytes)) { "invalid UTF-8 entry name" }
            position += nameLength
            val entrySize = readLong(bytes, position)
            position += SIZE_LEN_SIZE
            require(
                entrySize >= 0 && entrySize <= limits.maxEntryBytes.toLong() &&
                    entrySize <= (bytes.size - position).toLong(),
            ) { "entry exceeds byte budget or pack bounds" }
            require(name !in entries) { "duplicate entry name" }
            entries[name] = bytes.copyOfRange(position, position + entrySize.toInt())
            names += name
            position += entrySize.toInt()
        }
        require(position == bytes.size && names == ENTRY_NAMES.sorted() && entries.keys == ENTRY_NAMES) {
            "invalid entry set, order, or trailing bytes"
        }
        val irBytes = entries.getValue("policy.ir.json")
        val document = CanonicalPolicyJson.decode(irBytes, limits.ir)
        val bundle = PolicyBundle(document, metadata = parseMetadata(entries.getValue("metadata.txt")))
        val irEncoding = BundleIrCanonicality.identify(irBytes, document)
        require(irEncoding != null) { "IR bytes do not match a supported canonical encoding" }
        require(entries.getValue("policy-source-map.txt").contentEquals(bundle.canonicalSourceBytes())) {
            "source map differs from canonical IR source references"
        }
        require(entries.getValue("metadata.txt").contentEquals(bundle.canonicalMetadataBytes())) {
            "metadata is not canonical or contains duplicate keys"
        }
        val manifestBytes = entries.getValue("manifest.json")
        require(BundleIrCanonicality.manifestMatches(irEncoding, irBytes, manifestBytes, bundle)) {
            "manifest differs from canonical recalculated manifest"
        }
        return TypedBundleVerifier.verify(bundle, capabilities, limits)
    }

    private fun readInt(bytes: ByteArray, position: Int): Int {
        require(position >= 0 && position <= bytes.size - NAME_LEN_SIZE) { "truncated entry name length" }
        return ByteBuffer.wrap(bytes, position, NAME_LEN_SIZE).int
    }

    private fun readLong(bytes: ByteArray, position: Int): Long {
        require(position >= 0 && position <= bytes.size - SIZE_LEN_SIZE) { "truncated entry size" }
        return ByteBuffer.wrap(bytes, position, SIZE_LEN_SIZE).long
    }

    private fun parseMetadata(bytes: ByteArray): Map<String, String> {
        val text = strictUtf8(bytes)
        if (text.isEmpty()) return emptyMap()
        val metadata = linkedMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "invalid metadata line" }
            val key = line.substring(0, separator)
            require(key !in metadata) { "duplicate metadata key" }
            metadata[key] = line.substring(separator + 1)
        }
        return metadata
    }

    private fun strictUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("invalid UTF-8", e)
    }
}

data class VerifiedBundle(val bundle: PolicyBundle, val semanticDigest: String, val sourceMap: PolicySourceMap)
