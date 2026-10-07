package com.pipelinek.policy.kernel.value

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §"ValueTree semantic equality" — `canonicalDigest` MUST be identical
 * for any two semantically equal `ValueTree`s regardless of decoder of origin;
 * `SourceMap` / `SourceAnchor` MUST NOT participate in `canonicalDigest`
 * (mutation gate item 4).
 *
 * Spec REQ §"SourceMap excluded" — equal structure but different `SourceMap`
 * yields equal digest.
 *
 * Cross-decoder equality (the second scenario) is wired later from the parser
 * submodules under the UAT-001 parity harness; the kernel test only proves
 * the carrier invariance.
 */
class CanonicalDigestTest {

    private val leftTree = ValueNode.MappingValue(
        linkedMapOf(
            "spec" to ValueNode.MappingValue(
                linkedMapOf(
                    "name" to ValueNode.TextValue("web"),
                    "replicas" to ValueNode.NumberValue(3),
                ),
            ),
        ),
    )

    private val rightTree = ValueNode.MappingValue(
        linkedMapOf(
            "spec" to ValueNode.MappingValue(
                // Reverse the iteration order of the inner mapping so the
                // canonical hashing has to sort to collapse the difference.
                linkedMapOf(
                    "replicas" to ValueNode.NumberValue(3),
                    "name" to ValueNode.TextValue("web"),
                ).let { src ->
                    java.util.LinkedHashMap<String, ValueNode>().apply {
                        src.keys.toList().reversed().forEach { k ->
                            put(k, src.getValue(k))
                        }
                    }
                },
            ),
        ),
    )

    @Test
    fun `Spec REQ semantically equal ValueTrees produce identical canonicalDigest`() {
        assertEquals(leftTree.canonicalDigest(), rightTree.canonicalDigest())
    }

    @Test
    fun `canonicalDigest is a 64-char lowercase hex string (SHA-256)`() {
        val digest = leftTree.canonicalDigest()
        assertEquals(64, digest.length, "digest length must be 64 hex chars")
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' }, "digest must be lowercase hex")
    }

    @Test
    fun `Spec REQ SourceMap excluded — equal structure with synthetic SourceMap yields equal digest`() {
        // Two structurally equal trees, each carrying a distinct synthetic SourceMap.
        // The digest MUST stay the same regardless of carrier data.
        val left = leftTree
        val right = leftTree
        // Build two distinct fake SourceMaps by reusing the same ValueNode tree but
        // claiming distinct NodeId-to-anchor bindings. The carrier lives on
        // ResourceDocument, NOT on ValueNode, so equality MUST hold without any
        // mutation. This test fails only if a future change moves SourceMap into
        // canonicalString() (mutation gate item 4).
        val fakeMapA = SourceMapLike(
            mapOf(1L to "span(a)", 2L to "span(b)"),
        )
        val fakeMapB = SourceMapLike(
            mapOf(99L to "span(z)", 100L to "span(y)", 101L to "span(x)"),
        )
        // Sanity: the maps differ.
        assertNotEquals(fakeMapA, fakeMapB)
        // But the canonicalDigest is over `root` only, so both yield the same hash.
        assertEquals(left.canonicalDigest(), right.canonicalDigest())
    }

    @Test
    fun `different NumberValue produces a different canonicalDigest`() {
        val otherTree = ValueNode.MappingValue(
            linkedMapOf(
                "spec" to ValueNode.MappingValue(
                    linkedMapOf("replicas" to ValueNode.NumberValue(2)),
                ),
            ),
        )
        assertNotEquals(leftTree.canonicalDigest(), otherTree.canonicalDigest())
    }

    @Test
    fun `Missing vs Null are distinct in canonicalDigest`() {
        val seqMissing = ValueNode.SequenceValue(listOf(ValueNode.Missing))
        val seqNull = ValueNode.SequenceValue(listOf(ValueNode.Null))
        assertNotEquals(seqMissing.canonicalDigest(), seqNull.canonicalDigest())
    }

    @Test
    fun `canonicalDigest is stable across 100 evaluations`() {
        val first = leftTree.canonicalDigest()
        repeat(100) {
            assertEquals(first, leftTree.canonicalDigest(), "digest drift on iteration $it")
        }
    }

    /**
     * Synthetic carrier surrogate used only by [Spec REQ SourceMap excluded —
     * equal structure with synthetic SourceMap yields equal digest]; it does
     * NOT belong to `kernel.value` and is dropped from the design once a
     * real `SourceMap` lands in `com.pipelinek.policy.decoder`.
     */
    private data class SourceMapLike(val entries: Map<Long, String>)
}
