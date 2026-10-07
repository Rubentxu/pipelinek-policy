package com.pipelinek.policy.decoders.map

import com.pipelinek.policy.decoder.DecodeRefusalCode
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §"Map adapter" — recursive descent over Kotlin in-memory data.
 *
 * Scenarios:
 *   (a) `mapOf("a" to 1, "b" to listOf("x", "y"))` →
 *       `MappingValue({"a": Number(1L), "b": Sequence([Text("x"), Text("y")])})`
 *   (b) custom bean → `Refused(UNSUPPORTED_HOST_VALUE)` (mutation gate
 *       item: dropping the refusal path would have made the test PASS
 *       with a silent `toString()` coercion; this test fails it)
 *   (c) nested map keys; the inner map becomes a nested `MappingValue`
 *   (d) non-string keys → `Refused(UNSUPPORTED_HOST_VALUE)` — silent
 *       `toString()` coercion would corrupt numeric keys.
 */
class MapAdapterDecoderTest {

    private data class CustomBean(val x: Int)

    private val decoder = MapAdapterDecoder()

    @Test
    fun `basic map of primitives and list converts to MappingValue`() {
        val result = decoder.decodeMap(
            mapOf(
                "a" to 1,
                "b" to listOf("x", "y"),
            ),
        )
        val doc = (result as DecodeResult.Ok).documents.single()
        val root = doc.root as ValueNode.MappingValue
        assertEquals(ValueNode.NumberValue(1L), root.entries.getValue("a"))
        val seq = root.entries.getValue("b") as ValueNode.SequenceValue
        assertEquals(2, seq.elements.size)
        assertEquals(ValueNode.TextValue("x"), seq.elements[0])
        assertEquals(ValueNode.TextValue("y"), seq.elements[1])
    }

    @Test
    fun `custom bean is refused with UNSUPPORTED_HOST_VALUE`() {
        val result = decoder.decodeMap(mapOf("k" to CustomBean(x = 42)))
        assertTrue(result is DecodeResult.Refused, "expected Refused, got $result")
        assertEquals(
            DecodeRefusalCode.UNSUPPORTED_HOST_VALUE,
            (result as DecodeResult.Refused).refusal.code,
        )
    }

    @Test
    fun `nested map keys become nested MappingValue`() {
        val result = decoder.decodeMap(
            mapOf(
                "outer" to mapOf(
                    "inner1" to "v1",
                    "inner2" to 2L,
                ),
            ),
        )
        val doc = (result as DecodeResult.Ok).documents.single()
        val outer = (doc.root as ValueNode.MappingValue).entries.getValue("outer") as ValueNode.MappingValue
        assertEquals(ValueNode.TextValue("v1"), outer.entries.getValue("inner1"))
        assertEquals(ValueNode.NumberValue(2L), outer.entries.getValue("inner2"))
    }

    @Test
    fun `non-string keys are refused rather than silently coerced`() {
        // LinkedHashMap keyed by Int; passing toString() would yield "1"
        // for key=1, silently losing the integer key. We refuse instead.
        val source: Map<Any?, Any?> = linkedMapOf<Any?, Any?>(1 to "v")
        val result = decoder.decodeMap(source as Map<String, Any?>)
        assertTrue(result is DecodeResult.Refused, "expected Refused, got $result")
        assertEquals(
            DecodeRefusalCode.UNSUPPORTED_HOST_VALUE,
            (result as DecodeResult.Refused).refusal.code,
        )
    }

    @Test
    fun `decode bytes always refuses — map adapter is host-only`() {
        val result = decoder.decode(ByteArray(0))
        assertTrue(result is DecodeResult.Refused)
        assertEquals(
            DecodeRefusalCode.UNSUPPORTED_HOST_VALUE,
            (result as DecodeResult.Refused).refusal.code,
        )
    }
}