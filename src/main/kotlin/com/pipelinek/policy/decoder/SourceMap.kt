package com.pipelinek.policy.decoder

/**
 * Spec REQ §ADDED REQ 1 (Decoder SPI contract) — stable identity for a
 * decoded node. The `Long` carrier is opaque to consumers; submodules MUST
 * generate fresh ids per `decode()` invocation so ids never bleed across
 * documents in a multi-document stream.
 *
 * ADR-0011 D-01 keeps this in the pure-SPI package (no parser coords).
 */
@JvmInline
value class NodeId(val value: Long)

/**
 * Spec REQ §ADDED REQ 1 + §"SourceMap laws" — physical origin of a decoded
 * node. Carriers are siblings of `ValueNode`; they participate in violation
 * display but NEVER in `canonicalDigest` (mutation gate item 4).
 */
sealed interface SourceAnchor {
    /**
     * Text/byte range in a streaming source (JSON / YAML).
     * Coordinates are 1-indexed and the end is exclusive.
     */
    data class TextSpan(
        val startLine: Long,
        val startColumn: Long,
        val endLine: Long,
        val endColumn: Long,
    ) : SourceAnchor

    /** CSV cell — row 1 is the header, columns are 1-indexed. */
    data class Cell(val row: Long, val column: Long) : SourceAnchor

    /** DrawIO / graph element handle. Reserved for the post-M2 adapter. */
    data class Element(val elementId: String) : SourceAnchor

    /** Synthetic / inferred / host-derived location with no real source. */
    data class Logical(val path: String) : SourceAnchor
}

/**
 * Spec REQ §ADDED REQ 1 — `NodeId -> SourceAnchor` carrier. Lookups via
 * [of] return `null` for absent bindings (no exception; the carrier is
 * advisory). Decoders MUST register one entry per generated `NodeId`; the
 * `SourceMap` MAY be empty for synthetic roots.
 */
data class SourceMap(val entries: Map<NodeId, SourceAnchor>) {
    fun of(id: NodeId): SourceAnchor? = entries[id]

    companion object {
        val EMPTY: SourceMap = SourceMap(emptyMap())
    }
}
