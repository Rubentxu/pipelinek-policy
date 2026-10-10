package com.pipelinek.policy.decoders.csv

import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * B5.3 / ROADMAP P1-08 · multibyte UTF-8 must survive decoding.
 *
 * The defect this pins is SILENT corruption, not a crash. `c.toInt().toChar()`
 * maps one byte to one char, so the 5 UTF-8 bytes of `café` became 5 characters
 * reading `café` as mojibake, and the policy then evaluated a value its author
 * never wrote. No exception, no warning: a wrong verdict.
 *
 * ## Both CSV routes are covered on purpose
 *
 * `CsvResourceDecoder` (materialised) and `CsvRowSource` (streaming) each had
 * their own copy of the byte-to-char loop. Fixing only one would leave the
 * corruption live on the other, which is precisely the parity gap B6.3 exists to
 * catch. A test covering only the materialised path would pass while streaming
 * stayed broken.
 *
 * `policy-decoders-yaml` is deliberately absent: `YamlResourceDecoder` already
 * uses `String(bytes, Charsets.UTF_8)`. It is the reference implementation, not
 * an exclusion to justify.
 */
class CsvUtf8DecodingTest {

    // Non-ASCII covering all three UTF-8 sequence lengths:
    // 2-byte (é), 3-byte (€) and 4-byte (😀) code points.
    private val twoByte = "café"
    private val threeByte = "precio €"
    private val fourByte = "estado 😀"

    /** Header row plus one DATA row; the header is consumed as column names. */
    private fun csv(value: String): ByteArray =
        "k,v\n1,$value\n".toByteArray(Charsets.UTF_8)

    /** Same payload with the value quoted, exercising the quoted-cell loop. */
    private fun quotedCsv(value: String): ByteArray =
        "k,v\n1,\"$value\"\n".toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): ValueNode.MappingValue {
        val result = CsvResourceDecoder().decode(bytes, DecodeOptions())
        val ok = assertIs<DecodeResult.Ok>(result)
        val sequence = assertIs<ValueNode.SequenceValue>(ok.documents.single().root)
        val row = assertIs<ValueNode.MappingValue>(sequence.elements.first())
        return row
    }

    private fun stream(bytes: ByteArray): ValueNode.MappingValue =
        assertIs(CsvRowSource(bytes).next())

    private fun cell(row: ValueNode.MappingValue, key: String): String {
        val text = assertIs<ValueNode.TextValue>(row.entries.getValue(key), "column '$key'")
        return text.text
    }

    // --- materialised route ---

    @Test
    fun `01a the materialised decoder preserves a 2-byte code point`() {
        assertEquals(twoByte, cell(decode(csv(twoByte)), "v"))
    }

    @Test
    fun `01b the materialised decoder preserves a 3-byte code point`() {
        assertEquals(threeByte, cell(decode(csv(threeByte)), "v"))
    }

    @Test
    fun `01c the materialised decoder preserves a 4-byte code point`() {
        assertEquals(fourByte, cell(decode(csv(fourByte)), "v"))
    }

    // --- streaming route ---

    @Test
    fun `02a the streaming source preserves a 2-byte code point`() {
        assertEquals(twoByte, cell(stream(csv(twoByte)), "v"))
    }

    @Test
    fun `02b the streaming source preserves a 3-byte code point`() {
        assertEquals(threeByte, cell(stream(csv(threeByte)), "v"))
    }

    @Test
    fun `02c the streaming source preserves a 4-byte code point`() {
        assertEquals(fourByte, cell(stream(csv(fourByte)), "v"))
    }

    // --- quoted cells run a separate loop and must decode too ---

    @Test
    fun `03a a quoted multibyte cell survives the materialised route`() {
        assertEquals(threeByte, cell(decode(quotedCsv(threeByte)), "v"))
    }

    @Test
    fun `03b a quoted multibyte cell survives the streaming route`() {
        assertEquals(threeByte, cell(stream(quotedCsv(threeByte)), "v"))
    }

    // --- parity: the two routes must agree on multibyte content ---

    @Test
    fun `04a both CSV routes agree on multibyte content`() {
        // Three columns, and a matching three-column header. The earlier
        // fixture emitted "k,v\n1,café,😀\n" — three data cells against a
        // two-column header, which is malformed CSV, not a decoding defect.
        val bytes = "k,v,n\n1,$twoByte,$fourByte\n".toByteArray(Charsets.UTF_8)
        val whole = decode(bytes)
        val streamed = stream(bytes)
        // Same input, same answer, both routes. If the two loops ever drift
        // again this is the assertion that catches it.
        assertEquals(cell(whole, "k"), cell(streamed, "k"))
        assertEquals(cell(whole, "v"), cell(streamed, "v"))
        // And the parity is on the decoded VALUES, not merely "both threw the
        // same way" — an equality of two equally-corrupted strings would pass.
        assertEquals("1", cell(whole, "k"))
        assertEquals(twoByte, cell(whole, "v"))
        assertEquals(fourByte, cell(whole, "n"))
    }

    // --- ASCII must not regress ---

    @Test
    fun `05a ascii decoding is unchanged`() {
        assertEquals("plain-ascii", cell(decode(csv("plain-ascii")), "v"))
    }
}
