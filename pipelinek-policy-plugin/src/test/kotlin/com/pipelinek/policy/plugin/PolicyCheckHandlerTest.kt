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
 * M6 surgical tests: codec round-trips (REQ-02), handler evaluation
 * (REQ-01/03), enforcement carriers, and refusal fail-closed behaviour.
 */
class PolicyCheckHandlerTest {

    /** `spec.replicas <= 3` — passes for 2, violates for 5. */
    private fun replicasRule(): Rule = Rule(
        id = "replicas-limit",
        message = "replicas must be <= 3",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
            op = Expression.Operator.LTE,
            right = Literal(ValueNode.NumberValue(3)),
        ),
    )

    private fun neverApplies(): Rule = Rule(
        id = "never-applies",
        message = "guard is false",
        expression = Literal(ValueNode.BooleanValue(true)),
        appliesWhen = Literal(ValueNode.BooleanValue(false)),
    )

    private fun packedBundle(vararg rules: Rule): ByteArray {
        val set = PolicySet(
            id = "test-set",
            policies = listOf(Policy(id = "p1", rules = rules.toList())),
        )
        return PolicyBundle(
            document = PolicyIrDocument(policySet = set, sourceRefs = emptyMap()),
        ).pack()
    }

    private fun inputForResource(json: String, bundle: ByteArray): PolicyCheckInput {
        val b64 = Base64.getEncoder()
        return PolicyCheckInput(
            resourceBase64 = b64.encodeToString(json.toByteArray()),
            resourceFormat = "JSON",
            packedBundleBase64 = b64.encodeToString(bundle),
        )
    }

    @Test
    fun `codec round trip is identity for input`() {
        val input = inputForResource("""{"spec":{"replicas":2}}""", ByteArray(8) { it.toByte() })
        val encoded = PolicyCheckInputCodec.encode(input)
        assertEquals(input, PolicyCheckInputCodec.decode(encoded))
    }

    @Test
    fun `codec round trip is identity for output`() {
        val output = PolicyCheckOutput.violated(
            2,
            "digest",
            "set",
            listOf(RuleSummary("r", "violated")),
        )
        val encoded = PolicyCheckOutputCodec.encode(output)
        assertEquals(output, PolicyCheckOutputCodec.decode(encoded))
    }

    @Test
    fun `passing resource yields PASSED with report digest`() {
        val out = PolicyCheckStepDefinition.evaluate(
            inputForResource("""{"spec":{"replicas":2}}""", packedBundle(replicasRule())),
        )
        assertEquals(PolicyCheckVerdict.PASSED, out.verdict)
        assertEquals(0, out.violationsCount)
        assertTrue(out.reportDigest!!.length == 64)
        assertEquals("test-set", out.policySetId)
        assertEquals(listOf(RuleSummary("replicas-limit", "passed")), out.ruleSummaries)
        assertEquals(StepOutcome.Success, out.outcome)
    }

    @Test
    fun `violating resource is enforced via outcome failure carrier`() {
        val out = PolicyCheckStepDefinition.evaluate(
            inputForResource("""{"spec":{"replicas":5}}""", packedBundle(replicasRule())),
        )
        assertEquals(PolicyCheckVerdict.VIOLATED, out.verdict)
        assertEquals(1, out.violationsCount)
        val failure = out.outcome as StepOutcome.Failure
        assertTrue("VIOLATED" in failure.failure.message)
        assertTrue(out.reportDigest!! in failure.failure.message)
    }

    @Test
    fun `appliesWhen false rule is NotApplicable and not a violation`() {
        val out = PolicyCheckStepDefinition.evaluate(
            inputForResource(
                """{"spec":{"replicas":5}}""",
                packedBundle(replicasRule(), neverApplies()),
            ),
        )
        assertEquals(1, out.violationsCount)
        assertEquals(
            listOf(
                RuleSummary("never-applies", "not-applicable"),
                RuleSummary("replicas-limit", "violated"),
            ),
            out.ruleSummaries,
        )
    }

    @Test
    fun `malformed bundle bytes are refused fail-closed`() {
        val bad = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5))
        val out = PolicyCheckStepDefinition.evaluate(
            PolicyCheckInput(
                resourceBase64 = Base64.getEncoder().encodeToString("{}".toByteArray()),
                resourceFormat = "JSON",
                packedBundleBase64 = bad,
            ),
        )
        assertEquals(PolicyCheckVerdict.REFUSED, out.verdict)
        val failure = out.outcome as StepOutcome.Failure
        assertTrue("REFUSED" in failure.failure.message)
    }

    @Test
    fun `unknown format is refused`() {
        val out = PolicyCheckStepDefinition.evaluate(
            PolicyCheckInput(
                resourceBase64 = "e30=",
                resourceFormat = "XML",
                packedBundleBase64 = Base64.getEncoder().encodeToString(ByteArray(4)),
            ),
        )
        assertEquals(PolicyCheckVerdict.REFUSED, out.verdict)
    }
}
