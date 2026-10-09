package com.pipelinek.policy.decoders.csv

import com.pipelinek.policy.decoder.CsvMode
import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeRefusalCode
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.NodeId
import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §"CSV modes" + ADR-0011 D-05.
 *
 * Scenarios:
 *   (a) default TEXT_ONLY — every cell is `TextValue` even when numeric-looking
 *   (b) EACH_ROW with `rows[1].b` resolves to `Present(Number(2))` and carries
 *       `SourceAnchor.Cell(row=2, column=2)`
 *   (c) WHOLE_DOCUMENT — root is a `SequenceValue` of `MappingValue`s
 *   (d) INFER_WITH_SAMPLE(3) with row 4 of a different type → `SCHEMA_FROZEN`
 *   (e) RFC 4180 quoted multi-line cell
 */
class CsvDecoderTest {

    private val decoder = CsvResourceDecoder()

    @Test
    fun `default TEXT_ONLY yields TextValue for numeric-looking cells`() {
        val bytes = "name,a,b\ns1,1,2\ns2,3,4\n".toByteArray()
        val result = decoder.decode(bytes) // default options: TEXT_ONLY, WHOLE_DOCUMENT
        val doc = (result as DecodeResult.Ok).documents.single()
        val row = (doc.root as ValueNode.SequenceValue).elements[0] as ValueNode.MappingValue
        // Mutation gate item 1: a mutated decoder that defaults to INFER
        // would coerce "1" → Number(1L); we lock the opposite.
        assertEquals(ValueNode.TextValue("1"), row.entries.getValue("a"))
        assertEquals(ValueNode.TextValue("2"), row.entries.getValue("b"))
    }

    @Test
    fun `EACH_ROW emits one document per data row with Cell anchors`() {
        val bytes = "name,a,b\ns1,1,2\ns2,3,4\n".toByteArray()
        val options = DecodeOptions(csvMode = CsvMode.EACH_ROW)
        val result = decoder.decode(bytes, options)
        val docs = (result as DecodeResult.Ok).documents
        assertEquals(2, docs.size)
        // Selector over the SECOND data row's `b` cell: row index 1 (0-based
        // across documents), `b` column.
        val second = docs[1].root as ValueNode.MappingValue
        val bResolve = Selector.of(DocumentPath.ROOT.child("b")).resolve(second)
        assertTrue(bResolve is Selector.Result.Present, "expected Present, got $bResolve")
        assertEquals(ValueNode.TextValue("4"), (bResolve as Selector.Result.Present).node)
        // Cell anchor for the `b` cell of row 3 (the SECOND data row): row 3
// (header is row 1), column 2 (1-based). docs[1] maps to data row index
// 1 → row number 3.
        val anchorForB = docs[1].sourceMap.entries
            .map { it.value }
            .filterIsInstance<SourceAnchor.Cell>()
            .first { it.row == 3L && it.column == 2L }
        assertEquals(SourceAnchor.Cell(row = 3L, column = 2L), anchorForB)
    }

    @Test
    fun `WHOLE_DOCUMENT root is Sequence of Mapping rows`() {
        val bytes = "name,a,b\ns1,1,2\ns2,3,4\n".toByteArray()
        val result = decoder.decode(bytes) // default WHOLE_DOCUMENT
        val doc = (result as DecodeResult.Ok).documents.single()
        assertTrue(doc.root is ValueNode.SequenceValue, "expected Sequence, got ${doc.root::class.simpleName}")
        val seq = doc.root as ValueNode.SequenceValue
        assertEquals(2, seq.elements.size)
        assertTrue(seq.elements[0] is ValueNode.MappingValue)
        assertEquals("s1", (seq.elements[0] as ValueNode.MappingValue).entries.getValue("name").let {
            (it as ValueNode.TextValue).text
        })
    }

    @Test
    fun `INFER_WITH_SAMPLE 3 refuses heterogeneous row 4 with SCHEMA_FROZEN`() {
        // Sample rows 2..3-1 == first 3 data rows are integers; row 4 is a
        // boolean — must refuse with SCHEMA_FROZEN pointing at the cell.
        val bytes = "name,count,active\ns1,1,true\ns2,2,true\ns3,3,true\ns4,not_a_number,true\n"
            .toByteArray()
        val options = DecodeOptions(inferSampleSize = 3)
        val result = decoder.decode(bytes, options)
        assertTrue(result is DecodeResult.Refused, "expected Refused, got $result")
        assertEquals(
            DecodeRefusalCode.SCHEMA_FROZEN,
            (result as DecodeResult.Refused).refusal.code,
        )
    }

    @Test
    fun `RFC 4180 quoted multi-line cell parses correctly`() {
        // Header row, then one data row with a quoted cell spanning two lines.
        val bytes = "name,note\n\"alice\",\"line1\nline2\"\n".toByteArray()
        val result = decoder.decode(bytes)
        val doc = (result as DecodeResult.Ok).documents.single()
        val row = (doc.root as ValueNode.SequenceValue).elements[0] as ValueNode.MappingValue
        assertEquals(ValueNode.TextValue("alice"), row.entries.getValue("name"))
        assertEquals(ValueNode.TextValue("line1\nline2"), row.entries.getValue("note"))
    }

    @Test
    fun `duplicate headers refuse instead of overwriting a cell and its source identity`() {
        val result = decoder.decode("name,name\nalice,bob\n".toByteArray())

        assertTrue(result is DecodeResult.Refused, "duplicate column keys lose a materialized node: $result")
        assertEquals(DecodeRefusalCode.DUPLICATE_KEY, (result as DecodeResult.Refused).refusal.code)
    }

    @Test
    fun `empty bytes yields MALFORMED`() {
        val result = decoder.decode(ByteArray(0))
        assertTrue(result is DecodeResult.Refused)
        assertEquals(DecodeRefusalCode.MALFORMED, (result as DecodeResult.Refused).refusal.code)
    }

    @Test
    fun `whole document source map follows every node in structural preorder`() {
        val result = decoder.decode("name,count\nalice,1\nbob,2\n".toByteArray())
        val doc = (result as DecodeResult.Ok).documents.single()

        assertEquals(7, doc.sourceMap.entries.size, "sequence + 2 row mappings + 4 cell values")
        assertEquals(SourceAnchor.Logical("csv://document/rows"), doc.sourceMap.of(NodeId(0)))
        assertEquals(SourceAnchor.Cell(2L, 1L), doc.sourceMap.of(NodeId(1)))
        assertEquals(SourceAnchor.Cell(2L, 1L), doc.sourceMap.of(NodeId(2)))
        assertEquals(SourceAnchor.Cell(2L, 2L), doc.sourceMap.of(NodeId(3)))
        assertEquals(SourceAnchor.Cell(3L, 1L), doc.sourceMap.of(NodeId(4)))
        assertEquals(SourceAnchor.Cell(3L, 1L), doc.sourceMap.of(NodeId(5)))
        assertEquals(SourceAnchor.Cell(3L, 2L), doc.sourceMap.of(NodeId(6)))
    }

    @Test
    fun `each row source map assigns container before cell values`() {
        val docs = (decoder.decode(
            "name,count\nalice,1\nbob,2\n".toByteArray(),
            DecodeOptions(csvMode = CsvMode.EACH_ROW),
        ) as DecodeResult.Ok).documents

        assertEquals(2, docs.size)
        assertEquals(3, docs[0].sourceMap.entries.size, "mapping + 2 cells")
        assertEquals(SourceAnchor.Cell(2L, 1L), docs[0].sourceMap.of(NodeId(0)))
        assertEquals(SourceAnchor.Cell(2L, 1L), docs[0].sourceMap.of(NodeId(1)))
        assertEquals(SourceAnchor.Cell(2L, 2L), docs[0].sourceMap.of(NodeId(2)))
        assertEquals(SourceAnchor.Cell(3L, 1L), docs[1].sourceMap.of(NodeId(0)))
        assertEquals(SourceAnchor.Cell(3L, 1L), docs[1].sourceMap.of(NodeId(1)))
        assertEquals(SourceAnchor.Cell(3L, 2L), docs[1].sourceMap.of(NodeId(2)))
    }
}