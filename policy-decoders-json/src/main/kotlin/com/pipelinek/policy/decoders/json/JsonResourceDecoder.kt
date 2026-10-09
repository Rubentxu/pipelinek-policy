package com.pipelinek.policy.decoders.json

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonLocation
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeRefusal
import com.pipelinek.policy.decoder.DecodeRefusalCode
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.DecoderDescriptor
import com.pipelinek.policy.decoder.NodeId
import com.pipelinek.policy.decoder.ResourceDecoder
import com.pipelinek.policy.decoder.ResourceDocument
import com.pipelinek.policy.decoder.ResourceFormat
import com.pipelinek.policy.decoder.ResourceAttributes
import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.decoder.SourceMap
import com.pipelinek.policy.kernel.value.ResourceId
import com.pipelinek.policy.kernel.value.ValueNode
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Spec REQ §ADDED REQ 2 (JSON+YAML parity) — JSON decoder that preserves
 * integer vs decimal per YAML 1.2 semantics, fails closed on duplicate
 * keys, and emits one `TextSpan` per generated node.
 *
 * Implementation notes:
 *   - Uses Jackson streaming `JsonParser` only — no `ObjectMapper`, no
 *     databind, no reflection (architectural law 5).
 *   - Duplicate-key detection: a fresh `LinkedHashMap` tracks the
 *     already-seen field names per mapping; a repeat triggers
 *     `Refused(DUPLICATE_KEY, anchor)` (mutation gate item 2).
 *   - Integer vs decimal preserved: a `JsonToken.VALUE_NUMBER_INT` lands
 *     on `NumberValue(BigInteger|Long|Int)`; a `VALUE_NUMBER_FLOAT` lands
 *     on `NumberValue(BigDecimal|Double)`. The textual number is read
 *     and re-decoded per long/integer-or-decimal so the kernel never
 *     loses precision.
 *   - Source spans use 1-indexed `line`/`column` from Jackson's
 *     `currentTokenLocation`; root mapping/array starts at line 1.
 *   - Top-level array: a JSON `[...]` collapses into a single
 *     `ResourceDocument` whose root is `SequenceValue`.
 */
class JsonResourceDecoder : ResourceDecoder {

    override val descriptor: DecoderDescriptor = DecoderDescriptor(
        format = ResourceFormat.JSON,
        version = "1.0.0-m2",
    )

    override fun decode(
        bytes: ByteArray,
        options: DecodeOptions,
    ): DecodeResult {
        return try {
            val parser: JsonParser = JsonFactory().createParser(bytes)
            val firstToken = parser.nextToken()
                ?: return DecodeResult.Refused(DecodeRefusal(DecodeRefusalCode.MALFORMED, anchor = null))
            val builder = Builder(parser)
            val rootNode = builder.parseValue(firstToken)
            // Preserve the parser's actual root token start. Only extend the
            // root end to the end of the complete top-level value.
            val endLoc = parser.currentLocation
            val rootId = NodeId(0L)
            val original = builder.sourceMap[rootId]
            if (original is SourceAnchor.TextSpan) {
                builder.sourceMap[rootId] = original.copy(
                    endLine = endLoc.lineNr.toLong().coerceAtLeast(1L),
                    endColumn = endLoc.columnNr.toLong().coerceAtLeast(1L),
                )
            }
            val doc = ResourceDocument(
                id = ResourceId("json://document-${builder.idCounter - 1}"),
                format = ResourceFormat.JSON,
                root = rootNode,
                sourceMap = SourceMap(builder.sourceMap.toMap()),
                attributes = ResourceAttributes(mediaType = "application/json"),
            )
            parser.close()
            DecodeResult.Ok(listOf(doc))
        } catch (refusal: DuplicateKeyRefusal) {
            DecodeResult.Refused(refusal.asDecodeRefusal())
        } catch (e: Throwable) {
            DecodeResult.Refused(
                DecodeRefusal(
                    DecodeRefusalCode.MALFORMED,
                    anchor = SourceAnchor.Logical("json://stream"),
                ),
            )
        }
    }

    /**
     * Builder accumulates `NodeId -> TextSpan` and the next-id counter.
     * The parser is passed in so each method can call `nextToken()` /
     * `currentName()`.
     */
    private class Builder(private val parser: JsonParser) {
        var idCounter: Long = 0L
            private set
        val sourceMap: MutableMap<NodeId, SourceAnchor> = mutableMapOf()

        fun nextId(): NodeId {
            val id = NodeId(idCounter)
            idCounter++
            return id
        }

        fun parseValue(current: JsonToken): ValueNode {
            return when (current) {
                JsonToken.START_OBJECT -> parseObject()
                JsonToken.START_ARRAY -> parseArray()
                JsonToken.VALUE_STRING -> {
                    // B3: every materialized ValueNode carries a span; scalars
                    // previously skipped registration, breaking NodeId↔node
                    // correspondence for consumers that walk pre-order.
                    val span = registerSpan()
                    val value = parser.text
                    completeSpan(span)
                    ValueNode.TextValue(value)
                }
                JsonToken.VALUE_NUMBER_INT -> parseInteger()
                JsonToken.VALUE_NUMBER_FLOAT -> parseDecimal()
                JsonToken.VALUE_TRUE -> {
                    val span = registerSpan()
                    completeSpan(span)
                    ValueNode.BooleanValue(true)
                }
                JsonToken.VALUE_FALSE -> {
                    val span = registerSpan()
                    completeSpan(span)
                    ValueNode.BooleanValue(false)
                }
                JsonToken.VALUE_NULL -> {
                    val span = registerSpan()
                    completeSpan(span)
                    ValueNode.Null
                }
                else -> throw IllegalStateException("unexpected token $current")
            }
        }

        private fun parseObject(): ValueNode.MappingValue {
            val entries = linkedMapOf<String, ValueNode>()
            val seen = mutableSetOf<String>()
            val span = registerSpan()
            while (true) {
                val token = parser.nextToken()
                if (token == JsonToken.END_OBJECT) break
                require(token == JsonToken.FIELD_NAME) { "expected FIELD_NAME, got $token" }
                val name = parser.currentName
                if (!seen.add(name)) {
                    throw DuplicateKeyRefusal(
                        anchor = spanAtCurrentLocation(parser.currentLocation).let { span ->
                            SourceAnchor.TextSpan(
                                startLine = span.startLine,
                                startColumn = span.startColumn,
                                endLine = span.startLine,
                                endColumn = span.startColumn + name.length,
                            )
                        },
                    )
                }
                val valueToken = parser.nextToken()
                entries[name] = parseValue(valueToken)
            }
            completeSpan(span)
            return ValueNode.MappingValue(entries)
        }

        private fun parseArray(): ValueNode.SequenceValue {
            val elements = mutableListOf<ValueNode>()
            val span = registerSpan()
            while (true) {
                val token = parser.nextToken()
                if (token == JsonToken.END_ARRAY) break
                elements += parseValue(token)
            }
            completeSpan(span)
            return ValueNode.SequenceValue(elements)
        }

        private fun parseInteger(): ValueNode.NumberValue {
            // Re-decode the textual form to keep precision (Long for ints,
            // BigInteger for overflows).
            val span = registerSpan()
            val text = parser.text.trim()
            val number: Number = runCatching { text.toLong() }.getOrNull()
                ?: BigInteger(text)
            completeSpan(span)
            return ValueNode.NumberValue(number)
        }

        private fun parseDecimal(): ValueNode.NumberValue {
            val span = registerSpan()
            val text = parser.text.trim()
            // Always BigDecimal for floats so precision stays arbitrary and
            // JSON/YAML canonicalize identically (mutation gate item 3).
            // A `Double` carrier would silently truncate 0.1 + 0.2 style
            // inputs — the kernel forbids that (architectural law 9).
            val number: Number = BigDecimal(text)
            completeSpan(span)
            return ValueNode.NumberValue(number)
        }

        private fun registerSpan(): Pair<NodeId, JsonLocation> {
            val id = nextId()
            val start = parser.currentTokenLocation()
            sourceMap[id] = spanAtTokenLocation(start)
            return id to start
        }

        private fun completeSpan(registered: Pair<NodeId, JsonLocation>) {
            val (id, start) = registered
            sourceMap[id] = spanBetween(start, parser.currentLocation)
        }
    }

    companion object {
        /** Jackson's token location already points at the token's 1-indexed start column. */
        private fun spanAtTokenLocation(loc: JsonLocation): SourceAnchor.TextSpan {
            val line = loc.lineNr.toLong().coerceAtLeast(1L)
            val col = loc.columnNr.toLong().coerceAtLeast(1L)
            return SourceAnchor.TextSpan(startLine = line, startColumn = col, endLine = line, endColumn = col)
        }

        private fun spanBetween(start: JsonLocation, end: JsonLocation): SourceAnchor.TextSpan =
            SourceAnchor.TextSpan(
                startLine = start.lineNr.toLong().coerceAtLeast(1L),
                startColumn = start.columnNr.toLong().coerceAtLeast(1L),
                endLine = end.lineNr.toLong().coerceAtLeast(1L),
                endColumn = end.columnNr.toLong().coerceAtLeast(1L),
            )

        /** Current parser position is after the parsed field name, so anchor the next column. */
        private fun spanAtCurrentLocation(loc: JsonLocation): SourceAnchor.TextSpan {
            val line = (loc.lineNr).toLong().coerceAtLeast(1L)
            val col = (loc.columnNr + 1).toLong().coerceAtLeast(1L)
            return SourceAnchor.TextSpan(startLine = line, startColumn = col, endLine = line, endColumn = col)
        }
    }

    private class DuplicateKeyRefusal(
        val anchor: SourceAnchor,
    ) : RuntimeException() {
        fun asDecodeRefusal(): DecodeRefusal =
            DecodeRefusal(DecodeRefusalCode.DUPLICATE_KEY, anchor)
    }
}
