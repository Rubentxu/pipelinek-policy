package com.pipelinek.policy.plugin

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.ir.PolicyIrDocument
import com.pipelinek.policy.kernel.governance.EnforcementMode
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B0.5 falsification · the declared precedence is `REFUSED > ERRORED >
 * VIOLATED > PASSED`, and the implementation must follow it.
 *
 * Before the fix, `PolicyCheckStepDefinition.evaluate` tested
 * `violations > 0` before `errors > 0`, so a report carrying one rule Error
 * plus one rule Violated produced `VIOLATED` — and under SHADOW that
 * `VIOLATED` mapped to `StepOutcome.Success`. An operational failure was
 * disguised as compliance.
 *
 * The whole matrix below is the falsification: each case states the verdict
 * that MUST come out. Every one of them is observable only through the public
 * `evaluate` entry point.
 */
class EnforcementPrecedenceTest {

    /** Text field under a numeric rule: the evaluator yields Error, never Violated. */
    private fun errorRule(id: String = "team-numeric"): Rule = Rule(
        id = id,
        message = "metadata.team must be a number <= 3",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("metadata").child("team"), ValueNode.Type.NUMBER),
            op = Expression.Operator.LTE,
            right = Literal(ValueNode.NumberValue(3)),
        ),
    )

    private fun passingRule(id: String = "team-platform"): Rule = Rule(
        id = id,
        message = "metadata.team must be platform",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("metadata").child("team"), ValueNode.Type.TEXT),
            op = Expression.Operator.TEXT_EQUALS,
            right = Literal(ValueNode.TextValue("platform")),
        ),
    )

    private fun violatedRule(id: String = "team-evil"): Rule = Rule(
        id = id,
        message = "metadata.team must be platform",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("metadata").child("team"), ValueNode.Type.TEXT),
            op = Expression.Operator.TEXT_EQUALS,
            right = Literal(ValueNode.TextValue("platform")),
        ),
    )

    private val erroringResource = """{"metadata":{"team":"many"}}"""
    private val passingResource = """{"metadata":{"team":"platform"}}"""

    private fun run(
        resource: String,
        rules: List<Rule>,
        enforcement: EnforcementMode = EnforcementMode.ENFORCED,
    ): PolicyCheckOutput = PolicyCheckStepDefinition.evaluate(
        PolicyCheckInput(
            resourceBase64 = Base64.getEncoder().encodeToString(resource.toByteArray()),
            resourceFormat = "JSON",
            packedBundleBase64 = Base64.getEncoder().encodeToString(
                PolicyBundle(
                    PolicyIrDocument(
                        policySet = PolicySet("b05-set", listOf(Policy("p1", rules))),
                    ),
                ).pack(),
            ),
            enforcement = enforcement,
        ),
    )

    // --- case 1: Passed + Error is ERRORED, never Success ---

    @Test
    fun `passed plus error is ERRORED and never Success`() {
        val out = run(passingResource, listOf(errorRule(), passingRule()))

        assertEquals(PolicyCheckVerdict.ERRORED, out.verdict)
        assertIs<StepOutcome.Failure>(out.outcome)
    }

    // --- case 2: Violated + Error is ERRORED, never Success ---

    @Test
    fun `violated plus error is ERRORED and never Success`() {
        val out = run(erroringResource, listOf(errorRule(), violatedRule()))

        assertEquals(PolicyCheckVerdict.ERRORED, out.verdict)
        assertIs<StepOutcome.Failure>(out.outcome)
    }

    // --- case 3: Passed + Violated + Error is ERRORED ---

    @Test
    fun `passed plus violated plus error is ERRORED`() {
        val out = run(
            """{"metadata":{"team":"many","owner":"x"}}""",
            listOf(errorRule(), violatedRule(), passingRule()),
        )

        assertEquals(PolicyCheckVerdict.ERRORED, out.verdict)
        assertIs<StepOutcome.Failure>(out.outcome)
    }

    // --- case 4: SHADOW + Error is Failure ---

    @Test
    fun `shadow plus error fails the step`() {
        val out = run(erroringResource, listOf(errorRule()), EnforcementMode.SHADOW)

        assertEquals(PolicyCheckVerdict.ERRORED, out.verdict)
        assertIs<StepOutcome.Failure>(out.outcome)
    }

    // --- case 5: SHADOW + Violated, no Error, is Success ---

    @Test
    fun `shadow plus violation without error succeeds`() {
        val out = run(erroringResource, listOf(violatedRule()), EnforcementMode.SHADOW)

        assertEquals(PolicyCheckVerdict.VIOLATED, out.verdict)
        assertTrue(out.wouldDeny)
        assertEquals(StepOutcome.Success, out.outcome)
    }

    // --- case 6: refusal under SHADOW is Failure ---

    @Test
    fun `refusal under shadow fails`() {
        val out = PolicyCheckStepDefinition.evaluate(
            PolicyCheckInput(
                resourceBase64 = Base64.getEncoder().encodeToString("{not json".toByteArray()),
                resourceFormat = "JSON",
                packedBundleBase64 = Base64.getEncoder().encodeToString("garbage".toByteArray()),
                enforcement = EnforcementMode.SHADOW,
            ),
        )

        assertEquals(PolicyCheckVerdict.REFUSED, out.verdict)
        assertIs<StepOutcome.Failure>(out.outcome)
    }

    // --- evidence survives an ERRORED verdict ---

    @Test
    fun `an ERRORED verdict still carries the violations as evidence`() {
        val out = run(erroringResource, listOf(errorRule(), violatedRule()))

        assertEquals(PolicyCheckVerdict.ERRORED, out.verdict)
        assertEquals(2, out.ruleSummaries.size, "every rule must stay visible")
        assertTrue(out.ruleSummaries.any { it.outcome == "error" })
        assertTrue(out.ruleSummaries.any { it.outcome == "violated" }, "violations remain evidence")
        assertEquals(1, out.violationsCount, "the violation count is evidence too, not erased by ERRORED")
        assertTrue(
            (out.reportDigest ?: "").isNotBlank(),
            "the report digest must survive so the failures can be traced",
        )
    }

    // --- ENFORCED violation still dominates when there is no error ---

    @Test
    fun `enforced violation without error still fails`() {
        val out = run(erroringResource, listOf(violatedRule()))

        assertEquals(PolicyCheckVerdict.VIOLATED, out.verdict)
        assertEquals(1, out.violationsCount)
        val failure = assertIs<StepOutcome.Failure>(out.outcome)
        assertTrue(failure.failure.message.contains("VIOLATED"))
    }
}
