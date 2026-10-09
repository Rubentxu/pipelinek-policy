package com.pipelinek.policy.cli

import com.pipelinek.policy.decoder.NodeId
import com.pipelinek.policy.decoder.ResourceAttributes
import com.pipelinek.policy.decoder.ResourceDocument
import com.pipelinek.policy.decoder.ResourceFormat
import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.decoder.SourceMap
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ResourceId
import com.pipelinek.policy.kernel.value.ValueNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B3 — PhysicalLocator unit contract.
 *
 * Falsification: a locator that only descends the target branch (instead of
 * walking the whole tree pre-order) miscounts NodeIds and resolves the wrong
 * anchor or none. The `b` case below exists precisely to catch that mutation.
 */
class PhysicalLocatorTest {

    private fun doc(vararg spans: Pair<Long, SourceAnchor>): ResourceDocument {
        val entries = spans.associate { (id, anchor) -> NodeId(id) to anchor }
        return ResourceDocument(
            id = ResourceId("test://doc"),
            format = ResourceFormat.JSON,
            root = root(),
            sourceMap = SourceMap(entries),
            attributes = ResourceAttributes(),
        )
    }

    /** `{"a":{"x":1},"b":2}` — pre-order ids: root=0, a=1, x=2, b=3. */
    private fun root(): ValueNode = ValueNode.MappingValue(
        linkedMapOf(
            "a" to ValueNode.MappingValue(
                linkedMapOf("x" to ValueNode.NumberValue(1L)),
            ),
            "b" to ValueNode.NumberValue(2L),
        ),
    )

    @Test
    fun `resolves a nested mapping path to its pre-order anchor`() {
        val document = doc(
            0L to SourceAnchor.TextSpan(1, 1, 1, 20),
            1L to SourceAnchor.TextSpan(1, 1, 1, 12),
            2L to SourceAnchor.TextSpan(1, 6, 1, 7),
            3L to SourceAnchor.TextSpan(1, 15, 1, 16),
        )
        val anchor = PhysicalLocator.anchorFor(document, DocumentPath.ROOT.child("a").child("x"))
        assertEquals(SourceAnchor.TextSpan(1, 6, 1, 7), anchor)
    }

    @Test
    fun `resolves a sibling AFTER a nested branch, proving full pre-order walk`() {
        val document = doc(
            0L to SourceAnchor.TextSpan(1, 1, 1, 20),
            1L to SourceAnchor.TextSpan(1, 1, 1, 12),
            2L to SourceAnchor.TextSpan(1, 6, 1, 7),
            3L to SourceAnchor.TextSpan(1, 15, 1, 16),
        )
        // `b` follows the whole `a` subtree: only a FULL walk assigns id 3.
        val anchor = PhysicalLocator.anchorFor(document, DocumentPath.ROOT.child("b"))
        assertEquals(SourceAnchor.TextSpan(1, 15, 1, 16), anchor)
    }

    @Test
    fun `sequence index path resolves to the element anchor`() {
        val document = ResourceDocument(
            id = ResourceId("test://seq"),
            format = ResourceFormat.JSON,
            root = ValueNode.SequenceValue(
                listOf(
                    ValueNode.TextValue("first"),
                    ValueNode.TextValue("second"),
                ),
            ),
            sourceMap = SourceMap(
                mapOf(
                    NodeId(0) to SourceAnchor.TextSpan(1, 1, 1, 18),
                    NodeId(1) to SourceAnchor.TextSpan(1, 2, 1, 8),
                    NodeId(2) to SourceAnchor.TextSpan(1, 10, 1, 17),
                ),
            ),
            attributes = ResourceAttributes(),
        )
        val anchor = PhysicalLocator.anchorFor(document, DocumentPath.ROOT.child("1"))
        assertEquals(SourceAnchor.TextSpan(1, 10, 1, 17), anchor)
    }

    @Test
    fun `root path resolves to the root anchor`() {
        val document = doc(0L to SourceAnchor.TextSpan(1, 1, 1, 20))
        val anchor = PhysicalLocator.anchorFor(document, DocumentPath.ROOT)
        assertTrue(anchor is SourceAnchor.TextSpan && anchor.startLine == 1L)
    }

    @Test
    fun `absent path resolves to null (no synthetic anchor)`() {
        val document = doc(0L to SourceAnchor.TextSpan(1, 1, 1, 20))
        assertNull(PhysicalLocator.anchorFor(document, DocumentPath.ROOT.child("nope")))
    }
}
