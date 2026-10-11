package com.pipelinek.policy.decoders.json

import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * H3.1 · The JSONL row cursor must read an InputStream, not a materialized ByteArray.
 *
 * `JsonlSource(bytes: ByteArray)` is lazy only in its PARSING. The document is
 * already fully in heap before the first row is yielded, so a 256 MiB JSONL
 * dataset costs 256 MiB regardless of how many rows are held. The roadmap
 * flagged this as PARCIAL for JSONL while CSV had the same pattern and was
 * closed in B6.6.
 *
 * The pre-existing `06b - flat memory over a large input (Runtime delta)` test
 * in `JsonlSourceTest` is the structurally-blind form of this defect: it
 * builds the `ByteArray` BEFORE taking its memory baseline, so the payload is
 * never inside the measured window. That test is deleted, not weakened, by
 * this change. The replacement measures pulls off the stream, which is
 * deterministic.
 *
 * Pattern mirrors `CsvRowStreamSourceTest`: a counting stream that refuses
 * bulk reads, plus the same five falsifying cases (window ceiling, all rows,
 * large line, multibyte split, parity with array path). The decoder is
 * narrow on purpose — one object per line, UTF-8, no `ObjectMapper` — and the
 * window contract is the same.
 */
class JsonlRowStreamSourceTest {

    /**
     * An InputStream that reports how much has been pulled and refuses the
     * bulk-oriented entry points. `readAllBytes` and `skip` are the shortcuts
     * a materializing reader reaches for; both throw so a cursor that tries
     * them fails loudly instead of quietly reading everything.
     */
    private class CountingStream(private val payload: ByteArray) : InputStream() {
        var pulled: Long = 0
            private set

        private var pos = 0

        override fun read(): Int {
            if (pos >= payload.size) return -1
            pulled++
            return payload[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= payload.size) return -1
            val n = minOf(len.toLong(), (payload.size - pos).toLong()).toInt()
            payload.copyInto(b, off, pos, pos + n)
            pos += n
            pulled += n
            return n
        }

        override fun readAllBytes(): ByteArray = error("materializing read: not permitted")

        override fun skip(n: Long): Long = error("skip: not permitted")
    }

    private fun jsonl(rows: Int): ByteArray {
        val sb = StringBuilder()
        repeat(rows) { sb.append("{\"n\":").append(it).append("}\n") }
        return sb.toString().toByteArray()
    }

    /**
     * The falsifying assertion.
     *
     * A materializing reader drains the whole payload before yielding the first
     * row, so `pulled` at that moment is the file size. A row cursor has pulled
     * only what it needed to parse the first JSON object.
     *
     * The bound is one window (8 KiB) plus a generous fudge: a JSONL line is
     * small, so the cursor only needs the first line's bytes plus the trailing
     * newline. Asserting against the file size instead would only pass for
     * payloads larger than the window, and would stop detecting the defect the
     * moment the file got small.
     */
    @Test
    fun `a row cursor does not drain the document before yielding the first row`() {
        val payload = jsonl(20_000)
        val stream = CountingStream(payload)

        val source = JsonlSource.open(stream)
        val firstRow = source.next()
        source.closeQuietly()

        assertNotNull(firstRow, "expected a first row")
        // The cursor reads ahead into a sliding window, so what is measured here
        // is one window, not the document. The property under test is that the
        // window is a FIXED bound that does not grow with the file: read 8x the
        // file and the same ceiling must still hold.
        val windowCeiling = 16L * 1024
        assertTrue(
            stream.pulled <= windowCeiling,
            "cursor pulled ${stream.pulled} bytes of ${payload.size} before the first " +
                "row, above the $windowCeiling window ceiling; a materializing " +
                "reader drains the document up front",
        )
        assertEquals(0L, (firstRow.entries["n"] as ValueNode.NumberValue).number.toLong())
    }

    /**
     * The positive control that the old 06b lacked.
     *
     * Every row must still be produced, in order, and the stream must end up
     * fully consumed. Without this, a cursor that silently read only the first
     * row would satisfy the bound above, and a refusal to keep reading would
     * go unnoticed. With 100k rows the cursor must walk past the window
     * boundary many times; the test passes only if `slide` actually slides.
     */
    @Test
    fun `a streamed cursor still yields every row in order and consumes the stream`() {
        val payload = jsonl(100_000)
        val stream = CountingStream(payload)
        val source = JsonlSource.open(stream)

        val seen = mutableListOf<Long>()
        while (true) {
            val row = source.next() ?: break
            seen += (row.entries["n"] as ValueNode.NumberValue).number.toLong()
        }
        source.closeQuietly()

        assertEquals(100_000, seen.size)
        assertEquals(0L, seen.first())
        assertEquals(99_999L, seen.last())
        assertEquals(
            payload.size.toLong(),
            stream.pulled,
            "the cursor must consume exactly the payload",
        )
    }

    /**
     * A JSONL line longer than the whole window.
     *
     * The sliding window is 8 KiB and the line reader accumulates while
     * walking, so a line larger than the window must still decode. This is
     * the case that would break first if the window were treated as a hard
     * line limit, and no small fixture reaches it.
     */
    @Test
    fun `a line larger than the read window still decodes`() {
        val big = "x".repeat(40_000)
        val payload = "{\"v\":\"$big\"}\n{\"v\":\"second\"}\n".toByteArray()
        val stream = CountingStream(payload)
        val source = JsonlSource.open(stream)

        val values = mutableListOf<String>()
        while (true) {
            val row = source.next() ?: break
            values += (row.entries["v"] as ValueNode.TextValue).text
        }
        source.closeQuietly()

        assertEquals(listOf(big, "second"), values)
        assertEquals(payload.size.toLong(), stream.pulled)
    }

    /**
     * Multibyte UTF-8 across chunk boundaries.
     *
     * A buffer-refilling cursor can easily split a multibyte UTF-8 sequence
     * across two reads. That failure is invisible with small fixtures, so
     * this drives the cursor with a tiny buffer to force the split on every
     * byte of every key. Same shape as the CSV equivalent.
     */
    @Test
    fun `a streamed cursor reads multibyte text across chunk boundaries`() {
        val payload = "{\"v\":\"Málaga\"}\n{\"v\":\"café\"}\n{\"v\":\"😀\"}\n".toByteArray()
        val dribble = object : InputStream() {
            private val inner = CountingStream(payload)

            override fun read(): Int = inner.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                inner.read(b, off, minOf(len, 2))
        }
        val source = JsonlSource.open(dribble)

        val values = mutableListOf<String>()
        while (true) {
            val row = source.next() ?: break
            values += (row.entries["v"] as ValueNode.TextValue).text
        }
        source.closeQuietly()

        assertEquals(listOf("Málaga", "café", "😀"), values)
    }

    /**
     * Byte-for-byte agreement with the materializing cursor.
     *
     * A new read path is only safe if it agrees with the certified one.
     * The two `JsonlSource` constructors (one over `ByteArray`, one over
     * `InputStream`) must yield the same rows for the same input.
     */
    @Test
    fun `streamed and materialized cursors agree row for row`() {
        val tricky = """
            {"a":1,"b":"plain"}
            {"a":2,"b":"Málaga"}
            {"a":3,"b":"café"}
            {"a":4,"b":"a \"quote\" inside"}
        """.trimIndent().toByteArray()

        fun rowsVia(open: (ByteArray) -> JsonlSource): List<ValueNode.MappingValue> {
            val source = open(tricky)
            val out = mutableListOf<ValueNode.MappingValue>()
            while (true) {
                out += source.next() ?: break
            }
            return out
        }

        val fromBytes = rowsVia { JsonlSource(it) }
        val fromStream = rowsVia { JsonlSource.open(ByteArrayInputStream(it)) }

        assertEquals(fromBytes, fromStream)
        assertTrue(fromBytes.isNotEmpty(), "the fixture must produce rows")
    }
}
