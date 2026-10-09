package com.pipelinek.policy.bundle

import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.ir.IrAdmissionLimits
import com.pipelinek.policy.ir.IrRefusal
import com.pipelinek.policy.ir.LegacyPolicyIrJsonV1
import com.pipelinek.policy.kernel.policy.ParamValue
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PolicyBundleAdmissionFortressTest {
    @Test
    fun `legacy v1 golden bundle remains admissible after canonical codec evolution`() {
        val ir = resource("/ir/legacy-v1-source-ref.json")
        val expectedLegacyDigest = "e13b731b4dc5b8fd9f0305addab67207781361dc0414d5d7738f0fbffd5104f8"
        val document = CanonicalPolicyJson.decode(ir)
        assertContentEquals(ir, checkNotNull(LegacyPolicyIrJsonV1.encode(document)))
        assertEquals(expectedLegacyDigest, sha256(ir))
        val manifest = "{\"bundleId\":\"legacy-set\",\"bundleVersion\":\"1\"," +
            "\"semanticDigest\":\"$expectedLegacyDigest\",\"artifactDigest\":\"$expectedLegacyDigest\"," +
            "\"requiredFunctions\":[],\"irVersion\":1}"
        val bytes = pack(
            sortedMapOf(
                "manifest.json" to manifest.toByteArray(Charsets.UTF_8),
                "metadata.txt" to byteArrayOf(),
                "policy-source-map.txt" to "rule=legacy.kt:1:1-1:10:rule".toByteArray(Charsets.UTF_8),
                "policy.ir.json" to ir,
            ),
        )

        val admitted = BundleVerifier.verifyPacked(bytes)

        assertEquals("legacy-set", admitted.bundle.document.policySet.id)
        assertEquals("legacy.kt", admitted.bundle.document.sourceRefs.getValue("rule").file)
        assertEquals(CanonicalPolicyJson.semanticDigest(admitted.bundle.document), admitted.semanticDigest)

        val tamperedEntries = unpack(bytes).toMutableMap()
        tamperedEntries["policy.ir.json"] = ir.decodeToString()
            .replaceFirst("\"irVersion\":1", "\"irVersion\":1,\"unrecognized\":true")
            .toByteArray(Charsets.UTF_8)
        assertFailsWith<BundleRefusal> { BundleVerifier.verifyPacked(pack(tamperedEntries)) }
    }

    @Test
    fun `decoder rejects duplicate keys invalid number grammar and every admission budget`() {
        val valid = resource("/ir/legacy-v1-source-ref.json")
        val validText = valid.decodeToString()
        val duplicateVersion = validText.replace("\"irVersion\":1", "\"irVersion\":1,\"irVersion\":1")
        assertFailsWith<IrRefusal> { CanonicalPolicyJson.decode(duplicateVersion.toByteArray()) }
        assertFailsWith<IrRefusal> {
            CanonicalPolicyJson.decode(validText.replace("\"irVersion\":1", "\"irVersion\":01").toByteArray())
        }
        assertFailsWith<IrRefusal> {
            CanonicalPolicyJson.decode(validText.replace("\"irVersion\":1", "\"irVersion\":١").toByteArray())
        }
        assertFailsWith<IrRefusal> { CanonicalPolicyJson.decode(byteArrayOf(0xC3.toByte())) }
        assertFailsWith<IrRefusal> {
            CanonicalPolicyJson.decode(valid, IrAdmissionLimits(maxEncodedBytes = valid.size - 1))
        }
        assertFailsWith<IrRefusal> {
            CanonicalPolicyJson.decode(valid, IrAdmissionLimits(maxJsonDepth = 1))
        }
        assertFailsWith<IrRefusal> {
            CanonicalPolicyJson.decode(valid, IrAdmissionLimits(maxJsonNodes = 1))
        }
        assertFailsWith<IrRefusal> {
            CanonicalPolicyJson.decode(valid, IrAdmissionLimits(maxSelectorDepth = 1))
        }
    }

    @Test
    fun `decoder rejects rules exceeding configured rule budget`() {
        val document = CanonicalPolicyJson.decode(resource("/ir/legacy-v1-source-ref.json"))
        val policy = document.policySet.policies.single()
        val twoRules = document.copy(
            policySet = document.policySet.copy(
                policies = listOf(policy.copy(rules = policy.rules + policy.rules.single().copy(id = "rule-2"))),
            ),
        )

        assertFailsWith<IrRefusal> {
            CanonicalPolicyJson.decode(
                CanonicalPolicyJson.encode(twoRules),
                IrAdmissionLimits(maxRules = 1),
            )
        }
    }

    @Test
    fun `legacy v1 primitive rule parameters remain readable`() {
        val legacy = """
            {
              "irVersion":1,
              "languageVersion":"m5",
              "policySetId":"legacy",
              "functions":[],
              "policies":[
                {
                  "id":"p",
                  "rules":[
                    {
                      "id":"r",
                      "message":"m",
                      "expression":{"op":"literal","value":{"type":"BOOLEAN","value":true}},
                      "params":{"integer":4,"decimal":0.1,"text":"hello","flag":true}
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = CanonicalPolicyJson.decode(legacy.toByteArray(Charsets.UTF_8))
        val params = decoded.policySet.policies.single().rules.single().params

        assertEquals(ParamValue.DoubleV(4.0), params.getValue("integer"))
        assertEquals(ParamValue.DoubleV(0.1), params.getValue("decimal"))
        assertEquals(ParamValue.StringV("hello"), params.getValue("text"))
        assertEquals(ParamValue.BooleanV(true), params.getValue("flag"))
    }

    @Test
    fun `multiline metadata cannot produce a bundle and typed verification refuses it`() {
        val document = CanonicalPolicyJson.decode(resource("/ir/legacy-v1-source-ref.json"))
        val bundle = PolicyBundle(document, metadata = mapOf("description" to "first line\nsecond line"))

        assertFailsWith<IllegalArgumentException> { bundle.pack() }
        assertFailsWith<BundleRefusal> { BundleVerifier.verify(bundle) }
    }

    @Test
    fun `packed admission rejects IR bytes with ignored noncanonical fields`() {
        val valid = PolicyBundle(CanonicalPolicyJson.decode(resource("/ir/legacy-v1-source-ref.json"))).pack()
        val entries = unpack(valid).toMutableMap()
        val originalIr = entries.getValue("policy.ir.json").decodeToString()
        entries["policy.ir.json"] = originalIr
            .replaceFirst("\"irVersion\":1", "\"irVersion\":1,\"unrecognized\":true")
            .toByteArray(Charsets.UTF_8)

        assertFailsWith<BundleRefusal> {
            BundleVerifier.verifyPacked(pack(entries))
        }
    }

    @Test
    fun `packed admission rejects malformed manifests source maps utf8 limits and truncation`() {
        val valid = PolicyBundle(CanonicalPolicyJson.decode(resource("/ir/legacy-v1-source-ref.json"))).pack()
        val originalEntries = unpack(valid)

        val manifestChanged = originalEntries.toMutableMap().also { entries ->
            entries["manifest.json"] = entries.getValue("manifest.json").decodeToString()
                .replace("\"bundleVersion\":\"1\"", "\"bundleVersion\":\"2\"")
                .toByteArray(Charsets.UTF_8)
        }
        assertFailsWith<BundleRefusal> { BundleVerifier.verifyPacked(pack(manifestChanged)) }

        val sourceMapChanged = originalEntries.toMutableMap().also { entries ->
            entries["policy-source-map.txt"] = "rule=forged.kt:1:1-1:10:rule".toByteArray(Charsets.UTF_8)
        }
        assertFailsWith<BundleRefusal> { BundleVerifier.verifyPacked(pack(sourceMapChanged)) }

        val invalidUtf8 = originalEntries.toMutableMap().also { entries ->
            entries["metadata.txt"] = byteArrayOf(0xC3.toByte())
        }
        assertFailsWith<BundleRefusal> { BundleVerifier.verifyPacked(pack(invalidUtf8)) }

        assertFailsWith<BundleRefusal> {
            BundleVerifier.verifyPacked(
                valid,
                BundleAdmissionLimits(maxPackedBytes = valid.size - 1, maxEntryBytes = valid.size - 1),
            )
        }
        assertFailsWith<BundleRefusal> {
            BundleVerifier.verifyPacked(
                valid,
                BundleAdmissionLimits(maxPackedBytes = valid.size, maxEntryBytes = 1),
            )
        }
        assertFailsWith<BundleRefusal> {
            BundleVerifier.verifyPacked(
                valid,
                BundleAdmissionLimits(
                    maxPackedBytes = valid.size,
                    maxEntryBytes = valid.size,
                    ir = IrAdmissionLimits(maxEncodedBytes = 1),
                ),
            )
        }

        valid.indices.forEach { end ->
            assertFailsWith<BundleRefusal>("truncated pack prefix length=$end") {
                BundleVerifier.verifyPacked(valid.copyOf(end))
            }
        }
        assertTrue(BundleVerifier.verifyPacked(valid).semanticDigest.isNotBlank())
    }

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing test resource $path" }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun unpack(bytes: ByteArray): Map<String, ByteArray> {
        var position = 4
        val entries = sortedMapOf<String, ByteArray>()
        repeat(4) {
            val nameLength = ByteBuffer.wrap(bytes, position, Int.SIZE_BYTES).int
            position += Int.SIZE_BYTES
            val name = bytes.copyOfRange(position, position + nameLength).decodeToString()
            position += nameLength
            val entryLength = ByteBuffer.wrap(bytes, position, Long.SIZE_BYTES).long.toInt()
            position += Long.SIZE_BYTES
            entries[name] = bytes.copyOfRange(position, position + entryLength)
            position += entryLength
        }
        check(position == bytes.size)
        return entries
    }

    private fun pack(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("PKB1".toByteArray(Charsets.UTF_8))
        entries.toSortedMap().forEach { (name, value) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(nameBytes.size).array())
            output.write(nameBytes)
            output.write(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value.size.toLong()).array())
            output.write(value)
        }
        return output.toByteArray()
    }
}
