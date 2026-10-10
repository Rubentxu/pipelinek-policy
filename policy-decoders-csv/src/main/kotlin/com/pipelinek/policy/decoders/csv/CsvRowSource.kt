package com.pipelinek.policy.decoders.csv

import com.pipelinek.policy.decoder.DecodeRefusal
import com.pipelinek.policy.decoder.DecodeRefusalCode
import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * M8 WU-3 · Lazy row cursor over RFC 4180 CSV bytes.
 *
 * Design D2: streaming lives in the decoders, not the kernel. This source
 * reuses the same tokenizer semantics as [CsvResourceDecoder]'s internal
 * tokenizer (quoted cells, `""` escapes, CRLF/LF row endings) but yields one
 * [ValueNode.MappingValue] row at a time — the full document is NEVER
 * materialized, so a 1 GiB file costs O(row) memory.
 *
 * Contract (docs/07 §5, M8 spec REQ 03):
 *  - Header (row 1) is consumed eagerly and returned by [header].
 *  - Rows are 1-indexed like the whole-document decoder (row 2 = first data row).
 *  - Cells are TextValue (no inference): parity with the certified
 *    whole-document contract (M10 CsvParityTest).
 *  - Structural errors (unterminated quote, ragged row) surface as
 *    [DecodeRefusal] via [next] — evaluation stops, the refusal propagates.
 */
class CsvRowSource(private val bytes: ByteArray) {

    private var i = 0
    private var line = 1
    private val header: List<String>
    private var nextRowNumber = 2L // header is row 1

    init {
        if (bytes.isEmpty()) {
            throw CsvRowRefusal(DecodeRefusalCode.MALFORMED, SourceAnchor.Logical("csv://empty"))
        }
        header = readRow()
        if (header.isEmpty()) {
            throw CsvRowRefusal(
                DecodeRefusalCode.MALFORMED,
                SourceAnchor.Logical("csv://header"),
            )
        }
    }

    val columnCount: Int get() = header.size

    /**
     * Yield the next data row as a MappingValue, or null at EOF.
     * Throws [CsvRefusal] on structural malformation (unterminated quote /
     * ragged row vs header width).
     */
    fun next(): ValueNode.MappingValue? {
        consumeRowEnd()
        if (i >= bytes.size) return null
        val row = readRow()
        if (row.size != header.size) {
            throw CsvRowRefusal(
                DecodeRefusalCode.MALFORMED,
                SourceAnchor.Logical("csv://ragged-row@$nextRowNumber"),
            )
        }
        val rowNumber = nextRowNumber
        nextRowNumber++
        val entries = linkedMapOf<String, ValueNode>()
        header.forEachIndexed { idx, col ->
            entries[col] = ValueNode.TextValue(row[idx])
        }
        return ValueNode.MappingValue(entries)
    }

    /** Skip the row terminator (LF or CRLF) between rows, if present. */
    private fun consumeRowEnd() {
        if (i < bytes.size && bytes[i] == '\r'.code.toByte()) {
            i++
            if (i < bytes.size && bytes[i] == '\n'.code.toByte()) i++
            line++
        } else if (i < bytes.size && bytes[i] == '\n'.code.toByte()) {
            i++
            line++
        }
    }

    // --- Tokenizer (same semantics as CsvResourceDecoder.CsvTokenizer) ---

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
        // B5.3 / P1-08: collect the raw BYTES of the field and decode them as
        // UTF-8 once, instead of mapping each byte to a char. The previous
        // `c.toInt().toChar()` turned the 5 bytes of "café" into 5 chars
        // reading "cafÃ©", silently corrupting every non-ASCII value.
        val start = i
        while (i < bytes.size) {
            val c = bytes[i]
            if (c == ','.code.toByte() || c == '\n'.code.toByte() || c == '\r'.code.toByte()) break
            i++
        }
        return String(bytes, start, i - start, Charsets.UTF_8)
    }

    private fun readQuotedCell(): String {
        i++ // consume opening quote
        // B5.3 / P1-08: the quoted-cell loop is a SEPARATE copy of the
        // byte-to-char defect. It accumulates raw BYTES and decodes once.
        // Decoding one byte at a time would replace every byte of a multibyte
        // sequence with U+FFFD, which is corruption of a different kind.
        val raw = java.io.ByteArrayOutputStream()
        while (i < bytes.size) {
            val c = bytes[i]
            if (c == '"'.code.toByte()) {
                if (i + 1 < bytes.size && bytes[i + 1] == '"'.code.toByte()) {
                    raw.write('"'.code)
                    i += 2
                } else {
                    i++
                    return raw.toString(Charsets.UTF_8)
                }
            } else {
                if (c == '\n'.code.toByte()) line++
                raw.write(c.toInt())
                i++
            }
        }
        throw CsvRowRefusal(
            DecodeRefusalCode.MALFORMED,
            SourceAnchor.Logical("csv://unterminated-quote@$line"),
        )
    }
}

/** Streaming refusal carrying the decoder-refusal contract (public: the CLI maps it to exit codes). */
class CsvRowRefusal(
    val code: DecodeRefusalCode,
    val anchor: SourceAnchor,
) : RuntimeException()
