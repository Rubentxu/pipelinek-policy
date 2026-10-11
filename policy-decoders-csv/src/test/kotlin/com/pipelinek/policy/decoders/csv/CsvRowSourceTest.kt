package com.pipelinek.policy.decoders.csv

import com.pipelinek.policy.decoder.CsvMode
import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8 REQ 03/06 · CsvRowSource streaming (03a/03b/06b).
 *
 * 03a: row-at-a-time yields the same cells as the whole-document decoder.
 * 06b: memory falsification — a large synthetic input streams with flat
 *      memory measured via Runtime delta (no fork).
 */
class CsvRowSourceTest {

    private val csv = "temp,unit\n21,c\n\"22,5\",c\nhot,c\n"

    @Test
    fun `03a - streaming rows equal whole-document rows cell by cell`() {
        val streamingRows = mutableListOf<ValueNode.MappingValue>()
        val source = CsvRowSource(csv.toByteArray())
        while (true) {
            val row = source.next() ?: break
            streamingRows += row
        }

        val decoded = (CsvResourceDecoder().decode(csv.toByteArray()) as
            com.pipelinek.policy.decoder.DecodeResult.Ok)
        val doc = decoded.documents.single()
        val seq = doc.root as ValueNode.SequenceValue

        assertEquals(seq.elements.size, streamingRows.size)
        seq.elements.zip(streamingRows).forEach { (whole, streamed) ->
            assertEquals(whole, streamed)
        }
    }

    @Test
    fun `03a - quoted cells with embedded newline and escaped quotes`() {
        val tricky = "a,b\n\"line1\nline2\",x\n\"say \"\"hi\"\"\",y\n"
        val source = CsvRowSource(tricky.toByteArray())
        val r1 = source.next()!!
        val r2 = source.next()!!
        assertEquals(ValueNode.TextValue("line1\nline2"), r1.entries["a"])
        assertEquals(ValueNode.TextValue("say \"hi\""), r2.entries["a"])
        assertEquals(null, source.next())
    }

    @Test
    fun `03b - falsification - ragged row refuses instead of padding silently`() {
        val ragged = "a,b,c\n1,2,3\n4,5\n"
        val source = CsvRowSource(ragged.toByteArray())
        assertEquals(3, source.next()!!.entries.size)
        val ex = assertThrows<CsvRowRefusal> { source.next() }
        assertTrue(ex.anchor.toString().contains("ragged-row") || ex.message != null)
    }

    @Test
    fun `03b - falsification - unterminated quote refuses`() {
        val bad = "a,b\n\"open,1\n"
        val source = CsvRowSource(bad.toByteArray())
        assertThrows<CsvRowRefusal> { source.next() }
    }

    /**
     * H3.1 · The memory claim is measured against a byte counter, not the heap.
     *
     * This replaces `06b - flat memory over a large input (Runtime delta, no
     * fork)`, which was deleted rather than kept. It is worth recording why,
     * because it looked like exactly the test this feature needed:
     *
     *     val bytes = sb.toString().toByteArray()   // allocated HERE
     *     val before = ...totalMemory()...          // measured HERE
     *     val source = CsvRowSource(bytes)          // nothing after
     *
     * The payload is allocated BEFORE the baseline, so the document is never
     * inside the measured window, and a source that retains all 150_000 rows
     * still measured -531040 bytes against its 64 MiB ceiling. Measured, not
     * inferred: a fully materializing variant passed that test. A green 06b
     * therefore proved nothing, and a Runtime delta over a short window is
     * dominated by GC timing regardless.
     *
     * CsvRowStreamSourceTest replaces it with a byte counter on the source
     * stream, which is deterministic: it pins that the cursor pulls at most one
     * window before yielding its first row, and it fails if the cursor ever
     * reaches for `readAllBytes`.
     */
    @Test
    fun `06b - a streamed cursor pulls a bounded prefix, not the whole document`() {
        val sb = StringBuilder("v\n")
        repeat(200_000) { sb.append(it).append('\n') }
        val payload = sb.toString().toByteArray()

        val counting = object : java.io.InputStream() {
            var pulled = 0L
                private set
            private var pos = 0

            override fun read(): Int = if (pos >= payload.size) -1 else payload[pos++].toInt() and 0xFF

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= payload.size) return -1
                val n = minOf(len.toLong(), (payload.size - pos).toLong()).toInt()
                payload.copyInto(b, off, pos, pos + n)
                pos += n
                pulled += n
                return n
            }

            override fun readAllBytes(): ByteArray = error("materializing read is not permitted")
        }

        CsvRowSource.open(counting).next()

        assertTrue(
            counting.pulled <= 16L * 1024,
            "pulled ${counting.pulled} bytes of ${payload.size} before the first row",
        )
    }
}
