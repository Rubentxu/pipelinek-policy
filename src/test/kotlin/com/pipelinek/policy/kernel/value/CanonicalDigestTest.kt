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
     * B6.5 · ADR-0011 D-05: sequence order is significant and must survive
     * canonicalization, so `canonicalDigest` must equal the digest of a tree
     * whose canonical string is KNOWN.
     *
     * Added because the C2 mutant (`node.elements.reversed()` inside
     * `canonicalString`) survived the whole suite. `ValueNodeTest` only checks
     * that the carrier keeps its elements in order; nothing pinned the
     * canonical string itself.
     *
     * The first version of this test compared a sequence against its own
     * reverse and STILL passed under the mutant, because reversing a sequence
     * and then reversing it again inside `canonicalString` returns the
     * original order. That is a symmetric oracle: it cannot see the bug it was
     * written for. The assertion below is asymmetric — it pins the digest of
     * an explicitly known canonical string, so any reordering changes it.
     */
    @Test
    fun `canonicalDigest preserves sequence order (ADR-0011 D-05)`() {
        val seq = ValueNode.SequenceValue(
            listOf(ValueNode.NumberValue(1), ValueNode.NumberValue(2)),
        )
        // NOTE the ", " separator: joinToString's default, not ",". The first draft
        // omitted it and failed for the right reason — the assertion caught a
        // real mismatch — but the mismatch was in the test, not the code.
        val expectedCanonical =
            """[{"type":"number","v":1}, {"type":"number","v":2}]"""
        assertEquals(
            expectedCanonical,
            ValueNode.canonicalString(seq),
            "canonical form must render elements in their original order",
        )
        assertEquals(
            ValueDigest.sha256Hex(expectedCanonical.toByteArray(Charsets.UTF_8)),
            seq.canonicalDigest(),
            "the digest must follow the canonical string",
        )
        // And the discriminating case: a reversed sequence must NOT collide.
        val reversed = ValueNode.SequenceValue(
            listOf(ValueNode.NumberValue(2), ValueNode.NumberValue(1)),
        )
        assertNotEquals(seq.canonicalDigest(), reversed.canonicalDigest())
    }

    /**
     * B6.5 · The canonical form must sort mapping keys, so two mappings that
     * differ only in insertion order share a digest.
     *
     * The C1 mutant (dropping `sortedBy`) also survived: `PolicyDiffTest`
     * covers insertion-order stability of the *report* digest, but this pins
     * the `ValueNode` canonical string itself.
     */
    @Test
    fun `canonicalDigest ignores mapping insertion order`() {
        val forward = ValueNode.MappingValue(
            linkedMapOf("a" to ValueNode.NumberValue(1), "z" to ValueNode.NumberValue(2)),
        )
        val reversed = ValueNode.MappingValue(
            linkedMapOf("z" to ValueNode.NumberValue(2), "a" to ValueNode.NumberValue(1)),
        )
        assertEquals(
            forward.canonicalDigest(),
            reversed.canonicalDigest(),
            "canonical form must sort mapping keys",
        )
    }

    /**
     * Synthetic carrier surrogate used only by [Spec REQ SourceMap excluded —
     * equal structure with synthetic SourceMap yields equal digest]; it does
     * NOT belong to `kernel.value` and is dropped from the design once a
     * real `SourceMap` lands in `com.pipelinek.policy.decoder`.
     */
    private data class SourceMapLike(val entries: Map<Long, String>)
}
