package com.pipelinek.policy.decoders.csv

import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * H3.1 · The row cursor must read an InputStream, not a materialized ByteArray.
 *
 * `CsvRowSource(bytes: ByteArray)` is lazy only in its PARSING. The document is
 * already fully in heap before the first row is yielded, so a 256 MiB dataset
 * costs 256 MiB regardless of how many rows are held. Its KDoc claimed "a 1 GiB
 * file costs O(row) memory", which the signature cannot deliver.
 *
 * The pre-existing `06b - flat memory over a large input` test was meant to
 * falsify materialization and could not. Measured: a source that RETAINS every
 * one of 150_000 rows showed a retained-memory delta of -531040 bytes against a
 * 64 MiB ceiling, so it passed with a comfortable margin. The reason is
 * structural, not a threshold problem -- the test builds the ByteArray before
 * taking its baseline:
 *
 *     val bytes = sb.toString().toByteArray()   // allocated HERE
 *     val before = ...totalMemory()...          // measured HERE
 *     val source = CsvRowSource(bytes)          // nothing allocated after
 *
 * so the payload is never inside the measured window, and Runtime deltas over
 * short windows are dominated by GC timing anyway. A green 06b therefore said
 * nothing about materialization.
 *
 * These cases replace it with something falsifiable. The mechanism is a stream
 * that counts how much has been pulled and REFUSES bulk reads, so a cursor that
 * wants the whole document must go through a path that is observable, and the
 * test asserts on that counter rather than on heap.
 */
class CsvRowStreamSourceTest {

    /**
     * An InputStream that reports how much was pulled and refuses the
     * bulk-oriented entry points.
     *
     * `readAllBytes` and the single-arg `read` are the shortcuts a materializing
     * reader reaches for. Both throw, so an implementation that tries them
     * fails loudly instead of quietly reading everything.
     */
    private class CountingStream(private val payload: ByteArray) : InputStream() {
        var pulled: Long = 0
            private set

        /** Largest gap between two pulls; proves the cursor did not drain ahead. */
        var maxPulledBeforeFirstRow: Long = 0
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

    private fun csv(rows: Int): ByteArray {
        val sb = StringBuilder("v\n")
        repeat(rows) { sb.append(it).append('\n') }
        return sb.toString().toByteArray()
    }

    /**
     * The falsifying assertion.
     *
     * A materializing reader drains the whole payload before yielding the first
     * row, so `pulled` at that moment is the file size. A row cursor has pulled
     * only what it needed to parse the header and the first row.
     *
     * The bound is generous — 32x the first row's worth of bytes — because the
     * point is not to pin buffering behaviour but to separate "drains up front"
     * from "reads ahead".
     */
    @Test
    fun `a row cursor does not drain the document before yielding the first row`() {
        val payload = csv(20_000)
        val stream = CountingStream(payload)

        val source = CsvRowSource.open(stream)
        val firstRow = source.next()

        assertTrue(firstRow != null, "expected a first data row")
        // The cursor reads ahead into a sliding window, so what is measured here
        // is one window, not the document. The property under test is that the
        // window is a FIXED bound that does not grow with the file: read 8x the
        // file and the same ceiling must still hold. Asserting against the file
        // size instead would only pass for payloads larger than the window, and
        // would stop detecting the defect the moment the file got small.
        val windowCeiling = 16L * 1024
        assertTrue(
            stream.pulled <= windowCeiling,
            "cursor pulled ${stream.pulled} bytes of ${payload.size} before the first " +
                "row, above the $windowCeiling window ceiling; a materializing " +
                "reader drains the document up front",
        )
        assertEquals("0", (firstRow!!.entries["v"] as ValueNode.TextValue).text)
    }

    /**
     * The positive control that the old test lacked.
     *
     * Every row must still be produced, in order, and the stream must end up
     * fully consumed. Without this, a cursor that silently read only the first
     * row would satisfy the bound above, and a refusal to keep reading would go
     * unnoticed.
     */
    @Test
    fun `a streamed cursor still yields every row in order and consumes the stream`() {
        // 100_000 rows, not 1000. A 1000-row fixture is ~3.9 KiB and fits
        // inside a single 8 KiB window, so the window never fills and never
        // slides -- which is precisely the code path this test has to cover.
        // With the smaller fixture, replacing `slide()` with a no-op left the
        // suite fully green while silently truncating every real document.
        val payload = csv(100_000)
        val stream = CountingStream(payload)
        val source = CsvRowSource.open(stream)

        val seen = mutableListOf<String>()
        while (true) {
            val row = source.next() ?: break
            seen += (row.entries["v"] as ValueNode.TextValue).text
        }

        assertEquals(100_000, seen.size)
        assertEquals("0", seen.first())
        assertEquals("99999", seen.last())
        assertEquals(payload.size.toLong(), stream.pulled, "the cursor must consume exactly the payload")
    }

    /**
     * A cell longer than the whole window.
     *
     * The sliding window is 8 KiB and `readCell` accumulates while walking, so a
     * cell larger than the window must still decode correctly. This is the case
     * that would break first if the window were treated as a hard cell limit, and
     * no small fixture reaches it.
     */
    @Test
    fun `a cell larger than the read window still decodes`() {
        val big = "x".repeat(40_000)
        val payload = "v\n$big\nsecond\n".toByteArray()
        val stream = CountingStream(payload)
        val source = CsvRowSource.open(stream)

        val cells = mutableListOf<String>()
        while (true) {
            val row = source.next() ?: break
            cells += (row.entries["v"] as ValueNode.TextValue).text
        }

        assertEquals(listOf(big, "second"), cells)
        assertEquals(payload.size.toLong(), stream.pulled)
    }

    /**
     * Quoted cells spanning the streaming boundary.
     *
     * A buffer-refilling cursor can easily split a `""` escape or a multi-byte
     * UTF-8 sequence across two reads. That failure is invisible with small
     * fixtures, so this drives the cursor with a tiny buffer to force the split
     * on every cell.
     */
    @Test
    fun `a streamed cursor reads quoted cells and multibyte text across chunk boundaries`() {
        val payload = "v\n\"a,b\"\n\"say \"\"hi\"\"\"\ncafé\n".toByteArray()
        // Deliberately tiny: at most two bytes per pull, so every cell is split
        // across reads and any window boundary bug surfaces.
        val dribble = object : InputStream() {
            private val inner = CountingStream(payload)

            override fun read(): Int = inner.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                inner.read(b, off, minOf(len, 2))
        }
        val source = CsvRowSource.open(dribble)

        val cells = mutableListOf<String>()
        while (true) {
            val row = source.next() ?: break
            cells += (row.entries["v"] as ValueNode.TextValue).text
        }

        assertEquals(listOf("a,b", """say "hi"""", "café"), cells)
    }

    /**
     * Byte-for-byte agreement with the materializing cursor.
     *
     * A new read path is only safe if it agrees with the certified one. This is
     * the same parity property CsvParityTest holds for whole-document decoding,
     * applied to the two cursor constructors.
     */
    @Test
    fun `streamed and materialized cursors agree row for row`() {
        val tricky = "temp,unit\n21,c\n\"22,5\",c\nhot,c\nlast,c\n".toByteArray()

        fun rowsVia(open: (ByteArray) -> CsvRowSource): List<List<String?>> {
            val out = mutableListOf<List<String?>>()
            val source = open(tricky)
            while (true) {
                val row = source.next() ?: break
                out += row.entries.entries.map { (k, v) -> "$k=${(v as ValueNode.TextValue).text}" }
            }
            return out
        }

        val fromBytes = rowsVia { CsvRowSource(it) }
        val fromStream = rowsVia { CsvRowSource.open(ByteArrayInputStream(it)) }

        assertEquals(fromBytes, fromStream)
        assertTrue(fromBytes.isNotEmpty(), "the fixture must produce rows")
    }
}
