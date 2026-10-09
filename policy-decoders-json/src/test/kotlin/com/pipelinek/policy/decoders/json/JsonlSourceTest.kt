package com.pipelinek.policy.decoders.json

import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `06b - flat memory over many rows (Runtime delta, no fork)`() {
        val sb = StringBuilder()
        repeat(100_000) { sb.append("{\"n\":").append(it).append("}\n") }
        val bytes = sb.toString().toByteArray()

        System.gc()
        val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val source = JsonlSource(bytes)
        var count = 0L
        while (source.next() != null) count++
        System.gc()
        val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        assertEquals(100_000L, count)
        assertTrue(
            after - before < 64L * 1024 * 1024,
            "streaming delta ${after - before} bytes looks like full materialization",
        )
    }
}
