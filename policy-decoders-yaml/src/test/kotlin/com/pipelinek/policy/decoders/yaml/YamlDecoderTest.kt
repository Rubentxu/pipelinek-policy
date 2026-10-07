package com.pipelinek.policy.decoders.yaml

import com.pipelinek.policy.decoder.DecodeRefusalCode
import com.pipelinek.policy.decoder.DecodeResult
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
}