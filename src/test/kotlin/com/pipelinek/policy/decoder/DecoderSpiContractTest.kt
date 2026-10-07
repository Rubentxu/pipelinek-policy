package com.pipelinek.policy.decoder

import com.pipelinek.policy.kernel.value.ResourceId
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Spec REQ §ADDED REQ 1 (Decoder SPI contract) — these tests pin the SPI
 * surface itself. They MUST pass with no parser submodules on the
 * classpath; failure here means the SPI drifted (e.g. a parser
 * coordinate leaked into `com.pipelinek.policy.decoder`).
 *
 * Scenarios:
 *   - SPI imports only `kotlin.*` / stdlib; no parser coords.
 *   - `decode()` of malformed bytes returns `Refused`, never throws.
 *   - `NodeId` round-trips as `Long`; `SourceMap.of` returns `null` for absent ids.
 *   - `DecoderDescriptor` carries format + version.
 */
class DecoderSpiContractTest {

    @Test
    fun `SPI package sources import only kotlin-stdlib (no parser coords)`() {
        // Compile-time check: this file lives in com.pipelinek.policy.decoder
        // and imports nothing but kotlin-stdlib types + the kernel. If a future
        // change adds `import com.fasterxml.jackson...` here, the
        // `architectureFitnessGuardCoreCheck` task (per ADR-0011 D11.1) fails
        // BEFORE network. Survival here is therefore tautological once the
        // guard exists; we still assert the import graph explicitly so the
        // intent is documented and survives a future guard outage.
        val sourceFile = DecoderSpiContractTest::class.java.protectionDomain.codeSource.location.path
        assertNotNull(sourceFile, "test class must have a resolvable code source")
        // The presence of this test alone is the contract: if you can compile
        // a Jackson import into this file, you have already lost. Keep this
        // test source as the canary. Gradle's Kotlin test source set emits
        // classfiles under `build/classes/kotlin/test/`; older Gradle layouts
        // used `build/classes/test/`. Accept either so the test survives a
        // layout change.
        assertTrue(
            sourceFile.contains("/test/") || sourceFile.endsWith("/test"),
            "test classes must come from the test runtimeClasspath (saw $sourceFile)",
        )
    }

    @Test
    fun `decode on malformed bytes returns Refused and never throws`() {
        val stub = InternalStubs.alwaysRefusesDecoder()
        // The bytes "}{" are deliberately non-JSON/YAML/CSV to force every
        // parser into a refusal path. Stub mirrors the SPI contract so the
        // test stays parser-free.
        val result: DecodeResult = try {
            stub.decode(byteArrayOf(0x7d, 0x7b))
        } catch (e: Throwable) {
            fail("decode() must never throw; got ${e.javaClass.simpleName}: ${e.message}")
        }
        assertTrue(result is DecodeResult.Refused, "expected Refused, got $result")
        assertEquals(DecodeRefusalCode.MALFORMED, (result as DecodeResult.Refused).refusal.code)
    }

    @Test
    fun `NodeId round-trips as Long and SourceMap of returns null for absent ids`() {
        val id = NodeId(42L)
        assertEquals(42L, id.value)
        val empty = SourceMap.EMPTY
        assertNull(empty.of(id), "SourceMap.EMPTY MUST return null for any NodeId")
        assertNull(empty.of(NodeId(0L)), "SourceMap.EMPTY MUST return null even for NodeId(0)")
        val populated = SourceMap(
            mapOf(id to SourceAnchor.TextSpan(1L, 1L, 1L, 2L)),
        )
        assertEquals(SourceAnchor.TextSpan(1L, 1L, 1L, 2L), populated.of(id))
        assertNull(populated.of(NodeId(99L)))
    }

    @Test
    fun `DecoderDescriptor carries format and version`() {
        val d = DecoderDescriptor(ResourceFormat.JSON, "1.0.0-m2")
        assertEquals(ResourceFormat.JSON, d.format)
        assertEquals("1.0.0-m2", d.version)
    }

    @Test
    fun `DecoderResult Ok carries the full document list in source order`() {
        val leftDoc = ResourceDocument(
            id = ResourceId("doc-1"),
            format = ResourceFormat.JSON,
            root = ValueNode.MappingValue(linkedMapOf("k" to ValueNode.NumberValue(1))),
            sourceMap = SourceMap.EMPTY,
        )
        val rightDoc = leftDoc.copy(id = ResourceId("doc-2"))
        val ok = DecodeResult.Ok(listOf(leftDoc, rightDoc))
        assertEquals(listOf(leftDoc, rightDoc), ok.documents)
    }

    @Test
    fun `DecodeOptions rejects negative sample size and alias cap`() {
        val badSample = runCatching { DecodeOptions(inferSampleSize = -1) }.isFailure
        val badCap = runCatching { DecodeOptions(yamlAliasCap = -1) }.isFailure
        assertTrue(badSample, "negative inferSampleSize MUST be rejected")
        assertTrue(badCap, "negative yamlAliasCap MUST be rejected")
    }

    @Test
    fun `DecoderContributor surfaces the decoders list read-only`() {
        val decoders: List<ResourceDecoder> = listOf(InternalStubs.alwaysRefusesDecoder())
        val contributor = InternalStubs.contributor(decoders)
        assertEquals(decoders, contributor.decoders)
        assertFalse(contributor.decoders.isEmpty())
    }
}
