package com.pipelinek.policy.decoders.json

import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * B5.3 / P1-08 · JSONL rows must decode as UTF-8.
 *
 * `JsonlSource` used to build each line with `bytes[pos++].toInt().toChar()`,
 * mapping one byte to one char. Every multibyte character was therefore split
 * into its individual bytes and each byte became a Latin-1-ish char, so the
 * JSON parser then evaluated a string the author never wrote.
 *
 * The corruption is silent: nothing refuses, no exception, the row is just
 * wrong. These tests pin the decoded VALUES, not merely "it parsed".
 */
class JsonlUtf8DecodingTest {

    private fun rows(jsonl: String): List<ValueNode.MappingValue> {
        // Encode explicitly as UTF-8. Relying on the platform default charset
        // would make the test pass or fail depending on the machine's locale,
        // and could even "fix" the corruption under test.
        val source = JsonlSource((jsonl.trimIndent() + "\n").toByteArray(Charsets.UTF_8))
        val out = mutableListOf<ValueNode.MappingValue>()
        while (true) {
            out += source.next() ?: break
        }
        return out
    }

    private fun text(row: ValueNode.MappingValue, key: String): String =
        assertIs<ValueNode.TextValue>(row.entries.getValue(key), "field '$key'").text

    @Test
    fun `01a jsonl preserves a 2-byte code point`() {
        val r = rows("""{"ciudad": "Málaga"}""").single()
        assertEquals("Málaga", text(r, "ciudad"))
    }

    @Test
    fun `01b jsonl preserves a 3-byte code point`() {
        val r = rows("""{"precio": "10 €"}""").single()
        assertEquals("10 €", text(r, "precio"))
    }

    @Test
    fun `01c jsonl preserves a 4-byte code point`() {
        val r = rows("""{"estado": "😀"}""").single()
        assertEquals("😀", text(r, "estado"))
    }

    @Test
    fun `02a multibyte survives as a non-value KEY too`() {
        // Keys are decoded by the same line reader, so a corrupted key would
        // silently make the row unfindable rather than fail loudly.
        val r = rows("""{"país": "ES"}""").single()
        assertEquals("ES", text(r, "país"))
    }

    @Test
    fun `03a jsonl matches the whole-document JSON decoder on multibyte`() {
        // Parity across both JSON routes. Two equally-corrupted values would
        // pass a naive equals, so the expected literal is asserted directly.
        val row = """{"ciudad": "Málaga", "nota": "€"}"""
        val streamed = rows(row).single()
        val whole = JsonResourceDecoder().decode("[$row]".toByteArray(Charsets.UTF_8))
        val ok = assertIs<com.pipelinek.policy.decoder.DecodeResult.Ok>(whole)
        val seq = assertIs<ValueNode.SequenceValue>(ok.documents.single().root)
        val wholeRow = assertIs<ValueNode.MappingValue>(seq.elements.single())
        assertEquals(text(streamed, "ciudad"), text(wholeRow, "ciudad"))
        assertEquals(text(streamed, "nota"), text(wholeRow, "nota"))
        assertEquals("Málaga", text(wholeRow, "ciudad"))
        assertEquals("€", text(wholeRow, "nota"))
    }

    @Test
    fun `04a multibyte does not corrupt the row boundary`() {
        // A corruption of the line scanner would risk bleeding bytes into the
        // NEXT row. Two rows, multibyte in the first only.
        val rs = rows(
            """
            {"a": "€", "b": 1}
            {"a": "plain", "b": 2}
            """,
        )
        assertEquals(2, rs.size)
        assertEquals("€", text(rs[0], "a"))
        assertEquals("plain", text(rs[1], "a"))
        assertEquals(ValueNode.NumberValue(2L), rs[1].entries.getValue("b"))
    }

    @Test
    fun `05a ascii jsonl decoding is unchanged`() {
        val r = rows("""{"a": "plain-ascii", "n": 7}""").single()
        assertEquals("plain-ascii", text(r, "a"))
        assertEquals(ValueNode.NumberValue(7L), r.entries.getValue("n"))
    }
}