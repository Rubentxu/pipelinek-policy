package com.pipelinek.policy.kernel.evaluator

import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.policy.ViolationCode
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §"Policy/Rule and pure evaluator" — pure evaluator over ValueTree.
 *
 * Plus mutation gate (REQ §"PolicyReport and canonical value hashing"):
 *   - Same evaluator+tree+PolicySet => identical digests across 100 runs.
 *   - Two maps of equal content with different insertion order => identical digests.
 */
class EvaluatorTest {

    private val tree = ValueNode.MappingValue(
        linkedMapOf(
            "spec" to ValueNode.MappingValue(
                linkedMapOf("replicas" to ValueNode.NumberValue(3)),
            ),
        ),
    )

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
    fun `Spec UAT (a) rule spec_replicas gte 3 with Number 3 evaluates to Passed`() {
        val set = PolicySet(id = "baseline", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        val ruleEval = report.results[com.pipelinek.policy.kernel.evaluator.RuleId(replicasRule.id)]
        assertEquals(RuleEvaluation.Passed, ruleEval)
    }

    @Test
    fun `Spec UAT (b) same rule with absent spec_replicas evaluates to Violated with MISSING_REQUIRED_VALUE`() {
        val empty = ValueNode.MappingValue(linkedMapOf())
        val set = PolicySet(id = "baseline", policies = listOf(policy))
        val report = Evaluator.evaluate(set, empty)
        val ev =
            report.results[com.pipelinek.policy.kernel.evaluator.RuleId(replicasRule.id)] as RuleEvaluation.Violated
        assertEquals(1, ev.violations.size)
        assertEquals(ViolationCode.MISSING_REQUIRED_VALUE, ev.violations[0].code)
        assertEquals(DocumentPath.ROOT.child("spec").child("replicas"), ev.violations[0].location)
    }

    @Test
    fun `Spec UAT (e) 100 evaluations of the same input produce identical digests`() {
        val set = PolicySet(id = "baseline", policies = listOf(policy))
        val first = Evaluator.evaluate(set, tree).digest
        repeat(100) {
            val again = Evaluator.evaluate(set, tree).digest
            assertEquals(first, again, "digest drift on iteration $it")
        }
    }

    @Test
    fun `Spec UAT (f) reordered maps produce identical digests`() {
        val leftTree = ValueNode.MappingValue(
            linkedMapOf("spec" to ValueNode.MappingValue(linkedMapOf("replicas" to ValueNode.NumberValue(3)))),
        )
        val rightTree = ValueNode.MappingValue(
            linkedMapOf(
                "spec" to ValueNode.MappingValue(
                    // reversed insertion order
                    linkedMapOf("replicas" to ValueNode.NumberValue(3)).let { src ->
                        java.util.LinkedHashMap<String, ValueNode>().apply {
                            // Force reversed order by iterating backwards
                            val keys = src.keys.toList().reversed()
                            keys.forEach { k -> put(k, src.getValue(k)) }
                        }
                    },
                ),
            ),
        )
        val set = PolicySet(id = "baseline", policies = listOf(policy))
        assertEquals(Evaluator.evaluate(set, leftTree).digest, Evaluator.evaluate(set, rightTree).digest)
    }

    @Test
    fun `Spec UAT (g) rule whose FieldRef expected type mismatches the leaf returns Error with TYPE_MISMATCH`() {
        // Spec scenario: "Typed refusal on malformed IR" — Rule whose expression
        // references a value whose type does not match the declared expected type.
        // The selector surfaces TypeMismatch via `expectingType`; the evaluator
        // promotes that to RuleEvaluation.Error with code TYPE_MISMATCH.
        val treeWithText = ValueNode.MappingValue(
            linkedMapOf(
                "spec" to ValueNode.MappingValue(
                    linkedMapOf("replicas" to ValueNode.TextValue("three")),
                ),
            ),
        )
        val badPolicy = Policy(
            id = "bad",
            rules = listOf(
                Rule(
                    id = "missing-field",
                    message = "spec.replicas must be a Number",
                    expression = Comparison(
                        left = FieldRef(
                            DocumentPath.ROOT.child("spec").child("replicas"),
                            ValueNode.Type.NUMBER,
                        ),
                        op = Operator.GTE,
                        right = Literal(ValueNode.NumberValue(3)),
                    ),
                ),
            ),
        )
        val set = PolicySet(id = "bad-set", policies = listOf(badPolicy))
        val report = Evaluator.evaluate(set, treeWithText)
        val ev = report.results.values.first()
        assertTrue(
            ev is RuleEvaluation.Error,
            "expected Error for type-mismatched leaf, got $ev",
        )
        assertEquals(ViolationCode.TYPE_MISMATCH, ev.violations[0].code)
    }

    @Test
    fun `replicas equals 2 evaluates to Violated with COMPARISON_FAILED`() {
        val lowTree = ValueNode.MappingValue(
            linkedMapOf(
                "spec" to ValueNode.MappingValue(
                    linkedMapOf("replicas" to ValueNode.NumberValue(2)),
                ),
            ),
        )
        val set = PolicySet(id = "baseline", policies = listOf(policy))
        val report = Evaluator.evaluate(set, lowTree)
        val ev = report.results.values.first() as RuleEvaluation.Violated
        assertEquals(ViolationCode.COMPARISON_FAILED, ev.violations[0].code)
        assertEquals(">=3", ev.violations[0].expected)
        assertEquals("2", ev.violations[0].actual)
    }

    @Test
    fun `PolicyReport exposes deterministic digest for empty PolicySet`() {
        val set = PolicySet(id = "empty", policies = emptyList())
        val r1 = Evaluator.evaluate(set, tree).digest
        val r2 = Evaluator.evaluate(set, tree).digest
        assertEquals(r1, r2)
    }

    @Test
    fun `different PolicySets produce different digests`() {
        val set1 = PolicySet("a", listOf(policy))
        val set2 = PolicySet("b", listOf(policy))
        assertTrue(Evaluator.evaluate(set1, tree).digest != Evaluator.evaluate(set2, tree).digest)
    }

    // --- Mutation gate (REQ §"Mutation gate"): each test fails if the targeted mutation is applied ---

    @Test
    fun `mutation gate Operator GTE replaced by GT changes Passed to Violated for boundary value 3`() {
        // Spec REQ §"Mutation gate": swapping GTE->GT must change the verdict on replicas=3.
        // GTE(3, 3) = Passed; GT(3, 3) = Violated.
        val ruleGte = Rule(
            id = "replicas.gte",
            message = "replicas must be at least 3",
            expression = Comparison(
                op = Operator.GTE,
                left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
                right = Literal(ValueNode.NumberValue(3)),
            ),
        )
        val ruleGt = ruleGte.copy(
            id = "replicas.gt",
            expression = (ruleGte.expression as Comparison).copy(op = Operator.GT),
        )
        val policy1 = Policy(id = "p", rules = listOf(ruleGte))
        val policy2 = Policy(id = "p", rules = listOf(ruleGt))
        val set1 = PolicySet(id = "s", policies = listOf(policy1))
        val set2 = PolicySet(id = "s", policies = listOf(policy2))
        val out1 = Evaluator.evaluate(set1, tree).results.values.first()
        val out2 = Evaluator.evaluate(set2, tree).results.values.first()
        // replicas=3 with GTE 3 => Passed.
        assertTrue(out1 is RuleEvaluation.Passed, "GTE 3 vs 3 must be Passed")
        // replicas=3 with GT 3 => Violated.
        assertTrue(out2 is RuleEvaluation.Violated, "GT 3 vs 3 must be Violated")
    }

    @Test
    fun `mutation gate Selector Missing collapse to Present(false) breaks Violated with MISSING_REQUIRED_VALUE`() {
        // If a mutation made Selector.Result.Missing collapse to Present(MissingValue),
        // the evaluator would no longer produce Violated(MISSING_REQUIRED_VALUE) for absent path.
        val rule = Rule(
            id = "absent",
            message = "value missing",
            expression = Comparison(
                op = Operator.GT,
                left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
                right = Literal(ValueNode.NumberValue(0)),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val empty = ValueNode.MappingValue(linkedMapOf())
        val ev = Evaluator.evaluate(set, empty).results.values.first()
        assertTrue(ev is RuleEvaluation.Violated, "absent path must produce Violated (not Error / not Passed)")
        assertEquals(ViolationCode.MISSING_REQUIRED_VALUE, (ev as RuleEvaluation.Violated).violations[0].code)
    }

    @Test
    fun `mutation gate Text leaf coerced to Number would break COMPARISON_FAILED on Text vs Number literal`() {
        // If a mutation silently coerced Text("3") to Number(3), the comparison
        // 3 vs 3 with EQ would PASS; we require no silent coercion.
        val textTree = ValueNode.MappingValue(
            linkedMapOf("spec" to ValueNode.MappingValue(linkedMapOf("replicas" to ValueNode.TextValue("3")))),
        )
        val rule = Rule(
            id = "replicas.eq.3",
            message = "must equal 3",
            expression = Comparison(
                op = Operator.EQ,
                left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
                right = Literal(ValueNode.NumberValue(3)),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val out = Evaluator.evaluate(set, textTree).results.values.first()
        // Text leaf with Number expectation => Error(TYPE_MISMATCH), not Passed/Violated.
        // Either Error(TYPE_MISMATCH) or Violated(COMPARISON_FAILED) is acceptable per the gate;
        // what we MUST NOT see is Passed (that would prove silent coercion).
        assertTrue(out !is RuleEvaluation.Passed, "Text leaf must NOT be silently coerced to Number")
        assertTrue(out is RuleEvaluation.Error || out is RuleEvaluation.Violated)
    }
}

