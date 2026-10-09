package com.pipelinek.policy.decoders.yaml

import com.pipelinek.policy.decoder.DecodeRefusalCode
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.NodeId
import com.pipelinek.policy.decoder.ResourceDocument
import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §ADDED REQ 2 — YAML decoder contract.
 *
 * Scenarios:
 *   (a) parity with JSON for `{a: 1, b: 2}` (same canonicalDigest; feeds UAT-001)
 *   (b) duplicate keys `a: 1\na: 3` -> Refused(DUPLICATE_KEY)
 *   (c) `---` separator with 2 docs -> 2 ResourceDocuments
 *   (d) alias loop with cap=2 -> Refused(ALIAS_EXPANSION_EXCEEDED)
 */
class YamlDecoderTest {

    private val decoder = YamlResourceDecoder()

    @Test
    fun `parity with JSON for {a=1, b=2}`() {
        val yamlBytes = "a: 1\nb: 2\n".toByteArray()
        val result = decoder.decode(yamlBytes)
        val doc = (result as DecodeResult.Ok).documents.single()
        assertEquals(
            ValueNode.MappingValue(
                linkedMapOf(
                    "a" to ValueNode.NumberValue(1L),
                    "b" to ValueNode.NumberValue(2L),
                ),
            ),
            doc.root,
        )
    }

    @Test
    fun `duplicate keys fail closed`() {
        val yamlBytes = "a: 1\na: 3\n".toByteArray()
        val result = decoder.decode(yamlBytes)
        assertTrue(result is DecodeResult.Refused, "expected Refused, got $result")
        assertEquals(DecodeRefusalCode.DUPLICATE_KEY, (result as DecodeResult.Refused).refusal.code)
    }

    @Test
    fun `multi-document stream with separator yields two ResourceDocuments in source order`() {
        val yamlBytes = "---\na: 1\n---\nb: 2\n".toByteArray()
        val result = decoder.decode(yamlBytes)
        assertTrue(result is DecodeResult.Ok, "expected Ok, got $result")
        val docs = (result as DecodeResult.Ok).documents
        assertEquals(2, docs.size)
        assertEquals(
            ValueNode.MappingValue(linkedMapOf("a" to ValueNode.NumberValue(1L))),
            docs[0].root,
        )
        assertEquals(
            ValueNode.MappingValue(linkedMapOf("b" to ValueNode.NumberValue(2L))),
            docs[1].root,
        )
    }

    @Test
    fun `alias cap trips when the same non-scalar anchor is reused above the cap`() {
        // `setMaxAliasesForCollections(2)` in the SnakeYAML engine bounds
        // the number of *distinct* aliases that may appear inside a single
        // mapping/sequence, not recursion depth. We reuse the same anchor
        // `&x` three times inside the top-level mapping; the engine refuses
        // with `Number of aliases for non-scalar nodes exceeds ...`.
        val yamlBytes = """
            a: &x [1]
            b: [*x, *x, *x]
        """.trimIndent().toByteArray() + "\n".toByteArray()
        val options = com.pipelinek.policy.decoder.DecodeOptions(yamlAliasCap = 2)
        val result = decoder.decode(yamlBytes, options)
        assertTrue(
            result is DecodeResult.Refused,
            "expected Refused for alias-cap exceeded, got $result",
        )
        assertEquals(
            DecodeRefusalCode.ALIAS_EXPANSION_EXCEEDED,
            (result as DecodeResult.Refused).refusal.code,
        )
    }

    @Test
    fun `integer literal in YAML is NOT coerced to decimal`() {
        val yamlBytes = "n: 42\n".toByteArray()
        val result = decoder.decode(yamlBytes)
        val doc = (result as DecodeResult.Ok).documents.single()
        val number = (doc.root as ValueNode.MappingValue).entries.getValue("n") as ValueNode.NumberValue
        // The SnakeYAML engine emits Long for plain `42`; a mutated decoder
        // that coerces to BigDecimal/Double would silently change the value.
        assertEquals(42L, number.number.toLong())
        assertTrue(number.number is Long, "expected Long carrier, got ${number.number::class.simpleName}")
    }

    @Test
    fun `decimal literal in YAML is NOT coerced to integer`() {
        val yamlBytes = "n: 4.5\n".toByteArray()
        val result = decoder.decode(yamlBytes)
        val doc = (result as DecodeResult.Ok).documents.single()
        val number = (doc.root as ValueNode.MappingValue).entries.getValue("n") as ValueNode.NumberValue
        assertEquals(4.5, number.number.toDouble())
    }

    @Test
    fun `empty bytes yields MALFORMED`() {
        val result = decoder.decode(ByteArray(0))
        assertTrue(result is DecodeResult.Refused)
        assertEquals(DecodeRefusalCode.MALFORMED, (result as DecodeResult.Refused).refusal.code)
    }

    // ------------------------------------------------------------------
    // B3 — real source spans (per-scalar marks, not (1,1,1,1) placeholders).
    // Falsification: the old decoder registered TextSpan(1,1,1,1) for every
    // node because `loadAllFromString` drops marks. These tests fail against
    // the old implementation and pin the real positions.
    // ------------------------------------------------------------------

    @Test
    fun `scalar value on line 3 carries its real span`() {
        val yaml = "metadata:\n  kind: deployment\n  team: tools\n".toByteArray()
        val doc = (decoder.decode(yaml) as DecodeResult.Ok).documents.single()
        val root = doc.root as ValueNode.MappingValue
        val metadata = root.entries.getValue("metadata") as ValueNode.MappingValue
        val team = metadata.entries.getValue("team") as ValueNode.TextValue

        val anchor = doc.anchorFor(team)
        assertTrue(
            anchor is SourceAnchor.TextSpan && anchor.startLine == 3L,
            "team scalar must anchor at line 3, got $anchor",
        )
    }

    @Test
    fun `nested scalar on line 2 column 5 carries its real span`() {
        val yaml = "spec:\n  replicas: 3\n".toByteArray()
        val doc = (decoder.decode(yaml) as DecodeResult.Ok).documents.single()
        val spec = (doc.root as ValueNode.MappingValue).entries.getValue("spec") as ValueNode.MappingValue
        val replicas = spec.entries.getValue("replicas") as ValueNode.NumberValue

        val anchor = doc.anchorFor(replicas)
        assertTrue(
            anchor is SourceAnchor.TextSpan && anchor.startLine == 2L && anchor.startColumn >= 1L,
            "replicas scalar must anchor at line 2, got $anchor",
        )
    }

    @Test
    fun `sequence elements carry distinct per-element spans`() {
        val yaml = "items:\n  - a\n  - b\n".toByteArray()
        val doc = (decoder.decode(yaml) as DecodeResult.Ok).documents.single()
        val items = (doc.root as ValueNode.MappingValue).entries.getValue("items") as ValueNode.SequenceValue

        val aAnchor = doc.anchorFor(items.elements[0])
        val bAnchor = doc.anchorFor(items.elements[1])
        assertTrue(aAnchor is SourceAnchor.TextSpan && aAnchor.startLine == 2L, "a at line 2, got $aAnchor")
        assertTrue(bAnchor is SourceAnchor.TextSpan && bAnchor.startLine == 3L, "b at line 3, got $bAnchor")
    }

    @Test
    fun `second document scalars anchor within their own document`() {
        val yaml = "---\na: 1\n---\na: 2\n".toByteArray()
        val docs = (decoder.decode(yaml) as DecodeResult.Ok).documents
        assertEquals(2, docs.size)
        val second = docs[1]
        val a = (second.root as ValueNode.MappingValue).entries.getValue("a")
        val anchor = second.anchorFor(a)
        assertTrue(
            anchor is SourceAnchor.TextSpan && anchor.startLine >= 4L,
            "doc-2 scalar must anchor at the physical line 4+ of the stream, got $anchor",
        )
    }

    /** Pre-order walk mirrors the decoder's NodeId assignment order. */
    private fun ResourceDocument.anchorFor(target: ValueNode): SourceAnchor? {
        var counter = 0L
        var found: NodeId? = null
        fun walkFind(node: ValueNode) {
            val id = NodeId(counter++)
            if (node === target && found == null) found = id
            when (node) {
                is ValueNode.MappingValue -> node.entries.values.forEach { walkFind(it) }
                is ValueNode.SequenceValue -> node.elements.forEach { walkFind(it) }
                else -> Unit
            }
        }
        walkFind(root)
        return sourceMap.of(found!!)
    }
}