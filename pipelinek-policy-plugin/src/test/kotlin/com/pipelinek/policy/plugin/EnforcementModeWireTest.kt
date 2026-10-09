package com.pipelinek.policy.plugin

import com.pipelinek.policy.kernel.governance.EnforcementMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B4-T8 wire certificate · `EnforcementMode` moved from this module to the
 * core (`kernel/governance`) as a PLAIN enum, because core permits only the
 * Kotlin stdlib and `@Serializable` is not available there.
 *
 * Asserting enum VALUES would be worthless: the constants did not change, so
 * such a test would pass under a rename that silently moves the wire format.
 * This certificate therefore pins the exact BYTES the frozen M6/M7 payloads
 * have, captured BEFORE the move.
 */
class EnforcementModeWireTest {

    private val input = PolicyCheckInput(
        resourceBase64 = "eyJmb28iOiJiYXIifQ==",
        resourceFormat = "JSON",
        packedBundleBase64 = "UEtCMQ==",
        enforcement = EnforcementMode.SHADOW,
    )

    private val output = PolicyCheckOutput.violated(
        violationsCount = 2,
        reportDigest = "digest-1",
        policySetId = "p1",
        ruleSummaries = listOf(RuleSummary("r1", "violated")),
        enforcement = EnforcementMode.SHADOW,
    )

    @Test
    fun `the moved enum produces byte-identical input and output payloads`() {
        assertEquals(
            """{"resourceBase64":"eyJmb28iOiJiYXIifQ==","resourceFormat":"JSON",""" +
                """"packedBundleBase64":"UEtCMQ==","enforcement":"SHADOW"}""",
            PolicyCheckInputCodec.encode(input).value,
        )
        assertEquals(
            """{"verdict":"VIOLATED","violationsCount":2,"reportDigest":"digest-1",""" +
                """"policySetId":"p1","ruleSummaries":[{"ruleId":"r1","outcome":"violated",""" +
                """"policyId":null}],"refusalReason":null,"enforcement":"SHADOW","wouldDeny":true}""",
            PolicyCheckOutputCodec.encode(output).value,
        )
    }

    @Test
    fun `the core enum is the one the wire carries`() {
        assertEquals("ENFORCED", EnforcementMode.ENFORCED.name)
        assertEquals("SHADOW", EnforcementMode.SHADOW.name)
        assertEquals(
            EnforcementMode.SHADOW,
            PolicyCheckOutputCodec.decode(PolicyCheckOutputCodec.encode(output)).enforcement,
        )
    }
}
