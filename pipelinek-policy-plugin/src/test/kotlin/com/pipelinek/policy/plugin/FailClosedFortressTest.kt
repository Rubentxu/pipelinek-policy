package com.pipelinek.policy.plugin

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.ir.PolicyIrDocument
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BLOQUE B0 (B0.1/B0.3) · Regression fortress: Error must NEVER produce
 * PASSED, in ACTIVE or SHADOW; admission refusals dominate; the evaluator's
 * semantic state survives to StepOutcome.
 *
 * These tests are the falsification gate for the fail-closed fix: they are
 * RED against the pre-B0 code (an `Error` rule with zero violations was
 * reported as PASSED).
 */
class FailClosedFortressTest {

    /** Text field under a numeric rule → evaluator yields Error (TYPE_MISMATCH), not Violated. */
    private fun numericRuleOnTextField(): Rule = Rule(
        id = "replicas-numeric",
        message = "replicas must be a number <= 3",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
            op = Expression.Operator.LTE,
            right = Literal(ValueNode.NumberValue(3)),
        ),
    )

    private fun textEqualsRule(id: String, expected: String): Rule = Rule(
        id = id,
        message = "$id expects $expected",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("team"), ValueNode.Type.TEXT),
            op = Expression.Operator.TEXT_EQUALS,
            right = Literal(ValueNode.TextValue(expected)),
        ),
    )

    private fun packedBundle(vararg rules: Rule): ByteArray {
        val set = PolicySet(id = "b0-set", policies = listOf(Policy(id = "p1", rules = rules.toList())))
        return PolicyBundle(document = PolicyIrDocument(policySet = set, sourceRefs = emptyMap())).pack()
    }

    private fun input(json: String, bundle: ByteArray, enforcement: EnforcementMode = EnforcementMode.ENFORCED): PolicyCheckInput {
        val b64 = Base64.getEncoder()
        return PolicyCheckInput(
            resourceBase64 = b64.encodeToString(json.toByteArray()),
            resourceFormat = "JSON",
            packedBundleBase64 = b64.encodeToString(bundle),
            enforcement = enforcement,
        )
    }

    // --- B0.1: Error never becomes PASSED ---

    @Test
    fun `B0-1a text field under numeric rule fails closed, not PASSED`() {
        val out = PolicyCheckStepDefinition.evaluate(
            input("""{"spec":{"replicas":"many"}}""", packedBundle(numericRuleOnTextField())),
        )
        assertTrue(
            out.verdict != PolicyCheckVerdict.PASSED,
            "an Error evaluation must never be reported as PASSED (was ${out.verdict})",
        )
        assertTrue(out.outcome is StepOutcome.Failure, "fail-closed: Error must fail the step under ENFORCED")
        val summary = out.ruleSummaries.single()
        assertEquals("replicas-numeric", summary.ruleId)
        assertEquals("error", summary.outcome, "the evaluator's semantic state must survive")
    }

    @Test
    fun `B0-1b Error with zero violations in SHADOW is not silent success`() {
        val out = PolicyCheckStepDefinition.evaluate(
            input(
                """{"spec":{"replicas":"many"}}""",
                packedBundle(numericRuleOnTextField()),
                enforcement = EnforcementMode.SHADOW,
            ),
        )
        // Shadow preserves would-deny as evidence and never blocks business
        // execution, but an OPERATIONAL error is not a business verdict: the
        // pipeline must learn the check did not actually evaluate.
        assertTrue(
            out.verdict != PolicyCheckVerdict.PASSED,
            "SHADOW must not convert an operational error into silent PASSED",
        )
    }

    @Test
    fun `B0-1c mixed Passed+Error+Violated keeps every state distinguishable`() {
        val out = PolicyCheckStepDefinition.evaluate(
            input(
                """{"spec":{"replicas":"many"},"team":"platform"}""",
                packedBundle(
                    numericRuleOnTextField(), // error
                    textEqualsRule("team-is-platform", "platform"), // passed
                    textEqualsRule("team-is-evil", "evil"), // violated
                ),
            ),
        )
        assertEquals(PolicyCheckVerdict.VIOLATED, out.verdict, "violations still dominate the verdict")
        assertEquals(1, out.violationsCount)
        assertEquals(3, out.ruleSummaries.size)
        assertTrue(out.ruleSummaries.any { it.outcome == "passed" })
        assertTrue(out.ruleSummaries.any { it.outcome == "error" })
        assertTrue(out.ruleSummaries.any { it.outcome == "violated" })
    }

    // --- B0.3: the seven fortress scenarios ---

    @Test
    fun `B0-3a legitimate NotApplicable keeps its semantic state`() {
        val neverApplies = Rule(
            id = "never-applies",
            message = "guard is false",
            expression = Literal(ValueNode.BooleanValue(true)),
            appliesWhen = Literal(ValueNode.BooleanValue(false)),
        )
        val out = PolicyCheckStepDefinition.evaluate(
            input("""{"team":"x"}""", packedBundle(neverApplies)),
        )
        assertEquals(PolicyCheckVerdict.PASSED, out.verdict)
        assertEquals(listOf(RuleSummary("never-applies", "not-applicable")), out.ruleSummaries)
        assertEquals(StepOutcome.Success, out.outcome)
    }

    @Test
    fun `B0-3b corrupt bundle fails even under SHADOW enforcement`() {
        val garbage = "not-a-bundle".toByteArray()
        val out = PolicyCheckStepDefinition.evaluate(
            input("""{"team":"x"}""", garbage, enforcement = EnforcementMode.SHADOW),
        )
        assertEquals(PolicyCheckVerdict.REFUSED, out.verdict, "admission refusal dominates")
        assertTrue(out.outcome is StepOutcome.Failure, "REFUSED fails closed regardless of rollout mode")
    }

    @Test
    fun `B0-3c violated in SHADOW is would-deny evidence, outcome success`() {
        val out = PolicyCheckStepDefinition.evaluate(
            input(
                """{"team":"evil"}""",
                packedBundle(textEqualsRule("team-is-platform", "platform")),
                enforcement = EnforcementMode.SHADOW,
            ),
        )
        assertEquals(PolicyCheckVerdict.VIOLATED, out.verdict)
        assertTrue(out.wouldDeny)
        assertEquals(StepOutcome.Success, out.outcome, "shadow reports but does not block")
    }

    @Test
    fun `B0-3d decode refusal propagates as REFUSED with failure outcome`() {
        val out = PolicyCheckStepDefinition.evaluate(
            PolicyCheckInput(
                resourceBase64 = Base64.getEncoder().encodeToString("{not json".toByteArray()),
                resourceFormat = "JSON",
                packedBundleBase64 = Base64.getEncoder().encodeToString(packedBundle(textEqualsRule("t", "x"))),
            ),
        )
        assertEquals(PolicyCheckVerdict.REFUSED, out.verdict)
        assertTrue(out.outcome is StepOutcome.Failure)
    }

    @Test
    fun `B0-3e violation in ENFORCED fails the step with PLUGIN failure kind`() {
        val out = PolicyCheckStepDefinition.evaluate(
            input("""{"team":"evil"}""", packedBundle(textEqualsRule("team-is-platform", "platform"))),
        )
        assertEquals(PolicyCheckVerdict.VIOLATED, out.verdict)
        val failure = out.outcome as StepOutcome.Failure
        assertTrue(failure.failure.message.contains("VIOLATED"))
    }
}
