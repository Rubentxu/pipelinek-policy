package com.pipelinek.policy.decoder

/**
 * Spec REQ §ADDED REQ 1 (Decoder SPI contract) + ADR-0011 D-03..D-05.
 *
 * Total decoder contract:
 *   - `decode()` returns `DecodeResult.Ok(...)` or `DecodeResult.Refused(...)`.
 *   - It MUST NOT throw. All refusals land on `DecodeRefusal(code, anchor)`
 *     so the evaluator never has to wrap parsing logic.
 *   - `SourceMap` is a sibling of `root` and NEVER participates in
 *     `ValueNode.canonicalDigest()` (mutation gate item 4).
 */
enum class DecodeRefusalCode {
    MALFORMED,
    DUPLICATE_KEY,
    UNSUPPORTED_HOST_VALUE,
    SCHEMA_FROZEN,
    ALIAS_EXPANSION_EXCEEDED,
}

/**
 * Structured refusal returned by every decoder. The `anchor` is the closest
 * `SourceAnchor` the decoder could identify (may be `null` for fully
 * unparseable streams, e.g. an empty byte array).
 */
data class DecodeRefusal(
    val code: DecodeRefusalCode,
    val anchor: SourceAnchor? = null,
)

/**
 * Outcome of `decode()`. Multi-document streams yield multiple `ResourceDocument`s
 * in source order; single-document streams yield exactly one.
 */
sealed interface DecodeResult {
    data class Ok(val documents: List<ResourceDocument>) : DecodeResult
    data class Refused(val refusal: DecodeRefusal) : DecodeResult
}

/**
 * Spec REQ §"CSV modes" — `TEXT_ONLY` is the safe default per ADR-0011 D-05.
 * `INFER_WITH_SAMPLE(N)` is opt-in via `inferSampleSize > 0`.
 */
enum class CsvMode { WHOLE_DOCUMENT, EACH_ROW }

/**
 * Spec REQ §"Decoder SPI contract" — knobs each decoder honours. Defaults
 * keep the SPI boring: no CSV inference, no YAML alias games.
 */
data class DecodeOptions(
    val csvMode: CsvMode = CsvMode.WHOLE_DOCUMENT,
    val inferSampleSize: Int = 0,
    val yamlAliasCap: Int = 100,
) {
    init {
        require(inferSampleSize >= 0) { "inferSampleSize MUST be >= 0 (law: negative sample size is nonsense)" }
        require(yamlAliasCap >= 0) { "yamlAliasCap MUST be >= 0 (law: unbounded alias expansion is the cap's purpose)" }
    }
}

/**
 * Spec REQ §ADDED REQ 1 — describes the format and version of a decoder so
 * downstream tooling can detect duplicates and stamp receipts.
 */
data class DecoderDescriptor(
    val format: ResourceFormat,
    val version: String,
)

/**
 * Spec REQ §ADDED REQ 1 — total decoder contract. `descriptor` is a property,
 * not a method call, so the evaluator can fingerprint it cheaply.
 */
interface ResourceDecoder {
    val descriptor: DecoderDescriptor

    fun decode(bytes: ByteArray, options: DecodeOptions = DecodeOptions()): DecodeResult
}

/**
 * Spec REQ §"Parser contributors" — composition seam so consumers can ship
 * several `ResourceDecoder`s in one bundle and the runtime freezes the set
 * at startup (law 10: no global mutable registries).
 */
interface DecoderContributor {
    val decoders: List<ResourceDecoder>
}
