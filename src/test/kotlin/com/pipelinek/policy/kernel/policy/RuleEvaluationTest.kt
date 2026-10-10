package com.pipelinek.policy.kernel.policy

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.RuleId
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import com.pipelinek.policy.kernel.value.ValueNode.BooleanValue
import com.pipelinek.policy.kernel.value.ValueNode.MappingValue
import com.pipelinek.policy.kernel.value.ValueNode.NumberValue
import com.pipelinek.policy.kernel.value.ValueNode.SequenceValue
import com.pipelinek.policy.kernel.value.ValueNode.TextValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §"DSL rule combinators" — `Rule.appliesWhen` short-circuits to
 * `NotApplicable` (NOT `Violated`) on a falsy / missing outcome. The canonical
 * PolicyReport digest MUST differ from a `Violated(MISSING_REQUIRED_VALUE)` over
 * the same input (mutation gate item 3).
 */
class RuleEvaluationTest {

    @Test
    fun `appliesWhen with literal false yields NotApplicable (not Violated)`() {
        val rule = Rule(
            id = "r1",
            message = "should not violate when guard says no",
            expression = Comparison(
                op = Operator.GTE,
                left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
                right = Literal(NumberValue(3)),
            ),
            appliesWhen = Literal(BooleanValue(false)),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val tree = MappingValue(
            linkedMapOf("spec" to MappingValue(linkedMapOf("replicas" to NumberValue(2)))),
        )
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", rule.id)]
        assertEquals(RuleEvaluation.NotApplicable, ev)
    }

    @Test
    fun `appliesWhen with literal true lets main expression run`() {
        val rule = Rule(
            id = "r2",
            message = "guard true",
            expression = Comparison(
                op = Operator.GTE,
                left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
                right = Literal(NumberValue(3)),
            ),
            appliesWhen = Literal(BooleanValue(true)),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val tree = MappingValue(
            linkedMapOf("spec" to MappingValue(linkedMapOf("replicas" to NumberValue(2)))),
        )
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", rule.id)]
        // replicas=2 with GTE 3 ⇒ Violated (not NotApplicable).
        assertTrue(ev is RuleEvaluation.Violated, "expected Violated, got $ev")
        assertEquals(ViolationCode.COMPARISON_FAILED, (ev as RuleEvaluation.Violated).violations[0].code)
    }

    @Test
    fun `appliesWhen(false) yields a digest distinct from Violated`() {
        val tree = MappingValue(
            linkedMapOf("spec" to MappingValue(linkedMapOf("replicas" to NumberValue(2)))),
        )
        val ruleWithApplies = Rule(
            id = "x",
            message = "guard",
            expression = Comparison(
                op = Operator.GTE,
                left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
                right = Literal(NumberValue(3)),
            ),
            appliesWhen = Literal(BooleanValue(false)),
        )
        val ruleWithoutApplies = Rule(
            id = "x",
            message = "guard",
            expression = Comparison(
                op = Operator.GTE,
                left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
                right = Literal(NumberValue(3)),
            ),
        )
        val setA = PolicySet(id = "s", policies = listOf(Policy(id = "p", rules = listOf(ruleWithApplies))))
        val setB = PolicySet(id = "s", policies = listOf(Policy(id = "p", rules = listOf(ruleWithoutApplies))))
        val dA = Evaluator.evaluate(setA, tree).digest
        val dB = Evaluator.evaluate(setB, tree).digest
        assertNotEquals(dA, dB, "appliesWhen(false) MUST produce a digest distinct from Violated")
    }

    @Test
    fun `appliesWhen with missing required source yields NotApplicable (deterministic short-circuit)`() {
        // Build an appliesWhen that resolves a missing optional boolean. We
        // construct a Comparison against a missing literal field so the gate
        // is a `MissingValueException` that short-circuits to NotApplicable.
        val rule = Rule(
            id = "r3",
            message = "guard via missing field",
            expression = Comparison(
                op = Operator.GTE,
                left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
                right = Literal(NumberValue(3)),
            ),
            appliesWhen = Comparison(
                op = Operator.TEXT_EQUALS,
                left = FieldRef(
                    path = DocumentPath.ROOT.child("metadata").child("name"),
                    expectedType = ValueNode.Type.TEXT,
                ),
                right = Literal(TextValue("Deployment")),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        // Empty tree; appliesWhen FieldRef is missing ⇒ NotApplicable, not Error.
        val tree = MappingValue(linkedMapOf())
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", rule.id)]
        assertEquals(RuleEvaluation.NotApplicable, ev)
    }

    /**
     * B6.5 · A guard that itself refuses must surface as `Error`, never as
     * `NotApplicable`.
     *
     * Added because the A2 mutant (mapping `TypedRefusal` to `NotApplicable`)
     * SURVIVED the whole suite. The existing tests here cover a falsy literal
     * guard and a missing-field guard, but none covers a guard that *refuses* —
     * for example comparing TEXT to a NUMBER, which architectural law 9 forbids
     * coercing. Swallowing that into `NotApplicable` is the worst possible
     * outcome: the author's broken predicate looks like the rule simply did not
     * apply, and the document silently passes.
     */
    @Test
    fun `appliesWhen typed refusal yields Error, not NotApplicable`() {
        val rule = Rule(
            id = "broken-guard",
            message = "guard compares incompatible types",
            expression = Literal(BooleanValue(true)),
            appliesWhen = Comparison(
                op = Operator.GTE,
                // TEXT vs NUMBER: the kernel must refuse rather than coerce.
                left = FieldRef(DocumentPath.ROOT.child("metadata").child("team"), ValueNode.Type.TEXT),
                right = Literal(NumberValue(3)),
            ),
        )
        val set = PolicySet(id = "s", policies = listOf(Policy(id = "p", rules = listOf(rule))))
        val tree = MappingValue(
            linkedMapOf("metadata" to MappingValue(linkedMapOf("team" to TextValue("platform")))),
        )
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", rule.id)]
        assertTrue(
            ev is RuleEvaluation.Error,
            "a refusing appliesWhen must be Error so the author sees it, got $ev",
        )
    }

    @Test
    fun `Rule carries typed metadata fields`() {
        val rule = Rule(
            id = "r4",
            message = "with metadata",
            expression = Literal(BooleanValue(true)),
            code = "MY_CODE",
            expected = "expected-value",
            actual = "actual-value",
            params = mapOf("min" to ParamValue.LongV(3)),
        )
        assertEquals("MY_CODE", rule.code)
        assertEquals("expected-value", rule.expected)
        assertEquals("actual-value", rule.actual)
        assertEquals(ParamValue.LongV(3), rule.params["min"])
    }
}
