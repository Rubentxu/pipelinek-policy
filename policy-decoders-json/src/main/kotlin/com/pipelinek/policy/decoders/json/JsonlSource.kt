package com.pipelinek.policy.decoders.json

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.pipelinek.policy.decoder.DecodeRefusal
import com.pipelinek.policy.decoder.DecodeRefusalCode
import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.kernel.value.ValueNode

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
 * Memory is O(row): each line is parsed independently and released.
 */
class JsonlSource(private val bytes: ByteArray) {

    private var pos = 0
    private var lineNumber = 0L

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
        val sb = StringBuilder()
        while (pos < bytes.size) {
            val c = bytes[pos++].toInt().toChar()
            if (c == '\n') {
                if (sb.isNotEmpty()) return sb.toString()
                sb.setLength(0) // skip blank line
            } else if (c != '\r') {
                sb.append(c)
            }
        }
        return if (sb.isNotEmpty()) sb.toString() else null
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

/** Streaming refusal carrying the decoder-refusal contract. */
class JsonlRefusal(
    val code: DecodeRefusalCode,
    val anchor: SourceAnchor,
) : RuntimeException()
