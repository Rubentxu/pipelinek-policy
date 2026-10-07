package com.pipelinek.policy.kernel.path

/**
 * Stable, canonical path identifier for a node inside a `ValueTree`.
 *
 * Spec REQ §"ValueTree and Selector":
 *   - Path segments are immutable dotted strings.
 *   - Two paths with the same segment sequence are equal (and hash-equal)
 *     regardless of construction order.
 *   - `DocumentPath.ROOT` is the singleton anchor; its `parent()` returns
 *     itself (no exceptions thrown from the path API).
 *   - `toString()` produces a dotted, human-readable form for diagnostics.
 *
 * This type is intentionally a value class over a `List<String>` to keep
 * equality and hashing cheap while preserving immutability.
 */
@JvmInline
value class DocumentPath private constructor(private val pathSegments: List<String>) {

    /** Number of segments (root has 0). */
    val length: Int get() = pathSegments.size

    /** Read-only view of the segments in declaration order. */
    val segments: List<String> get() = pathSegments

    /** Returns a new path extended with one more segment. */
    fun child(segment: String): DocumentPath = DocumentPath(pathSegments + segment)

    /**
     * Returns the parent path, dropping the last segment. The root path is its
     * own parent (path API never throws).
     */
    fun parent(): DocumentPath =
        if (pathSegments.isEmpty()) this else DocumentPath(pathSegments.dropLast(1))

    /** Dotted, human-readable form. Root renders as the empty string. */
    override fun toString(): String = pathSegments.joinToString(separator = ".")

    companion object {
        /** Singleton anchor for the document root. */
        val ROOT: DocumentPath = DocumentPath(emptyList())
    }
}
