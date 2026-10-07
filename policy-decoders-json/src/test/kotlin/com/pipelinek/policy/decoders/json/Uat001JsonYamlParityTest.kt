package com.pipelinek.policy.decoders.json

import com.pipelinek.policy.decoders.yaml.YamlResourceDecoder
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.SourceMap
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §UAT-001 — equivalent JSON and YAML inputs MUST produce
 * `ValueTree`s with identical `canonicalDigest()`s (spec §"Semantic
 * parity JSON ↔ YAML"). Source spans MUST differ between the two
 * decoders.
 *
 * Synthetic-only fixtures (P0 fixture-license deferral): the JSON
 * payload below is hand-rolled, not imported from any legacy repo.
 */
class Uat001JsonYamlParityTest {

    @Test
    fun `JSON and YAML of {a=1, b=2} yield identical canonicalDigest and distinct source spans`() {
        val jsonBytes = """{"a":1,"b":2}""".toByteArray()
        val yamlBytes = "a: 1\nb: 2\n".toByteArray()

        val jsonDoc = (JsonResourceDecoder().decode(jsonBytes) as DecodeResult.Ok).documents.single()
        val yamlDoc = (YamlResourceDecoder().decode(yamlBytes) as DecodeResult.Ok).documents.single()

        // Canonical digest MUST match (cross-decoder equality).
        assertEquals(jsonDoc.root.canonicalDigest(), yamlDoc.root.canonicalDigest())
        // SourceMap MUST contain at least one span on each side (regression
        // guard for mutation gate item 4: dropping SourceMap leaves digest
        // unchanged but kills the per-decoder source-anchor identity).
        assertTrue(jsonDoc.sourceMap.entries.isNotEmpty())
        assertTrue(yamlDoc.sourceMap.entries.isNotEmpty())
        // The two maps may share anchors by coincidence; in practice the
        // line/column values differ. We assert not-equal as a coarse
        // regression: if either side flattens to SourceMap.EMPTY, this
        // test fails loudly.
        assertNotEquals(
            jsonDoc.sourceMap.entries.toList(),
            yamlDoc.sourceMap.entries.toList(),
        )
        // Empty SourceMap is the silent failure mode we are guarding against.
        assertNotEquals(SourceMap.EMPTY, jsonDoc.sourceMap)
        assertNotEquals(SourceMap.EMPTY, yamlDoc.sourceMap)
    }

    @Test
    fun `JSON nested mapping matches YAML nested mapping canonicalDigest`() {
        val jsonBytes = """{"spec":{"replicas":3,"image":"web"}}""".toByteArray()
        val yamlBytes = "spec:\n  replicas: 3\n  image: web\n".toByteArray()

        val jsonDoc = (JsonResourceDecoder().decode(jsonBytes) as DecodeResult.Ok).documents.single()
        val yamlDoc = (YamlResourceDecoder().decode(yamlBytes) as DecodeResult.Ok).documents.single()

        assertEquals(jsonDoc.root.canonicalDigest(), yamlDoc.root.canonicalDigest())

        // Sanity: nested mapping reaches the right shape.
        val jsonRoot = jsonDoc.root as ValueNode.MappingValue
        assertEquals(
            ValueNode.NumberValue(3L),
            ((jsonRoot.entries.getValue("spec") as ValueNode.MappingValue).entries.getValue("replicas")),
        )
    }
}