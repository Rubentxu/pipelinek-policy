package com.pipelinek.policy.kernel.policy

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §"Policy/Rule and pure evaluator" — PolicySet / Policy / Rule / RuleEvaluation.
 *
 * The IR is closed under structural equality so the evaluator and report can
 * fingerprint it deterministically.
 */
class PolicySetTest {

    private val replicasRule = Rule(
        id = "min-replicas",
        message = "spec.replicas must be >= 3",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
            op = Operator.GTE,
            right = Literal(ValueNode.NumberValue(3)),
        ),
    )

    private val policy = Policy(id = "k8s-baseline", rules = listOf(replicasRule))

    @Test
    fun `PolicySet wraps a list of policies with a stable identity`() {
        val ps = PolicySet(id = "baseline", policies = listOf(policy))
        assertEquals("baseline", ps.id)
        assertEquals(listOf(policy), ps.policies)
    }

    @Test
    fun `PolicySet equality is structural over its policies`() {
        val a = PolicySet("baseline", listOf(policy))
        val b = PolicySet("baseline", listOf(policy))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Policy equality is structural over its rules`() {
        val a = Policy("k8s-baseline", listOf(replicasRule))
        val b = Policy("k8s-baseline", listOf(replicasRule))
        assertEquals(a, b)
    }

    @Test
    fun `PolicySets with different IDs are not equal`() {
        val a = PolicySet("a", listOf(policy))
        val b = PolicySet("b", listOf(policy))
        assertNotEquals(a, b)
    }

    @Test
    fun `Rule carries id message and expression`() {
        assertEquals("min-replicas", replicasRule.id)
        assertEquals("spec.replicas must be >= 3", replicasRule.message)
        assertTrue(replicasRule.expression is Expression.Comparison)
    }

    @Test
    fun `RuleEvaluation Passed has empty violations`() {
        val passed = RuleEvaluation.Passed
        assertEquals(emptyList(), passed.violations)
    }

    @Test
    fun `RuleEvaluation Violated carries at least one violation`() {
        val v = PolicyViolation(
            code = ViolationCode.MISSING_REQUIRED_VALUE,
            location = DocumentPath.ROOT.child("spec").child("replicas"),
            message = "spec.replicas is required",
        )
        val ev = RuleEvaluation.Violated(listOf(v))
        assertEquals(1, ev.violations.size)
        assertEquals(v, ev.violations[0])
    }

    @Test
    fun `RuleEvaluation NotApplicable is a singleton`() {
        assertEquals(RuleEvaluation.NotApplicable, RuleEvaluation.NotApplicable)
    }

    @Test
    fun `RuleEvaluation Error carries a violation with TYPE_MISMATCH code`() {
        val err = PolicyViolation(
            code = ViolationCode.TYPE_MISMATCH,
            location = DocumentPath.ROOT.child("spec").child("replicas"),
            message = "expected Number, got Text",
            expected = "Number",
            actual = "Text",
        )
        val ev = RuleEvaluation.Error(err)
        assertEquals(1, ev.violations.size)
        assertEquals(ViolationCode.TYPE_MISMATCH, ev.violations[0].code)
    }

    @Test
    fun `ViolationCode enum covers the UAT-required cases`() {
        // M3 ADDS ViolationCode.COLLECTION_PREDICATE_FAILED (ordinal=3, back-compat).
        val codes = ViolationCode.values().toSet()
        assertTrue(ViolationCode.MISSING_REQUIRED_VALUE in codes)
        assertTrue(ViolationCode.TYPE_MISMATCH in codes)
        assertTrue(ViolationCode.COMPARISON_FAILED in codes)
        assertTrue(ViolationCode.COLLECTION_PREDICATE_FAILED in codes)
        // Back-compat: the first three ordinals MUST stay 0, 1, 2 — the canonical
        // PolicyReport digest and M1 tests rely on them.
        assertEquals(0, ViolationCode.MISSING_REQUIRED_VALUE.ordinal)
        assertEquals(1, ViolationCode.TYPE_MISMATCH.ordinal)
        assertEquals(2, ViolationCode.COMPARISON_FAILED.ordinal)
    }

    @Test
    fun `PolicyViolation equality carries expected and actual fields`() {
        val v1 = PolicyViolation(
            ViolationCode.COMPARISON_FAILED,
            DocumentPath.ROOT.child("spec").child("replicas"),
            "expected >= 3",
            expected = ">=3",
            actual = "1",
        )
        val v2 = PolicyViolation(
            ViolationCode.COMPARISON_FAILED,
            DocumentPath.ROOT.child("spec").child("replicas"),
            "expected >= 3",
            expected = ">=3",
            actual = "1",
        )
        assertEquals(v1, v2)
        assertEquals(v1.hashCode(), v2.hashCode())
    }

    // Local aliases removed — Kotlin classes cannot be assigned as values.
}
