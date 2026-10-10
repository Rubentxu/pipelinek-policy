package com.pipelinek.policy.ir

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleSeverity
import com.pipelinek.policy.kernel.value.ValueNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * H1.1 — a declared severity must survive the canonical IR round trip.
 *
 * The defect this pins: `Rule.severity` existed in the kernel and was read by
 * `Evaluator` (so an in-memory evaluation carried the author's severity) and by
 * `PolicyDiff` (so an in-memory comparison reported `SEVERITY_CHANGED`), but
 * `CanonicalPolicyJsonWriter` never emitted it and the decoder never read it.
 *
 * The consequence was a report that lied about its own severity. A rule declared
 * `CRITICAL` evaluated to `CRITICAL` in the author's process; the moment the same
 * rule was packed into a bundle, decoded by the plugin step, and evaluated, the
 * severity had silently become "not declared". Nothing reported the loss. The
 * encoded bytes for two rules differing only in severity were identical.
 *
 * This is architectural law 9 read as a transport property: the severity is
 * author-declared data, not something derived at evaluation time, so losing it
 * in transit is a silent coercion.
 *
 * The tests below are deliberately asymmetric. A test that asserted
 * "severity is preserved when severity is CRITICAL" could pass against an
 * encoder that simply ignored severity on both sides of the round trip, because
 * both would be null-or-crash-free. The absence case and the bytes-identity case
 * are what make this falsifiable.
 */
class PolicyIrSeverityRoundTripTest {

    @Test
    fun `every normative severity survives the canonical IR round trip`() {
        RuleSeverity.entries.forEach { severity ->
            val document = documentWithSeverity(severity)

            val decoded = CanonicalPolicyJson.decode(CanonicalPolicyJson.encode(document))

            assertEquals(
                severity,
                decoded.policySet.policies.single().rules.single().severity,
                "severity $severity must survive encode/decode; got " +
                    "${decoded.policySet.policies.single().rules.single().severity}",
            )
            assertEquals(document, decoded, "full document equality for severity $severity")
        }
    }

    /**
     * The absence of a declared severity is itself meaningful and must not be
     * turned into a default. `PolicyDiff` reports `SEVERITY_CHANGED` only when
     * BOTH sides declare one; if the codec invented an `INFO` here, every
     * undeclared rule would suddenly differ from every declared one.
     */
    @Test
    fun `an undeclared severity stays undeclared and is never defaulted`() {
        val document = documentWithSeverity(null)

        val decoded = CanonicalPolicyJson.decode(CanonicalPolicyJson.encode(document))

        assertNull(
            decoded.policySet.policies.single().rules.single().severity,
            "an absent severity must stay absent, not become INFO",
        )
        assertEquals(document, decoded)
    }

    /**
     * The falsifying assertion: two documents that differ ONLY in severity must
     * not produce identical bytes. Before the fix this test fails with identical
     * arrays, which is the precise shape of the bug — the information was not
     * merely mis-ordered, it was absent from the encoding entirely.
     */
    @Test
    fun `two rules differing only in severity do not encode to identical bytes`() {
        val critical = CanonicalPolicyJson.encode(documentWithSeverity(RuleSeverity.CRITICAL))
        val info = CanonicalPolicyJson.encode(documentWithSeverity(RuleSeverity.INFO))

        assertFalse(
            critical.contentEquals(info),
            "severity is not represented in the canonical encoding, so a CRITICAL " +
                "rule and an INFO rule are the same bytes",
        )
    }

    @Test
    fun `severity appears in the encoded bytes as its normative name`() {
        val encoded = CanonicalPolicyJson.encode(documentWithSeverity(RuleSeverity.CRITICAL)).decodeToString()

        assertTrue(encoded.contains("\"severity\":\"CRITICAL\""), encoded)
    }

    /**
     * Severity is a closed vocabulary (INFO | WARNING | ERROR | CRITICAL). An
     * encoded document carrying anything else must be refused at the boundary
     * rather than silently degraded to null, which is exactly the failure mode
     * being fixed one layer up.
     */
    @Test
    fun `an out-of-vocabulary severity is refused instead of silently dropped`() {
        val encoded = CanonicalPolicyJson.encode(documentWithSeverity(RuleSeverity.ERROR))
            .decodeToString()
            .replace("\"severity\":\"ERROR\"", "\"severity\":\"FATAL\"")

        val failure = assertFailsWith<IrRefusal.CorruptEncoding> {
            CanonicalPolicyJson.decode(encoded.encodeToByteArray())
        }
        assertTrue(failure.message!!.contains("FATAL"), failure.message!!)
    }

    private fun documentWithSeverity(severity: RuleSeverity?) = PolicyIrDocument(
        policySet = PolicySet(
            "set",
            listOf(
                Policy(
                    "policy",
                    listOf(
                        Rule(
                            id = "rule",
                            message = "must be enabled",
                            expression = Expression.Literal(ValueNode.BooleanValue(true)),
                            severity = severity,
                        ),
                    ),
                ),
            ),
        ),
    )
}
