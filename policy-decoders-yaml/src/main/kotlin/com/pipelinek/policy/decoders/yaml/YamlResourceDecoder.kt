package com.pipelinek.policy.decoders.yaml

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
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.composer.Composer
import org.snakeyaml.engine.v2.exceptions.DuplicateKeyException
import org.snakeyaml.engine.v2.exceptions.Mark
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.NodeTuple
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.parser.ParserImpl
import org.snakeyaml.engine.v2.scanner.StreamReader
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Spec REQ §ADDED REQ 2 (JSON+YAML parity) + B3 real source spans.
 *
 * Implementation notes:
 *   - Composes the event stream via SnakeYAML engine 2.x `Composer`
 *     (NOT the high-level `Load`) so every node carries its real
 *     `Mark` (line/column). `setUseMarks(true)` is mandatory: without
 *     marks the composer returns empty Optionals and spans degrade.
 *   - Multi-document streams (`---` separators) fan out to one
 *     `ResourceDocument` per document (ADR-0011 D-04), in source order.
 *   - Duplicate keys fail closed (ADR-0011 D-03) at any depth; the
 *     engine itself refuses via `DuplicateKeyException` when
 *     `allowDuplicateKeys(false)`.
 *   - `DecodeOptions.yamlAliasCap` bounds anchor expansion; the engine
 *     enforces it natively (`maxAliasesForCollections`) and surfaces a
 *     `Refused` with `ALIAS_EXPANSION_EXCEEDED`.
 *   - Scalar typing follows the YAML 1.2 core schema resolution as the
 *     engine's composer reports it (`Tag.INT` / `Tag.FLOAT` / `Tag.BOOL`
 *     / `Tag.NULL`); only tagged-plain scalars are typed, everything
 *     else stays text (law 9: no silent coercion). Integer carriers are
 *     `Long` (BigInteger on overflow), decimals are always `BigDecimal`
 *     so JSON/YAML canonicalize identically (mutation gate item 3).
 *   - Spans are 1-indexed line/column `TextSpan`s derived from each
 *     node's start mark (B3: no more (1,1,1,1) placeholders).
 */
class YamlResourceDecoder : ResourceDecoder {

    override val descriptor: DecoderDescriptor = DecoderDescriptor(
        format = ResourceFormat.YAML,
        version = "1.1.0-b3",
    )

    override fun decode(
        bytes: ByteArray,
        options: DecodeOptions,
    ): DecodeResult {
        return try {
            val text = String(bytes, Charsets.UTF_8)
            if (text.isBlank()) {
                return DecodeResult.Refused(
                    DecodeRefusal(DecodeRefusalCode.MALFORMED, anchor = null),
                )
            }
            val settings = LoadSettings.builder()
                .setMaxAliasesForCollections(options.yamlAliasCap)
                .setAllowDuplicateKeys(false)
                .setUseMarks(true)
                .build()
            val reader = StreamReader(text, settings)
            val parser = ParserImpl(reader, settings)
            val composer = Composer(parser, settings)

            val documents = mutableListOf<ResourceDocument>()
            var index = 0
            while (composer.hasNext()) {
                val composed = composer.next()
                val sourceMap = mutableMapOf<NodeId, SourceAnchor>()
                val root = fromNode(composed, sourceMap)
                documents += ResourceDocument(
                    id = ResourceId("yaml://document-$index"),
                    format = ResourceFormat.YAML,
                    root = root,
                    sourceMap = SourceMap(sourceMap.toMap()),
                    attributes = ResourceAttributes(mediaType = "application/yaml"),
                )
                index++
            }
            if (documents.isEmpty()) {
                DecodeResult.Refused(
                    DecodeRefusal(DecodeRefusalCode.MALFORMED, anchor = null),
                )
            } else {
                DecodeResult.Ok(documents)
            }
        } catch (refusal: YamlRefusal) {
            DecodeResult.Refused(DecodeRefusal(refusal.code, refusal.anchor))
        } catch (e: DuplicateKeyException) {
            DecodeResult.Refused(
                DecodeRefusal(
                    DecodeRefusalCode.DUPLICATE_KEY,
                    anchor = run {
                        val mark = e.problemMark
                        if (mark.isPresent) mark.get().toTextSpan() else SourceAnchor.Logical("yaml://mapping")
                    },
                ),
            )
        } catch (e: YamlEngineException) {
            val code = if (e.message?.contains("aliases for non-scalar", ignoreCase = true) == true) {
                DecodeRefusalCode.ALIAS_EXPANSION_EXCEEDED
            } else {
                DecodeRefusalCode.MALFORMED
            }
            DecodeResult.Refused(
                DecodeRefusal(code, SourceAnchor.Logical("yaml://stream")),
            )
        } catch (e: Throwable) {
            DecodeResult.Refused(
                DecodeRefusal(DecodeRefusalCode.MALFORMED, SourceAnchor.Logical("yaml://stream")),
            )
        }
    }

    /** Pre-order conversion mirroring the JSON builder's NodeId assignment. */
    private fun fromNode(
        node: Node,
        sourceMap: MutableMap<NodeId, SourceAnchor>,
    ): ValueNode {
        // Id AND span are registered BEFORE descending so pre-order ids stay
        // unique (post-registration let siblings collide on sourceMap.size).
        val id = NodeId(sourceMap.size.toLong())
        val span = node.startMark
            .map { it.toTextSpan() }
            .orElseGet { SourceAnchor.TextSpan(1L, 1L, 1L, 1L) }
        sourceMap[id] = span
        return when (node) {
            is ScalarNode -> scalarValue(node)
            is SequenceNode -> ValueNode.SequenceValue(
                node.value.map { fromNode(it, sourceMap) },
            )
            is MappingNode -> ValueNode.MappingValue(fromMapping(node, sourceMap))
            else -> throw YamlRefusal(
                DecodeRefusalCode.UNSUPPORTED_HOST_VALUE,
                SourceAnchor.Logical("yaml://unsupported"),
            )
        }
    }

    /** Core-schema scalar resolution: only tagged-plain scalars are typed. */
    private fun scalarValue(node: ScalarNode): ValueNode = when (node.tag) {
        Tag.INT -> {
            val asLong = node.value.toLongOrNull()
            val asBig = if (asLong == null) runCatching { BigInteger(node.value) }.getOrNull() else null
            when {
                asLong != null -> ValueNode.NumberValue(asLong)
                asBig != null -> ValueNode.NumberValue(promoteInteger(asBig))
                // Non-decimal core-schema ints (0x.., 0o..) fall back to
                // text: typing them anyway would be coercion in disguise.
                else -> ValueNode.TextValue(node.value)
            }
        }
        Tag.FLOAT -> {
            val decimal = runCatching { BigDecimal(node.value) }.getOrNull()
            if (decimal != null) ValueNode.NumberValue(decimal) else ValueNode.TextValue(node.value)
        }
        Tag.BOOL -> when (node.value) {
            "true", "True", "TRUE" -> ValueNode.BooleanValue(true)
            "false", "False", "FALSE" -> ValueNode.BooleanValue(false)
            else -> ValueNode.TextValue(node.value)
        }
        Tag.NULL -> ValueNode.Null
        else -> ValueNode.TextValue(node.value)
    }

    private fun promoteInteger(n: BigInteger): Number =
        if (n.bitLength() <= 63) n.toLong() else n

    private fun fromMapping(
        node: MappingNode,
        sourceMap: MutableMap<NodeId, SourceAnchor>,
    ): Map<String, ValueNode> {
        val out = linkedMapOf<String, ValueNode>()
        node.value.forEach { tuple: NodeTuple ->
            val keyNode = tuple.keyNode as? ScalarNode
                ?: throw YamlRefusal(
                    DecodeRefusalCode.UNSUPPORTED_HOST_VALUE,
                    SourceAnchor.Logical("yaml://non-scalar-key"),
                )
            val key = keyNode.value
            if (out.containsKey(key)) {
                throw YamlRefusal(
                    DecodeRefusalCode.DUPLICATE_KEY,
                    run {
                        val mark = keyNode.startMark
                        if (mark.isPresent) mark.get().toTextSpan() else SourceAnchor.Logical("yaml://mapping@$key")
                    },
                )
            }
            out[key] = fromNode(tuple.valueNode, sourceMap)
        }
        return out
    }

    private fun Mark.toTextSpan(): SourceAnchor.TextSpan = SourceAnchor.TextSpan(
        startLine = line.toLong() + 1L,
        startColumn = column.toLong() + 1L,
        endLine = line.toLong() + 1L,
        endColumn = column.toLong() + 2L,
    )

    private class YamlRefusal(
        val code: DecodeRefusalCode,
        val anchor: SourceAnchor,
    ) : RuntimeException()
}
