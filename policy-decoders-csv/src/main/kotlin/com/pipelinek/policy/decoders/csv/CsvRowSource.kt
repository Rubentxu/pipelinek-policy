package com.pipelinek.policy.decoders.csv

import com.pipelinek.policy.decoder.DecodeRefusal
import com.pipelinek.policy.decoder.DecodeRefusalCode
import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.kernel.value.ValueNode
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * H3.1 · A pull-based window over document bytes.
 *
 * The cursor used to index a `ByteArray` directly, which made it lazy in its
 * PARSING but not in its MEMORY: the whole document was already in heap before
 * the first row was yielded, so the KDoc's "a 1 GiB file costs O(row) memory"
 * was not something the signature could deliver. It held a `ByteArray` and
 * indexed it; there was no read path that could avoid that.
 *
 * [ByteSource] replaces the indexing with a one-byte-lookahead pull. A windowed
 * source refills from its stream and answers `at`/`advance` without ever holding
 * more than [window] plus one byte, so memory is O(longest cell), not O(file).
 * The array-backed source answers the same questions over already-resident
 * bytes and retains the existing behaviour exactly, including its error anchors.
 *
 * This is the only place the two representations meet, which is what keeps the
 * tokenizer itself unchanged: one tokenizer, two byte sources.
 */
internal interface ByteSource {
    /** The byte at the cursor, or -1 at end of document. */
    fun at(): Int

    /**
     * The byte [ahead] positions past the cursor, or -1 at end of document.
     *
     * The CSV tokenizer needs to tell an escaped `""` from a closing quote. With
     * an indexed array that was `bytes[i + 1]`; over a windowed stream the byte
     * may not be buffered yet, so this pulls forward WITHOUT moving the cursor.
     * Every call site uses 0 or 1, so a stream needs only two bytes of
     * lookahead and the implementations stay trivial.
     */
    fun at(ahead: Int): Int

    fun advance()

    /**
     * A source over bytes already in memory.
     *
     * Kept as a separate class rather than a null check inside the interface so
     * the hot path stays a virtual call with no branching, and so the two
     * implementations cannot disagree about semantics.
     */
    class Array(private val bytes: ByteArray) : ByteSource {
        private var i = 0

        override fun at(): Int = at(0)

        override fun at(ahead: Int): Int {
            val idx = i + ahead
            return if (idx in bytes.indices) bytes[idx].toInt() and 0xFF else -1
        }

        override fun advance() {
            i++
        }

    }

    /**
     * A source that pulls from an [InputStream] through a sliding window.
     *
     * The window is [window] bytes, filled by [fill]. `at` always has one byte
     * of lookahead available, so the tokenizer can keep its original shape --
     * `at()` for the delimiter test, `advance()` after consuming -- without
     * knowing anything about buffering.
     */
    class Windowed(
        private val stream: InputStream,
        private val window: Int = DEFAULT_WINDOW,
    ) : ByteSource {
        private var buf = ByteArray(window)
        private var bufStart = 0L // absolute offset of buf[0]
        private var bufLen = 0 // valid bytes in buf
        private var cursor = 0L // absolute offset of the cursor
        private var ended = false

        override fun at(): Int = at(0)

        override fun at(ahead: Int): Int {
            ensure(ahead + 1)
            val idx = (cursor - bufStart + ahead).toInt()
            return if (idx < bufLen) buf[idx].toInt() and 0xFF else -1
        }

        override fun advance() {
            cursor++
        }


        /**
         * Make sure at least [want] bytes are buffered from the cursor.
         *
         * Bytes already buffered are kept and only the shortfall is pulled, so
         * a caller walking forward never re-reads. When the window no longer
         * reaches far enough, it is slid to the cursor rather than refilled from
         * position zero, which is what bounds memory: the buffer is discarded
         * instead of growing.
         */
        private fun ensure(want: Int) {
            while (!ended && (bufLen - (cursor - bufStart)) < want) {
                val space = buf.size - bufLen
                if (space <= 0) {
                    if (cursor > bufStart) {
                        slide()
                    } else {
                        // Full, and the cursor is already at bufStart, so
                        // sliding cannot free anything. Reachable only when
                        // `want` exceeds the window, which at(0)/at(1) never do.
                        // Failing loudly beats the infinite loop a zero-length
                        // read would cause here.
                        require(false) { "ensure($want) exceeds the $window-byte window" }
                    }
                }
                val room = buf.size - bufLen
                if (room <= 0) break
                val n = stream.read(buf, bufLen, room)
                if (n < 0) {
                    ended = true
                    break
                }
                bufLen += n
            }
        }

        /**
         * Drop the bytes before the cursor so the window always covers the
         * cursor and whatever follows it.
         */
        private fun slide() {
            val at = (cursor - bufStart).toInt()
            if (at <= 0) return
            buf.copyInto(buf, 0, at, bufLen)
            bufLen -= at
            bufStart = cursor
        }

        private companion object {
            /**
             * Large enough that a realistic CSV cell never forces a slide, small
             * enough that memory is visibly not proportional to the file. A
             * quoted cell longer than this is handled correctly -- `take` spans
             * windows -- it just slides more often.
             */
            const val DEFAULT_WINDOW = 8 * 1024
        }
    }
}

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
class CsvRowSource private constructor(
    private val src: ByteSource,
    private val ownedStream: InputStream? = null,
) {

    private var line = 1
    private val header: List<String>
    private var nextRowNumber = 2L // header is row 1

    /**
     * Open a cursor over bytes already in memory.
     *
     * Retained because decoding a resource that the host already admitted, or
     * one that came out of a pack, is genuinely cheaper from an array. It is
     * the pre-H3.1 behaviour and it is unchanged: same tokenizer, same refusal
     * anchors, same rows.
     */
    constructor(bytes: ByteArray) : this(ByteSource.Array(bytes))

    /**
     * H3.1 · Open a cursor over a stream, without materializing the document.
     *
     * This is what makes the "a 1 GiB file costs O(row) memory" claim in the
     * KDoc true rather than aspirational: bytes are pulled through a sliding
     * window, so the peak heap is the window plus the current cell instead of
     * the whole file.
     *
     * The caller owns [stream] and should close it. This cursor never closes
     * it, because a refusal mid-stream leaves the stream half-read and closing
     * it here would make the caller's cleanup invisible.
     */
    companion object {
        /**
         * Open a cursor that OWNS [stream] and will close it on [closeQuietly].
         *
         * [close] is a function because an array-backed cursor owns nothing, and
         * giving a pure cursor an `AutoCloseable` that does nothing would invite
         * a caller to rely on a close that never releases a resource.
         */
        fun open(stream: InputStream): CsvRowSource =
            CsvRowSource(ByteSource.Windowed(stream), stream)
    }

    /**
     * Release the underlying stream, ignoring a failure.
     *
     * A host folding a cursor has no use for a close error: the rows it needed
     * are already produced, and throwing here would turn a completed evaluation
     * into a failed one. Named for that reason, so the choice is visible at the
     * call site rather than hidden in a runCatching.
     */
    fun closeQuietly() {
        try {
            ownedStream?.close()
        } catch (_: java.io.IOException) {
            // Intentionally ignored; see above.
        }
    }

    init {
        if (src.at() < 0) {
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
        if (src.at() < 0) return null
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
        when (src.at()) {
            '\r'.code -> {
                src.advance()
                if (src.at() == '\n'.code) src.advance()
                line++
            }
            '\n'.code -> {
                src.advance()
                line++
            }
        }
    }

    // --- Tokenizer (same semantics as CsvResourceDecoder.CsvTokenizer) ---

    private fun readRow(): List<String> {
        val cells = mutableListOf<String>()
        while (true) {
            cells += readCell()
            if (src.at() == ','.code) {
                src.advance()
            } else {
                break
            }
        }
        return cells
    }

    private fun readCell(): String {
        if (src.at() == '"'.code) {
            return readQuotedCell()
        }
        // B5.3 / P1-08: collect the raw BYTES of the field and decode them as
        // UTF-8 once, instead of mapping each byte to a char. The previous
        // `c.toInt().toChar()` turned the 5 bytes of "caf\u00e9" into 5 chars
        // reading "caf\u00c3\u00a9", silently corrupting every non-ASCII value.
        //
        // The bytes are appended as they are walked, so a cell never has to be
        // measured and then re-read. That is not a micro-optimisation: an
        // earlier version measured the cell, rewound to its start and copied it,
        // which only works if the window still retains the whole cell. It does
        // not, once the window slides mid-cell, and the result was
        // IllegalArgumentException("rewind of 59 exceeds the retained window") on
        // the 64 MiB UAT fixture. Appending while walking makes the cursor the
        // single source of truth and the window purely a read-ahead detail.
        val raw = ByteArrayOutputStream()
        while (true) {
            val c = src.at()
            if (c < 0 || c == ','.code || c == '\n'.code || c == '\r'.code) break
            raw.write(c)
            src.advance()
        }
        return raw.toString(Charsets.UTF_8)
    }

    private fun readQuotedCell(): String {
        src.advance() // consume opening quote
        // B5.3 / P1-08: the quoted-cell loop is a SEPARATE copy of the
        // byte-to-char defect. It accumulates raw BYTES and decodes once.
        // Decoding one byte at a time would replace every byte of a multibyte
        // sequence with U+FFFD, which is corruption of a different kind.
        //
        // A quoted cell can be longer than the read window, so this grows
        // incrementally instead of demanding the whole cell up front: `take`
        // would fail on a window that is shorter than the cell.
        val raw = ByteArrayOutputStream()
        while (true) {
            val c = src.at()
            if (c < 0) {
                throw CsvRowRefusal(
                    DecodeRefusalCode.MALFORMED,
                    SourceAnchor.Logical("csv://unterminated-quote@$line"),
                )
            }
            if (c == '"'.code) {
                // A doubled quote is an escaped quote; a lone one closes the
                // cell. The byte after the quote may not be buffered yet, so
                // this is a lookahead rather than an index.
                if (src.at(1) == '"'.code) {
                    raw.write('"'.code)
                    src.advance()
                    src.advance()
                } else {
                    src.advance()
                    return raw.toString(Charsets.UTF_8)
                }
            } else {
                if (c == '\n'.code) line++
                raw.write(c)
                src.advance()
            }
        }
    }

}
/** Streaming refusal carrying the decoder-refusal contract (public: the CLI maps it to exit codes). */
class CsvRowRefusal(
    val code: DecodeRefusalCode,
    val anchor: SourceAnchor,
) : RuntimeException()
