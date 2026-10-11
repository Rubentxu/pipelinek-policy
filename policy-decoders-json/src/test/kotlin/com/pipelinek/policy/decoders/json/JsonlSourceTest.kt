package com.pipelinek.policy.decoders.json

import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/** M8 REQ 04 · JsonlSource streaming (04a/04b/04c). */
class JsonlSourceTest {

    @Test
    fun `04a - objects stream row by row with typed values`() {
        val jsonl = """
            {"temp": 21, "unit": "c"}
            {"temp": 22.5, "ok": true}
            {"temp": null}
        """.trimIndent() + "\n"
        val source = JsonlSource(jsonl.toByteArray())
        val r1 = source.next()!!
        val r2 = source.next()!!
        val r3 = source.next()!!

        assertEquals(ValueNode.NumberValue(21L), r1.entries["temp"])
        assertEquals(ValueNode.TextValue("c"), r1.entries["unit"])
        assertEquals(ValueNode.NumberValue(java.math.BigDecimal("22.5")), r2.entries["temp"])
        assertEquals(ValueNode.BooleanValue(true), r2.entries["ok"])
        assertEquals(ValueNode.Null, r3.entries["temp"])
        assertEquals(null, source.next())
    }

    @Test
    fun `04a - parity with whole-document JSON decoder per row`() {
        val jsonl = "{\"a\":1}\n{\"a\":2}\n{\"a\":3}\n"
        val whole = (JsonResourceDecoder().decode(
            "[$jsonl".replace("[", "[") // build a JSON array of the same rows
                .trimEnd('\n')
                .let { it + "]" }
                .replace("}\n{", "},{")
                .toByteArray(),
        ) as com.pipelinek.policy.decoder.DecodeResult.Ok)
        val wholeRows = (whole.documents.single().root as ValueNode.SequenceValue).elements

        val source = JsonlSource(jsonl.toByteArray())
        val streamed = generateSequence { source.next() }.toList()
        assertEquals(wholeRows.size, streamed.size)
        wholeRows.zip(streamed).forEach { (w, s) -> assertEquals(w, s) }
    }

    @Test
    fun `04b - falsification - duplicate key refuses`() {
        val bad = "{\"a\":1,\"a\":2}\n"
        val source = JsonlSource(bad.toByteArray())
        val ex = assertThrows<JsonlRefusal> { source.next() }
        assertEquals(com.pipelinek.policy.decoder.DecodeRefusalCode.DUPLICATE_KEY, ex.code)
    }

    @Test
    fun `04c - falsification - non-object row refuses`() {
        val bad = "[1,2,3]\n"
        val source = JsonlSource(bad.toByteArray())
        val ex = assertThrows<JsonlRefusal> { source.next() }
        assertEquals(com.pipelinek.policy.decoder.DecodeRefusalCode.MALFORMED, ex.code)
    }

    @Test
    fun `04c - falsification - malformed line refuses`() {
        val bad = "{\"a\": }\n"
        val source = JsonlSource(bad.toByteArray())
        assertThrows<JsonlRefusal> { source.next() }
    }

    // H3.1 · the pre-existing `06b - flat memory over a large input` test was
    // structurally blind: it built the ByteArray BEFORE taking the memory
    // baseline, so the payload was never inside the measured window. The same
    // defect that closed for CSV in B6.6. Replaced by JsonlRowStreamSourceTest,
    // which counts bytes pulled off the stream (deterministic) rather than
    // measuring Runtime deltas over short windows (noisy and easy to game).
}
