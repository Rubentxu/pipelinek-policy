package com.pipelinek.policy.decoders.json

import com.pipelinek.policy.decoder.DecodeRefusalCode
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §ADDED REQ 2 — JSON decoder contract.
 *
 * Scenarios:
 *   (a) round-trip `{"a":1}` -> `MappingValue({"a": Number(1)})`
 *   (b) duplicate-key `{"a":1,"a":3}` -> `Refused(DUPLICATE_KEY)` (mutation gate item 2)
 *   (c) integer `42` not coerced to `Decimal`
 *   (d) decimal `4.5` not coerced to `Integer`
 *   (e) source span `startLine=1,startColumn=1` on root map
 */
class JsonDecoderTest {

    private val decoder = JsonResourceDecoder()

    @Test
    fun `round-trip simple mapping preserves integer`() {
        val bytes = """{"a":1}""".toByteArray()
        val result = decoder.decode(bytes)
        val doc = (result as DecodeResult.Ok).documents.single()
        assertEquals(
            ValueNode.MappingValue(linkedMapOf("a" to ValueNode.NumberValue(1L))),
            doc.root,
        )
    }

    @Test
    fun `duplicate key fails closed with Refused DUPLICATE_KEY`() {
        val bytes = """{"a":1,"a":3}""".toByteArray()
        val result = decoder.decode(bytes)
        assertTrue(result is DecodeResult.Refused, "expected Refused, got $result")
        val refusal = (result as DecodeResult.Refused).refusal
        assertEquals(DecodeRefusalCode.DUPLICATE_KEY, refusal.code)
        assertTrue(refusal.anchor is SourceAnchor.TextSpan, "anchor must be a TextSpan")
    }

    @Test
    fun `integer literal is NOT coerced to decimal`() {
        val bytes = """{"n":42}""".toByteArray()
        val result = decoder.decode(bytes)
        val doc = (result as DecodeResult.Ok).documents.single()
        val number = (doc.root as ValueNode.MappingValue).entries.getValue("n") as ValueNode.NumberValue
        // Long → no fractional part; a coerced Double or BigDecimal would
        // be silently different.
        assertTrue(number.number is Long, "expected Long carrier, got ${number.number::class.simpleName}")
        assertEquals(42L, number.number.toLong())
    }

    @Test
    fun `decimal literal is NOT coerced to integer`() {
        val bytes = """{"n":4.5}""".toByteArray()
        val result = decoder.decode(bytes)
        val doc = (result as DecodeResult.Ok).documents.single()
        val number = (doc.root as ValueNode.MappingValue).entries.getValue("n") as ValueNode.NumberValue
        // Decimal → fractional part preserved; we use BigDecimal so a mutated
        // decoder that switched to `Long` would truncate 4.5 → 4. The
        // invariant we lock: BigDecimal value is exactly 4.5, not 4.
        val asBigDecimal = number.number as java.math.BigDecimal
        assertEquals(java.math.BigDecimal("4.5"), asBigDecimal)
        assertNotEquals(java.math.BigDecimal("4"), asBigDecimal)
    }

    @Test
    fun `top-level mapping carries a TextSpan anchored at line 1 column 1`() {
        val bytes = """{"a":1}""".toByteArray()
        val result = decoder.decode(bytes)
        val doc = (result as DecodeResult.Ok).documents.single()
        // Find the FIRST TextSpan in the source map (the root mapping).
        val rootAnchor = doc.sourceMap.entries
            .map { it.value }
            .filterIsInstance<SourceAnchor.TextSpan>()
            .first()
        assertEquals(1L, rootAnchor.startLine, "root span MUST start at line 1")
        assertEquals(1L, rootAnchor.startColumn, "root span MUST start at column 1")
    }

    @Test
    fun `top-level array yields a single SequenceValue document`() {
        val bytes = """[{"a":1},{"a":2}]""".toByteArray()
        val result = decoder.decode(bytes)
        val doc = (result as DecodeResult.Ok).documents.single()
        val seq = doc.root as ValueNode.SequenceValue
        assertEquals(2, seq.elements.size)
        assertEquals(ValueNode.MappingValue(linkedMapOf("a" to ValueNode.NumberValue(1L))), seq.elements[0])
        assertEquals(ValueNode.MappingValue(linkedMapOf("a" to ValueNode.NumberValue(2L))), seq.elements[1])
    }

    @Test
    fun `nested mapping preserves duplicates at any depth`() {
        // Duplicate key deep in the tree MUST still fail closed.
        val bytes = """{"outer":{"x":1,"x":2}}""".toByteArray()
        val result = decoder.decode(bytes)
        assertTrue(result is DecodeResult.Refused, "expected Refused for nested duplicate, got $result")
        assertEquals(DecodeRefusalCode.DUPLICATE_KEY, (result as DecodeResult.Refused).refusal.code)
    }

    @Test
    fun `boolean and null literals round-trip`() {
        val bytes = """{"flag":true,"void":null,"off":false}""".toByteArray()
        val result = decoder.decode(bytes)
        val doc = (result as DecodeResult.Ok).documents.single()
        val map = doc.root as ValueNode.MappingValue
        assertEquals(ValueNode.BooleanValue(true), map.entries.getValue("flag"))
        assertEquals(ValueNode.Null, map.entries.getValue("void"))
        assertEquals(ValueNode.BooleanValue(false), map.entries.getValue("off"))
    }
}