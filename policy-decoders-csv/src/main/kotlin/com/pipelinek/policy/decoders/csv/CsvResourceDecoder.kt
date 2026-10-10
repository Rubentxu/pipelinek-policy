package com.pipelinek.policy.decoders.csv

import com.pipelinek.policy.decoder.CsvMode
import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeRefusal
import com.pipelinek.policy.decoder.DecodeRefusalCode
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.DecoderDescriptor
import com.pipelinek.policy.decoder.NodeId
import com.pipelinek.policy.decoder.ResourceAttributes
import com.pipelinek.policy.decoder.ResourceDecoder
import com.pipelinek.policy.decoder.ResourceDocument
import com.pipelinek.policy.decoder.ResourceFormat
import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.decoder.SourceMap
import com.pipelinek.policy.kernel.value.ResourceId
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * Spec REQ §"CSV modes" + ADR-0011 D11.1 — hand-rolled RFC 4180 streaming
 * CSV decoder. No external parser libraries (law 6).
 *
 * Modes:
 *   - `WHOLE_DOCUMENT` → one `ResourceDocument`, root = `SequenceValue` of
 *     `MappingValue` rows (header keys × N data rows).
 *   - `EACH_ROW`       → N `ResourceDocument`s, each with root =
 *     `MappingValue(headerKey → cell)` of a single data row. Header is
 *     row 1; rows below are 1-indexed.
 *
 * Type inference:
 *   - When `options.inferSampleSize == 0` (default, ADR-0011 D-05): every
 *     cell becomes `TextValue` regardless of content. This is the safe
 *     default — no silent coercion (architectural law 9).
 *   - When `options.inferSampleSize > 0`: sample rows 1..N, freeze a
 *     `Map<columnName, ColumnKind>`, and on any row > N whose cell does
 *     NOT match the frozen kind for its column, refuse with
 *     `SCHEMA_FROZEN` (mutation gate item 3: dropping the freeze kills
 *     the test).
 *
 * Source anchors:
 *   - Each cell carries `SourceAnchor.Cell(row, column)`.
 *   - The WHOLE_DOCUMENT sequence root carries a logical anchor; each row
 *     mapping is anchored at its first cell. In EACH_ROW mode the row mapping
 *     is anchored at its first cell. Container anchors are registered before
 *     children so NodeIds follow structural pre-order.
 */
class CsvResourceDecoder : ResourceDecoder {

    override val descriptor: DecoderDescriptor = DecoderDescriptor(
        format = ResourceFormat.CSV,
        version = "1.0.0-m2",
    )

    override fun decode(
        bytes: ByteArray,
        options: DecodeOptions,
    ): DecodeResult {
        val rows = try {
            CsvTokenizer(bytes).tokenize()
        } catch (refusal: CsvRefusal) {
            return DecodeResult.Refused(DecodeRefusal(refusal.code, refusal.anchor))
        }
        if (rows.isEmpty()) {
            return DecodeResult.Refused(
                DecodeRefusal(DecodeRefusalCode.MALFORMED, anchor = null),
            )
        }
        val header = rows.first()
        val dataRows = rows.drop(1)
        if (header.isEmpty()) {
            return DecodeResult.Refused(
                DecodeRefusal(
                    DecodeRefusalCode.MALFORMED,
                    anchor = SourceAnchor.Logical("csv://header"),
                ),
            )
        }
        val seenHeaders = mutableSetOf<String>()
        val duplicateHeaderIndex = header.indexOfFirst { !seenHeaders.add(it) }
        if (duplicateHeaderIndex >= 0) {
            return DecodeResult.Refused(
                DecodeRefusal(
                    DecodeRefusalCode.DUPLICATE_KEY,
                    anchor = SourceAnchor.Cell(row = 1L, column = (duplicateHeaderIndex + 1).toLong()),
                ),
            )
        }

        // INFER_WITH_SAMPLE(N): sample the first N data rows and freeze
        // each column's kind. If any later row violates the kind, refuse
        // BEFORE we materialize documents so the caller learns about the
        // violation atomically (mutation gate item 3: dropping the freeze
        // kills the test).
        if (options.inferSampleSize > 0) {
            val sample = dataRows.take(options.inferSampleSize)
            val schema = freezeSchema(header, sample)
            val violation = findSchemaViolation(header, dataRows, schema)
            if (violation != null) return DecodeResult.Refused(violation)
        }

        return when (options.csvMode) {
            CsvMode.WHOLE_DOCUMENT -> {
                val sourceMap = mutableMapOf<NodeId, SourceAnchor>()
                sourceMap[NodeId(0L)] = SourceAnchor.Logical("csv://document/rows")
                val elements = dataRows.mapIndexed { idx, row ->
                    rowToMapping(header, row, rowNumber = idx + 2, sourceMap)
                }
                val seq = ValueNode.SequenceValue(elements)
                val doc = ResourceDocument(
                    id = ResourceId("csv://document"),
                    format = ResourceFormat.CSV,
                    root = seq,
                    sourceMap = SourceMap(sourceMap.toMap()),
                    attributes = ResourceAttributes(mediaType = "text/csv"),
                )
                DecodeResult.Ok(listOf(doc))
            }
            CsvMode.EACH_ROW -> {
                val documents = dataRows.mapIndexed { idx, row ->
                    val sourceMap = mutableMapOf<NodeId, SourceAnchor>()
                    val root = rowToMapping(header, row, rowNumber = idx + 2, sourceMap)
                    ResourceDocument(
                        id = ResourceId("csv://row-${idx + 2}"),
                        format = ResourceFormat.CSV,
                        root = root,
                        sourceMap = SourceMap(sourceMap.toMap()),
                        attributes = ResourceAttributes(mediaType = "text/csv"),
                    )
                }
                if (documents.isEmpty()) {
                    DecodeResult.Refused(
                        DecodeRefusal(DecodeRefusalCode.MALFORMED, anchor = null),
                    )
                } else {
                    DecodeResult.Ok(documents)
                }
            }
        }
    }

    /**
     * Builds a `MappingValue(header → cell)` for one data row. The cell
     * type depends on `inferSampleSize`: if 0, always `TextValue`; if > 0,
     * the cell type is `TextValue | NumberValue | BooleanValue | Null`
     * depending on the column's frozen `ColumnKind`.
     */
    private fun rowToMapping(
        header: List<String>,
        row: List<String>,
        rowNumber: Int,
        sourceMap: MutableMap<NodeId, SourceAnchor>,
        schema: Map<String, ColumnKind>? = null,
    ): ValueNode.MappingValue {
        val entries = linkedMapOf<String, ValueNode>()
        val mappingId = NodeId(sourceMap.size.toLong())
        sourceMap[mappingId] = SourceAnchor.Cell(row = rowNumber.toLong(), column = 1L)
        header.forEachIndexed { idx, col ->
            val raw = row.getOrNull(idx) ?: ""
            val node: ValueNode = if (schema != null) {
                coerce(raw, schema[col] ?: ColumnKind.TEXT)
            } else {
                ValueNode.TextValue(raw)
            }
            val id = NodeId(sourceMap.size.toLong())
            sourceMap[id] = SourceAnchor.Cell(row = rowNumber.toLong(), column = (idx + 1).toLong())
            entries[col] = node
        }
        return ValueNode.MappingValue(entries)
    }

    private fun freezeSchema(
        header: List<String>,
        sample: List<List<String>>,
    ): Map<String, ColumnKind> {
        val kinds = mutableMapOf<String, ColumnKind>()
        header.forEachIndexed { idx, col ->
            val values = sample.map { it.getOrNull(idx) ?: "" }
            val kind = ColumnKind.infer(values)
            kinds[col] = kind
        }
        return kinds
    }

    private fun findSchemaViolation(
        header: List<String>,
        rows: List<List<String>>,
        schema: Map<String, ColumnKind>,
    ): DecodeRefusal? {
        rows.forEachIndexed { i, row ->
            val rowNumber = i + 2 // header is row 1
            header.forEachIndexed { idx, col ->
                val raw = row.getOrNull(idx) ?: ""
                val expected = schema[col] ?: ColumnKind.TEXT
                if (!ColumnKind.matches(raw, expected)) {
                    return DecodeRefusal(
                        DecodeRefusalCode.SCHEMA_FROZEN,
                        anchor = SourceAnchor.Cell(row = rowNumber.toLong(), column = (idx + 1).toLong()),
                    )
                }
            }
        }
        return null
    }

    private fun coerce(raw: String, kind: ColumnKind): ValueNode = when (kind) {
        ColumnKind.TEXT -> ValueNode.TextValue(raw)
        ColumnKind.LONG -> if (raw.isEmpty()) ValueNode.Null else ValueNode.NumberValue(raw.toLong())
        ColumnKind.DECIMAL -> if (raw.isEmpty()) ValueNode.Null else ValueNode.NumberValue(java.math.BigDecimal(raw))
        ColumnKind.BOOLEAN -> if (raw.isEmpty()) ValueNode.Null else ValueNode.BooleanValue(raw.equals("true", ignoreCase = true))
        ColumnKind.NULL -> if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) ValueNode.Null else ValueNode.TextValue(raw)
    }

    private enum class ColumnKind {
        TEXT, LONG, DECIMAL, BOOLEAN, NULL;

        companion object {
            fun infer(values: List<String>): ColumnKind {
                val nonEmpty = values.filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                if (nonEmpty.isEmpty()) return NULL
                val allBoolean = nonEmpty.all { it.equals("true", ignoreCase = true) || it.equals("false", ignoreCase = true) }
                if (allBoolean) return BOOLEAN
                val allLong = nonEmpty.all { it.toLongOrNull() != null }
                if (allLong) return LONG
                val allDecimal = nonEmpty.all {
                    runCatching { java.math.BigDecimal(it) }.isSuccess
                }
                if (allDecimal) return DECIMAL
                return TEXT
            }

            fun matches(raw: String, kind: ColumnKind): Boolean = when (kind) {
                ColumnKind.TEXT -> true
                ColumnKind.LONG -> raw.isEmpty() || raw.equals("null", ignoreCase = true) || raw.toLongOrNull() != null
                ColumnKind.DECIMAL -> raw.isEmpty() || raw.equals("null", ignoreCase = true) ||
                    runCatching { java.math.BigDecimal(raw) }.isSuccess
                ColumnKind.BOOLEAN -> raw.isEmpty() || raw.equals("null", ignoreCase = true) ||
                    raw.equals("true", ignoreCase = true) || raw.equals("false", ignoreCase = true)
                ColumnKind.NULL -> raw.isEmpty() || raw.equals("null", ignoreCase = true)
            }
        }
    }

    /**
     * Hand-rolled RFC 4180 streaming tokenizer. Returns a list of rows,
     * each row a list of cell strings. Quoted cells may span newlines;
     * inside a quoted cell, doubled quote (`""`) escapes a literal `"`.
     */
    private class CsvTokenizer(private val bytes: ByteArray) {
        private var i = 0
        private var line = 1

        fun tokenize(): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            while (i < bytes.size) {
                rows += readRow()
                // Optional trailing newline consumed; EOF or another row follows.
                if (i < bytes.size && bytes[i] == '\n'.code.toByte()) {
                    i++
                    line++
                } else if (i < bytes.size && bytes[i] == '\r'.code.toByte()) {
                    i++
                    if (i < bytes.size && bytes[i] == '\n'.code.toByte()) i++
                    line++
                }
            }
            return rows
        }

        private fun readRow(): List<String> {
            val cells = mutableListOf<String>()
            while (true) {
                cells += readCell()
                if (i < bytes.size && bytes[i] == ','.code.toByte()) {
                    i++
                } else {
                    break
                }
            }
            return cells
        }

        private fun readCell(): String {
            if (i < bytes.size && bytes[i] == '"'.code.toByte()) {
                return readQuotedCell()
            }
            // B5.3 / P1-08: collect raw BYTES and decode once as UTF-8. The
            // materialised tokenizer and the streaming source are separate
            // copies of this loop; fixing only one would leave the other
            // silently corrupting every non-ASCII value.
            val start = i
            while (i < bytes.size) {
                val c = bytes[i]
                if (c == ','.code.toByte() || c == '\n'.code.toByte() || c == '\r'.code.toByte()) break
                i++
            }
            return String(bytes, start, i - start, Charsets.UTF_8)
        }

        private fun readQuotedCell(): String {
            // Consume opening quote.
            i++
            // B5.3 / P1-08: same byte-to-char defect as [readCell]. Accumulate
            // bytes; decoding one at a time would turn each byte of a multibyte
            // sequence into U+FFFD.
            val raw = java.io.ByteArrayOutputStream()
            while (i < bytes.size) {
                val c = bytes[i]
                if (c == '"'.code.toByte()) {
                    if (i + 1 < bytes.size && bytes[i + 1] == '"'.code.toByte()) {
                        // Escaped quote
                        raw.write('"'.code)
                        i += 2
                    } else {
                        // Closing quote
                        i++
                        return raw.toString(Charsets.UTF_8)
                    }
                } else {
                    if (c == '\n'.code.toByte()) line++
                    raw.write(c.toInt())
                    i++
                }
            }
            throw CsvRefusal(
                DecodeRefusalCode.MALFORMED,
                SourceAnchor.Logical("csv://unterminated-quote@$line"),
            )
        }
    }

    private class CsvRefusal(
        val code: DecodeRefusalCode,
        val anchor: SourceAnchor,
    ) : RuntimeException()
}
