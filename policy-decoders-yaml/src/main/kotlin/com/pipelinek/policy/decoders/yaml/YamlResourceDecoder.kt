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
import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.decoder.SourceMap
import com.pipelinek.policy.kernel.value.ResourceId
import com.pipelinek.policy.kernel.value.ValueNode
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.DuplicateKeyException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

/**
 * Spec REQ §ADDED REQ 2 (JSON+YAML parity) — YAML decoder.
 *
 * Implementation notes:
 *   - Uses SnakeYAML engine 2.x's `Load.loadAllFromString` to fan out
 *     multi-document streams (`---` separators) into one parsed object
 *     per document (ADR-0011 D-04). Each parsed object is converted to
 *     a `ValueNode` tree via `fromJavaObject`.
 *   - Duplicate keys fail closed (ADR-0011 D-03) at any depth.
 *   - `DecodeOptions.yamlAliasCap` bounds recursive anchor expansion;
 *     the engine enforces the cap natively and surfaces a `Refused`
 *     via `DecodeRefusalCode.ALIAS_EXPANSION_EXCEEDED` when it triggers.
 *   - Source spans default to (1,1) because the high-level `loadAll`
 *     path does not propagate per-scalar marks; the per-document
 *     `NodeId` still lets consumers reference the document root.
 */
class YamlResourceDecoder : ResourceDecoder {

    override val descriptor: DecoderDescriptor = DecoderDescriptor(
        format = ResourceFormat.YAML,
        version = "1.0.0-m2",
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
                .build()
            val loader = Load(settings)
            val objects: Iterable<Any?> = loader.loadAllFromString(text)
            val documents = mutableListOf<ResourceDocument>()
            var index = 0
            for (parsed in objects) {
                val sourceMap = mutableMapOf<NodeId, SourceAnchor>()
                val node = fromJavaObject(parsed, sourceMap)
                val rootId = NodeId(0L)
                sourceMap[rootId] = SourceAnchor.TextSpan(1L, 1L, 1L, 1L)
                documents += ResourceDocument(
                    id = ResourceId("yaml://document-$index"),
                    format = ResourceFormat.YAML,
                    root = node,
                    sourceMap = SourceMap(sourceMap.toMap()),
                    attributes = com.pipelinek.policy.decoder.ResourceAttributes(
                        mediaType = "application/yaml",
                    ),
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
                    anchor = SourceAnchor.Logical("yaml://mapping"),
                ),
            )
        } catch (e: YamlEngineException) {
            val code = if (e.message?.contains("aliases for non-scalar", ignoreCase = true) == true) {
                DecodeRefusalCode.ALIAS_EXPANSION_EXCEEDED
            } else {
                DecodeRefusalCode.MALFORMED
            }
            DecodeResult.Refused(
                DecodeRefusal(code, anchor = SourceAnchor.Logical("yaml://stream")),
            )
        } catch (e: Throwable) {
            DecodeResult.Refused(
                DecodeRefusal(
                    DecodeRefusalCode.MALFORMED,
                    anchor = SourceAnchor.Logical("yaml://stream"),
                ),
            )
        }
    }

    /**
     * Converts the SnakeYAML-parsed Java object graph into a `ValueNode`
     * tree and registers a fresh `NodeId` per node in [sourceMap].
     */
    private fun fromJavaObject(
        value: Any?,
        sourceMap: MutableMap<NodeId, SourceAnchor>,
    ): ValueNode {
        val id = NodeId(sourceMap.size.toLong())
        val node: ValueNode = when (value) {
            null -> ValueNode.Null
            is Map<*, *> -> ValueNode.MappingValue(fromMapping(value, sourceMap))
            is List<*> -> ValueNode.SequenceValue(value.map { fromJavaObject(it, sourceMap) })
            is String -> ValueNode.TextValue(value)
            is Number -> ValueNode.NumberValue(coerceNumber(value))
            is Boolean -> ValueNode.BooleanValue(value)
            else -> throw YamlRefusal(
                DecodeRefusalCode.UNSUPPORTED_HOST_VALUE,
                SourceAnchor.Logical("yaml://unsupported"),
            )
        }
        sourceMap[id] = SourceAnchor.TextSpan(1L, 1L, 1L, 1L)
        return node
    }

    private fun fromMapping(
        map: Map<*, *>,
        sourceMap: MutableMap<NodeId, SourceAnchor>,
    ): Map<String, ValueNode> {
        val out = linkedMapOf<String, ValueNode>()
        val seen = mutableSetOf<String>()
        map.forEach { (k, v) ->
            val keyStr = k.toString()
            if (!seen.add(keyStr)) {
                throw YamlRefusal(
                    DecodeRefusalCode.DUPLICATE_KEY,
                    SourceAnchor.Logical("yaml://mapping@$keyStr"),
                )
            }
            out[keyStr] = fromJavaObject(v, sourceMap)
        }
        return out
    }

    private class YamlRefusal(
        val code: DecodeRefusalCode,
        val anchor: SourceAnchor,
    ) : RuntimeException()

    /**
     * Promotes SnakeYAML's small-integer carriers (`Integer` / `Short` /
     * `Byte`) to `Long` so the integer-vs-decimal invariant matches the
     * JSON decoder and the kernel stays type-stable. Decimals and BigInteger
     * pass through untouched (mutation gate item 3).
     */
    private fun coerceNumber(n: Number): Number {
        return when (n) {
            is Long -> n
            is Int -> n.toLong()
            is Short -> n.toLong()
            is Byte -> n.toLong()
            else -> n
        }
    }
}