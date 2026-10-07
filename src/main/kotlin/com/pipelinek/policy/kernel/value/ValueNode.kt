package com.pipelinek.policy.kernel.value

import java.security.MessageDigest

/**
 * Canonical value tree node for the M1 pure-value kernel.
 *
 * Spec REQ §"ValueTree and Selector" mandates seven distinct type tags:
 * `Missing`, `Null`, `Text`, `Number`, `Boolean`, `Sequence`, `Mapping`.
 *
 * Laws:
 *   - `Missing` is a singleton and distinct from `Null`.
 *   - `Text` never coerces to `Number` (architectural law 9: no silent coercion).
 *   - `Mapping` equality is canonical: two maps with the same key/value content
 *     compare equal regardless of insertion order (canonical hashing requirement).
 */
sealed interface ValueNode {

    /** Type-tag-only enum; preserved for diagnostic display. */
    val type: Type

    enum class Type { MISSING, NULL, TEXT, NUMBER, BOOLEAN, SEQUENCE, MAPPING }

    /** Singleton: absence of a node. Distinct from `Null`. */
    data object Missing : ValueNode {
        override val type: Type = Type.MISSING
    }

    /** Singleton: explicit null assignment. Distinct from `Missing`. */
    data object Null : ValueNode {
        override val type: Type = Type.NULL
    }

    /** Textual content. Must NOT be coerced to `Number`. */
    data class TextValue(val text: String) : ValueNode {
        override val type: Type = Type.TEXT
    }

    /**
     * Numeric content. The `Number` carrier lets callers pick the precision they
     * need without the kernel forcing a specific Kotlin numeric subtype.
     */
    data class NumberValue(val number: kotlin.Number) : ValueNode {
        override val type: Type = Type.NUMBER
    }

    /** Boolean content. */
    data class BooleanValue(val boolean: Boolean) : ValueNode {
        override val type: Type = Type.BOOLEAN
    }

    /** Ordered sequence of nodes. Element order is significant. */
    data class SequenceValue(val elements: List<ValueNode>) : ValueNode {
        override val type: Type = Type.SEQUENCE
    }

    /**
     * Map of named children. Stored as `Map<String, ValueNode>` for canonical
     * semantics; equality is content-based (insertion-order independent) by
     * virtue of `Map.equals` over `LinkedHashMap` content equality.
     */
    data class MappingValue(val entries: Map<String, ValueNode>) : ValueNode {
        override val type: Type = Type.MAPPING
    }

    /**
     * Spec REQ §"ValueTree semantic equality": returns a SHA-256 digest that is
     * identical for any two semantically equal `ValueTree`s. `SourceMap` /
     * `SourceAnchor` are siblings on `ResourceDocument` and MUST NOT participate
     * in this digest (mutation gate item 4).
     */
    fun canonicalDigest(): String =
        ValueDigest.sha256Hex(canonicalString(this).toByteArray(Charsets.UTF_8))

    companion object {
        /**
         * Spec REQ §"ValueTree semantic equality": deterministic, insertion-order
         * independent stringification used as the input to [canonicalDigest].
         * `MappingValue` keys are sorted alphabetically; element order in
         * `SequenceValue` is preserved (per ADR-0011 D-05 + spec REQ §Mapping
         * semantic equality).
         */
        fun canonicalString(node: ValueNode): String = when (node) {
            ValueNode.Missing -> "{\"type\":\"missing\"}"
            ValueNode.Null -> "{\"type\":\"null\"}"
            is ValueNode.TextValue -> "{\"type\":\"text\",\"v\":\"${escape(node.text)}\"}"
            is ValueNode.NumberValue -> "{\"type\":\"number\",\"v\":${node.number.toString()}}"
            is ValueNode.BooleanValue -> "{\"type\":\"boolean\",\"v\":${node.boolean}}"
            is ValueNode.SequenceValue -> node.elements
                .joinToString(prefix = "[", postfix = "]") { canonicalString(it) }
            is ValueNode.MappingValue -> {
                val sorted = node.entries.entries.sortedBy { it.key }
                sorted.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                    "\"${escape(k)}\":${canonicalString(v)}"
                }
            }
        }

        private fun escape(s: String): String =
            s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
    }
}

/**
 * Tiny helper that keeps SHA-256 hashing out of `Evaluator` and `ValueNode`'s
 * own surface area. Pure stdlib, deterministic, lowercase hex output.
 */
internal object ValueDigest {
    fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
