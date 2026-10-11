package com.pipelinek.policy.decoders.json

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.pipelinek.policy.decoder.DecodeRefusal
import com.pipelinek.policy.decoder.DecodeRefusalCode
import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.kernel.value.ValueNode
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * M8 WU-4 · Lazy row cursor over JSON Lines (one JSON object per line).
 *
 * Design D2 (streaming in decoders) + law 5: uses Jackson's streaming
 * `JsonParser` only — no `ObjectMapper`, no databind — mirroring
 * [JsonResourceDecoder]'s value semantics per row:
 *  - objects only at the row level (a JSONL dataset is a set of records);
 *  - duplicate keys refuse (DUPLICATE_KEY), integers vs decimals preserved;
 *  - structural errors surface as [DecodeRefusal]-carrying [JsonlRefusal].
 *
 * Memory is O(longest line) over an [InputStream], O(file) over a
 * [ByteArray]: the line reader pulls through a sliding window via
 * [JsonlByteSource.Windowed], so a 1 GiB file does not need 1 GiB of heap
 * before the first row is yielded. The array-backed path is retained for
 * tests and for the small fixtures that the CLI does not need to stream.
 */
class JsonlSource private constructor(
    private val src: JsonlByteSource,
    private val ownedStream: InputStream? = null,
) {

    private var lineNumber = 0L

    /**
     * Open a cursor over bytes already in memory.
     *
     * Retained because decoding a resource the host already admitted, or one
     * that came out of a pack, is genuinely cheaper from an array. Same line
     * scanner, same refusal anchors, same rows as the streaming constructor.
     */
    constructor(bytes: ByteArray) : this(JsonlByteSource.Array(bytes))

    /**
     * H3.1 · Open a cursor over a stream, without materializing the document.
     *
     * The caller owns [stream] and should close it. This cursor never closes
     * it, because a refusal mid-stream leaves the stream half-read and closing
     * it here would make the caller's cleanup invisible. The companion returns
     * a cursor that does own the stream; [closeQuietly] releases it.
     */
    companion object {
        /**
         * Open a cursor that OWNS [stream] and will close it on [closeQuietly].
         *
         * [close] is a function because an array-backed cursor owns nothing, and
         * giving a pure cursor an `AutoCloseable` that does nothing would invite
         * a caller to rely on a close that never releases a resource.
         */
        fun open(stream: InputStream): JsonlSource =
            JsonlSource(JsonlByteSource.Windowed(stream), stream)
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

    /**
     * Yield the next row as a MappingValue, or null at EOF. Blank lines are
     * skipped (trailing newline tolerated).
     */
    fun next(): ValueNode.MappingValue? {
        val line = nextNonBlankLine() ?: return null
        lineNumber++
        return parseRow(line)
    }

    private fun nextNonBlankLine(): String? {
        // B5.3 / P1-08: accumulate raw BYTES and decode the line as UTF-8 once.
        // `bytes[pos++].toInt().toChar()` mapped each byte to one char, so any
        // multibyte character was silently corrupted: "€" became three
        // halfwidth-katakana characters and the JSON parser then evaluated a
        // value the author never wrote.
        //
        // Bytes are appended as they are walked, so a line never has to be
        // measured and then re-read. That is not a micro-optimisation: an
        // earlier version of CsvRowSource measured the cell, rewound to its
        // start and copied it, which only worked if the window still retained
        // the whole cell. It did not, once the window slides mid-line, and the
        // result was IllegalArgumentException("rewind of N exceeds the
        // retained window") on a long-line fixture. Appending while walking
        // makes the cursor the single source of truth and the window purely a
        // read-ahead detail.
        val raw = ByteArrayOutputStream()
        while (true) {
            val b = src.at()
            if (b < 0) {
                return if (raw.size() > 0) raw.toString(Charsets.UTF_8) else null
            }
            src.advance()
            if (b == '\n'.code) {
                if (raw.size() > 0) return raw.toString(Charsets.UTF_8)
                raw.reset() // skip blank line
            } else if (b != '\r'.code) {
                raw.write(b)
            }
        }
    }

    private fun parseRow(line: String): ValueNode.MappingValue {
        val parser = JsonFactory().createParser(line.toByteArray())
        return try {
            val first = parser.nextToken()
                ?: throw JsonlRefusal(
                    DecodeRefusalCode.MALFORMED,
                    SourceAnchor.Logical("jsonl://line-$lineNumber/empty"),
                )
            val root = RowBuilder(parser).parseValue(first)
            parser.nextToken()?.let {
                throw JsonlRefusal(
                    DecodeRefusalCode.MALFORMED,
                    SourceAnchor.Logical("jsonl://line-$lineNumber/trailing"),
                )
            }
            parser.close()
            root as? ValueNode.MappingValue ?: throw JsonlRefusal(
                DecodeRefusalCode.MALFORMED,
                SourceAnchor.Logical("jsonl://line-$lineNumber/not-an-object"),
            )
        } catch (duplicate: JsonlRefusal) {
            throw duplicate
        } catch (t: Throwable) {
            throw JsonlRefusal(
                DecodeRefusalCode.MALFORMED,
                SourceAnchor.Logical("jsonl://line-$lineNumber"),
            )
        }
    }

    /** Reuses the certified decoder's value semantics on a fresh parser. */
    private class RowBuilder(private val parser: JsonParser) {
        fun parseValue(current: com.fasterxml.jackson.core.JsonToken): ValueNode {
            return when (current) {
                com.fasterxml.jackson.core.JsonToken.VALUE_STRING -> ValueNode.TextValue(parser.text)
                com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT -> parseInteger()
                com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_FLOAT -> parseDecimal()
                com.fasterxml.jackson.core.JsonToken.VALUE_TRUE -> ValueNode.BooleanValue(true)
                com.fasterxml.jackson.core.JsonToken.VALUE_FALSE -> ValueNode.BooleanValue(false)
                com.fasterxml.jackson.core.JsonToken.VALUE_NULL -> ValueNode.Null
                com.fasterxml.jackson.core.JsonToken.START_OBJECT -> parseObject()
                com.fasterxml.jackson.core.JsonToken.START_ARRAY -> parseArray()
                else -> throw JsonlRefusal(
                    DecodeRefusalCode.MALFORMED,
                    SourceAnchor.Logical("jsonl://unexpected-token"),
                )
            }
        }

        private fun parseObject(): ValueNode.MappingValue {
            val entries = linkedMapOf<String, ValueNode>()
            while (true) {
                val key = parser.nextToken()
                if (key == com.fasterxml.jackson.core.JsonToken.END_OBJECT) break
                val name = parser.currentName
                    ?: throw JsonlRefusal(
                        DecodeRefusalCode.MALFORMED,
                        SourceAnchor.Logical("jsonl://object-key"),
                    )
                if (entries.containsKey(name)) {
                    throw JsonlRefusal(
                        DecodeRefusalCode.DUPLICATE_KEY,
                        SourceAnchor.Logical("jsonl://duplicate-key:$name"),
                    )
                }
                entries[name] = parseValue(parser.nextToken())
            }
            return ValueNode.MappingValue(entries)
        }

        private fun parseArray(): ValueNode.SequenceValue {
            val elements = mutableListOf<ValueNode>()
            while (true) {
                val token = parser.nextToken()
                if (token == com.fasterxml.jackson.core.JsonToken.END_ARRAY) break
                elements += parseValue(token)
            }
            return ValueNode.SequenceValue(elements)
        }

        private fun parseInteger(): ValueNode.NumberValue {
            val text = parser.text
            return ValueNode.NumberValue(
                text.toLongOrNull() ?: java.math.BigInteger(text),
            )
        }

        private fun parseDecimal(): ValueNode.NumberValue =
            ValueNode.NumberValue(java.math.BigDecimal(parser.text))
    }
}

/**
 * H3.1 · A pull-based window over document bytes.
 *
 * Mirrors `CsvRowSource.ByteSource`: an `at()`/`advance()` interface with two
 * implementations, one over a `ByteArray` and one over an `InputStream`. The
 * line scanner in [JsonlSource] reads one byte at a time and does not need a
 * one-byte lookahead, so the interface is smaller than CSV's; the
 * [JsonlByteSource.Windowed] implementation still slides the buffer forward
 * once the cursor walks past the head, so memory is O(longest line), not
 * O(file).
 */
internal interface JsonlByteSource {
    /** The byte at the cursor, or -1 at end of document. */
    fun at(): Int

    fun advance()

    /**
     * A source over bytes already in memory.
     */
    class Array(private val bytes: ByteArray) : JsonlByteSource {
        private var i = 0

        override fun at(): Int =
            if (i in bytes.indices) bytes[i].toInt() and 0xFF else -1

        override fun advance() {
            i++
        }
    }

    /**
     * A source that pulls from an [InputStream] through a sliding window.
     *
     * The window is [window] bytes, filled by [ensure]. `at` always has the
     * current byte buffered, so the line scanner keeps its original shape --
     * `at()` for the byte, `advance()` after consuming -- without knowing
     * anything about buffering.
     */
    class Windowed(
        private val stream: InputStream,
        private val window: Int = DEFAULT_WINDOW,
    ) : JsonlByteSource {
        private var buf = ByteArray(window)
        private var bufStart = 0L // absolute offset of buf[0]
        private var bufLen = 0 // valid bytes in buf
        private var cursor = 0L // absolute offset of the cursor
        private var ended = false

        override fun at(): Int {
            ensure(1)
            val idx = (cursor - bufStart).toInt()
            return if (idx < bufLen) buf[idx].toInt() and 0xFF else -1
        }

        override fun advance() {
            cursor++
        }

        /**
         * Make sure at least [want] bytes are buffered from the cursor.
         *
         * Bytes already buffered are kept and only the shortfall is pulled,
         * so a caller walking forward never re-reads. When the window no
         * longer reaches far enough, it is slid to the cursor rather than
         * refilled from position zero, which is what bounds memory: the
         * buffer is discarded instead of growing.
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
                        // `want` exceeds the window. Failing loudly beats the
                        // infinite loop a zero-length read would cause here.
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
             * Large enough that a realistic JSONL line never forces a slide,
             * small enough that memory is visibly not proportional to the
             * file. A line longer than this is handled correctly -- the
             * scanner accumulates while walking -- it just slides more often.
             */
            const val DEFAULT_WINDOW = 8 * 1024
        }
    }
}

/** Streaming refusal carrying the decoder-refusal contract. */
class JsonlRefusal(
    val code: DecodeRefusalCode,
    val anchor: SourceAnchor,
) : RuntimeException()
