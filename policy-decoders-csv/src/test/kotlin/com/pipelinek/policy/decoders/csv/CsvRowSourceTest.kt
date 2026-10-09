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

    @Test
    fun `06b - flat memory over a large input (Runtime delta, no fork)`() {
        // ~150k rows, ~1.1 MiB — enough to catch a materializing regression
        // (full-materialize allocates ~1000x this delta in similar tests).
        val sb = StringBuilder("v\n")
        repeat(150_000) { sb.append(it).append('\n') }
        val bytes = sb.toString().toByteArray()

        System.gc()
        val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val source = CsvRowSource(bytes)
        var count = 0L
        var checksum = 0L
        while (true) {
            val row = source.next() ?: break
            checksum += (row.entries["v"] as ValueNode.TextValue).text.length
            count++
        }
        System.gc()
        val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        assertEquals(150_000L, count)
        // Streaming keeps retained memory ~O(row); allow generous headroom.
        val deltaBytes = after - before
        assertTrue(
            deltaBytes < 64L * 1024 * 1024,
            "streaming delta $deltaBytes bytes looks like full materialization",
        )
    }
}
