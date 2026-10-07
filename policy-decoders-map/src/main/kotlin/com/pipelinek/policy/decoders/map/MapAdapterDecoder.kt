package com.pipelinek.policy.decoders.map

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
 * Spec REQ §"Map adapter" — recursive descent over Kotlin in-memory data
 * structures. The decoder takes the host-supplied value via
 * [decodeMap] (not [decode]); [decode] refuses with
 * `UNSUPPORTED_HOST_VALUE` so the SPI does not pretend byte input is
 * meaningful.
 *
 * Why a decoder at all for an in-memory adapter?
 *   - Keeps the same DI / lifecycle shape as JSON/YAML/CSV decoders, so
 *     downstream `DecoderContributor` code does not need a special branch
 *     for in-process data (no `Map<String, Any?>` leaking into public
 *     domain contracts — architectural law 7).
 *   - Lets us swap the host-supplied value's root and feed the kernel
 *     `ValueNode` trees consistently with the other formats.
 *
 * Supported host types (whitelist, recursive):
 *   - `null`               → `ValueNode.Null`
 *   - `Boolean`            → `ValueNode.BooleanValue`
 *   - `Number`             → `ValueNode.NumberValue` (Long | BigInteger | BigDecimal)
 *   - `String`             → `ValueNode.TextValue`
 *   - `Map<String, *>`     → `ValueNode.MappingValue`
 *   - `List<*>` / `Array<*>` → `ValueNode.SequenceValue`
 *
 * Anything else (a custom bean, `Unit`, `Pair`, …) → refused with
 * `UNSUPPORTED_HOST_VALUE` and a `Logical` anchor naming the path.
 * No reflection (architectural law 5).
 */
class MapAdapterDecoder : ResourceDecoder {

    override val descriptor: DecoderDescriptor = DecoderDescriptor(
        format = ResourceFormat.MAP_ADAPTER,
        version = "1.0.0-m2",
    )

    /**
     * Always refuses — the map adapter takes its host value via
     * [decodeMap], not raw bytes. This keeps the byte-driven SPI
     * honest for [MapAdapterDecoder] and prevents accidental
     * `String → Map` parsing.
     */
    override fun decode(
        bytes: ByteArray,
        options: DecodeOptions,
    ): DecodeResult = DecodeResult.Refused(
        DecodeRefusal(
            DecodeRefusalCode.UNSUPPORTED_HOST_VALUE,
            anchor = SourceAnchor.Logical("map-adapter://bytes-not-accepted"),
        ),
    )

    /**
     * Decode a Kotlin `Map<String, Any?>` into a `ResourceDocument`. The
     * recursive descent only descends into whitelisted carriers and
     * refuses on anything else with `UNSUPPORTED_HOST_VALUE`.
     */
    fun decodeMap(value: Map<String, Any?>): DecodeResult {
        val sourceMap = mutableMapOf<NodeId, SourceAnchor>()
        return try {
            val root = convertValue(value, sourceMap, path = "")
            val rootId = NodeId(sourceMap.size.toLong())
            sourceMap[rootId] = SourceAnchor.Logical("map-adapter://root")
            val doc = ResourceDocument(
                id = ResourceId("map-adapter://document"),
                format = ResourceFormat.MAP_ADAPTER,
                root = root,
                sourceMap = SourceMap(sourceMap.toMap()),
                attributes = ResourceAttributes(mediaType = "application/x-kotlin-map"),
            )
            DecodeResult.Ok(listOf(doc))
        } catch (refusal: UnsupportedHostValue) {
            DecodeResult.Refused(DecodeRefusal(refusal.code, refusal.anchor))
        }
    }

    private fun convertValue(
        value: Any?,
        sourceMap: MutableMap<NodeId, SourceAnchor>,
        path: String,
    ): ValueNode {
        val node: ValueNode = when (value) {
            null -> ValueNode.Null
            is Boolean -> ValueNode.BooleanValue(value)
            is String -> ValueNode.TextValue(value)
            is Number -> ValueNode.NumberValue(coerceNumber(value))
            is Map<*, *> -> {
                // Strict: every key must be a String. A non-string key
                // could silently lose info under toString() coercion.
                val entries = linkedMapOf<String, ValueNode>()
                value.forEach { (k, v) ->
                    val key = k as? String ?: throw UnsupportedHostValue(
                        DecodeRefusalCode.UNSUPPORTED_HOST_VALUE,
                        SourceAnchor.Logical("map-adapter://$path/non-string-key"),
                    )
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    entries[key] = convertValue(v, sourceMap, childPath)
                }
                ValueNode.MappingValue(entries)
            }
            is List<*> -> ValueNode.SequenceValue(
                value.mapIndexed { i, v -> convertValue(v, sourceMap, "$path[$i]") },
            )
            is Array<*> -> ValueNode.SequenceValue(
                value.mapIndexed { i, v -> convertValue(v, sourceMap, "$path[$i]") },
            )
            else -> throw UnsupportedHostValue(
                DecodeRefusalCode.UNSUPPORTED_HOST_VALUE,
                SourceAnchor.Logical("map-adapter://$path/${value::class.simpleName}"),
            )
        }
        val id = NodeId(sourceMap.size.toLong())
        sourceMap[id] = SourceAnchor.Logical("map-adapter://$path")
        return node
    }

    private fun coerceNumber(n: Number): Number = when (n) {
        is Long -> n
        is Int -> n.toLong()
        is Short -> n.toLong()
        is Byte -> n.toLong()
        is java.math.BigInteger -> n
        is java.math.BigDecimal -> n
        else -> n
    }

    private class UnsupportedHostValue(
        val code: DecodeRefusalCode,
        val anchor: SourceAnchor,
    ) : RuntimeException()
}