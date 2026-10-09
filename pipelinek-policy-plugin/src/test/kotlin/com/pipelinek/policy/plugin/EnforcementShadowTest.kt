package com.pipelinek.policy.plugin

import com.pipelinek.policy.kernel.governance.EnforcementMode
import dev.rubentxu.pipeline.v2.events.registry.PayloadDecode
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M7 REQ-M7-04 — EnforcementMode SHADOW.
 *
 * Scenarios 04a-c plus the M6 back-compat decode (06b). The mutation
 * contract (04c): a mutation that makes SHADOW fail the outcome is detected
 * by 04a; a mutation that flips wouldDeny in ENFORCED mode is detected by 04b.
 */
class EnforcementShadowTest {

    private fun violatedOutput(mode: EnforcementMode): PolicyCheckOutput =
        PolicyCheckOutput.violated(
            violationsCount = 2,
            reportDigest = "digest-1",
            policySetId = "p1",
            ruleSummaries = listOf(RuleSummary("r1", "violated")),
            enforcement = mode,
        )

    // --- 04a: shadow keeps the verdict, never fails the outcome ---

    @Test
    fun `04a shadow violation keeps VIOLATED verdict with Success outcome and wouldDeny`() {
        val out = violatedOutput(EnforcementMode.SHADOW)

        assertEquals(PolicyCheckVerdict.VIOLATED, out.verdict)
        assertEquals(2, out.violationsCount)
        assertTrue(out.wouldDeny, "shadow violation must set wouldDeny=true")
        assertIs<dev.rubentxu.pipeline.v2.domain.StepOutcome.Success>(out.outcome)
    }

    // --- 04b: enforced is bit-identical M6 behavior ---

    @Test
    fun `04b enforced violation fails the outcome exactly like M6`() {
        val out = violatedOutput(EnforcementMode.ENFORCED)

        assertEquals(PolicyCheckVerdict.VIOLATED, out.verdict)
        assertFalse(out.wouldDeny)
        val failed = assertIs<dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure>(out.outcome)
        assertEquals(
            "policy check VIOLATED: 2 violation(s), report digest digest-1",
            failed.failure.message,
        )
    }

    // --- 04c: mutation contract — REFUSED still fails in SHADOW (fail-closed) ---

    @Test
    fun `04c refused still fails even in shadow mode`() {
        val out = PolicyCheckOutput.refused("decode refused").copy(enforcement = EnforcementMode.SHADOW)

        // Operational failures are never shadowed: fail-closed (M6 REQ-01b).
        assertIs<dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure>(out.outcome)
    }

    // --- 06b: M6 wire input without the enforcement field decodes to ENFORCED ---

    @Test
    fun `06b m6 payload without enforcement decodes to ENFORCED`() {
        val json = Json { encodeDefaults = false }
        val m6Wire = """{"resourceBase64":"aGk=","resourceFormat":"YAML","packedBundleBase64":"aGk="}"""

        val input = json.decodeFromString(PolicyCheckInput.serializer(), m6Wire)

        assertEquals(EnforcementMode.ENFORCED, input.enforcement)
    }

    // --- event payload v1 back-compat: 5-field M6 payloads still decode ---

    @Test
    fun `event payload m6 five-field form decodes with ENFORCED and wouldDeny false`() {
        val sep = "\u001F" // unit separator, same as the codec private SEP
        val m6Payload = "v1${sep}PASSED${sep}0${sep}-${sep}-"

        val decoded = PolicyCheckReportedCodec.decode(m6Payload, schemaVersion = 1)

        val payload = assertIs<PayloadDecode.Decoded<PolicyCheckReported>>(decoded).payload
        assertEquals(EnforcementMode.ENFORCED, payload.enforcement)
        assertFalse(payload.wouldDeny)
    }

    @Test
    fun `event payload v1 seven-field form round-trips`() {
        val payload = PolicyCheckReported(
            verdict = "VIOLATED",
            violationsCount = 3,
            reportDigest = "d",
            policySetId = "p",
            enforcement = EnforcementMode.SHADOW,
            wouldDeny = true,
        )
        val encoded = PolicyCheckReportedCodec.encode(payload)
        val decoded = PolicyCheckReportedCodec.decode(encoded, schemaVersion = 1)

        val out = assertIs<PayloadDecode.Decoded<PolicyCheckReported>>(decoded).payload
        assertEquals(payload, out)
    }
}
