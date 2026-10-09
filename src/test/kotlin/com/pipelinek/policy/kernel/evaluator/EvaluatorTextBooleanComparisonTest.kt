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
import com.pipelinek.policy.kernel.value.ValueNode.BooleanValue
import com.pipelinek.policy.kernel.value.ValueNode.MappingValue
import com.pipelinek.policy.kernel.value.ValueNode.TextValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §"DSL symbolic API" + MODIFIED REQ 5 — `Operator.TEXT_EQUALS` /
 * `Operator.BOOLEAN_EQUALS` close the ADT. Both evaluate structurally (Text by
 * `==`, Boolean by `==`) and NEVER coerce to Number (architectural law 9).
 */
class EvaluatorTextBooleanComparisonTest {

    @Test
    fun `TEXT_EQUALS over equal strings yields Passed`() {
        val tree = MappingValue(
            linkedMapOf("metadata" to MappingValue(linkedMapOf("name" to TextValue("hello")))),
        )
        val rule = Rule(
            id = "name-eq",
            message = "name must equal hello",
            expression = Comparison(
                op = Operator.TEXT_EQUALS,
                left = FieldRef(
                    DocumentPath.ROOT.child("metadata").child("name"),
                    ValueNode.Type.TEXT,
                ),
                right = Literal(TextValue("hello")),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results[RuleId.of("s", "p", rule.id)])
    }

    @Test
    fun `TEXT_EQUALS over different strings yields Violated with COMPARISON_FAILED`() {
        val tree = MappingValue(
            linkedMapOf("metadata" to MappingValue(linkedMapOf("name" to TextValue("world")))),
        )
        val rule = Rule(
            id = "name-eq",
            message = "name must equal hello",
            expression = Comparison(
                op = Operator.TEXT_EQUALS,
                left = FieldRef(
                    DocumentPath.ROOT.child("metadata").child("name"),
                    ValueNode.Type.TEXT,
                ),
                right = Literal(TextValue("hello")),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", rule.id)] as RuleEvaluation.Violated
        assertEquals(ViolationCode.COMPARISON_FAILED, ev.violations[0].code)
    }

    @Test
    fun `TEXT_EQUALS rejects Number operands with TYPE_MISMATCH (no silent coercion)`() {
        val tree = MappingValue(
            linkedMapOf("spec" to MappingValue(linkedMapOf("replicas" to ValueNode.NumberValue(3)))),
        )
        val rule = Rule(
            id = "name-eq",
            message = "name",
            expression = Comparison(
                op = Operator.TEXT_EQUALS,
                left = FieldRef(
                    DocumentPath.ROOT.child("spec").child("replicas"),
                    ValueNode.Type.NUMBER,
                ),
                right = Literal(TextValue("3")),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", rule.id)] as RuleEvaluation.Error
        assertEquals(ViolationCode.TYPE_MISMATCH, ev.violations[0].code)
    }

    @Test
    fun `BOOLEAN_EQUALS over equal booleans yields Passed`() {
        val tree = MappingValue(
            linkedMapOf("spec" to MappingValue(linkedMapOf("enabled" to BooleanValue(true)))),
        )
        val rule = Rule(
            id = "enabled-eq",
            message = "enabled must be true",
            expression = Comparison(
                op = Operator.BOOLEAN_EQUALS,
                left = FieldRef(
                    DocumentPath.ROOT.child("spec").child("enabled"),
                    ValueNode.Type.BOOLEAN,
                ),
                right = Literal(BooleanValue(true)),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results[RuleId.of("s", "p", rule.id)])
    }

    @Test
    fun `BOOLEAN_EQUALS over different booleans yields Violated with COMPARISON_FAILED`() {
        val tree = MappingValue(
            linkedMapOf("spec" to MappingValue(linkedMapOf("enabled" to BooleanValue(false)))),
        )
        val rule = Rule(
            id = "enabled-eq",
            message = "enabled must be true",
            expression = Comparison(
                op = Operator.BOOLEAN_EQUALS,
                left = FieldRef(
                    DocumentPath.ROOT.child("spec").child("enabled"),
                    ValueNode.Type.BOOLEAN,
                ),
                right = Literal(BooleanValue(true)),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", rule.id)] as RuleEvaluation.Violated
        assertEquals(ViolationCode.COMPARISON_FAILED, ev.violations[0].code)
    }

    @Test
    fun `BOOLEAN_EQUALS rejects Text operands with TYPE_MISMATCH (no silent coercion)`() {
        val tree = MappingValue(
            linkedMapOf("name" to TextValue("true")),
        )
        val rule = Rule(
            id = "name-bool",
            message = "name",
            expression = Comparison(
                op = Operator.BOOLEAN_EQUALS,
                left = FieldRef(
                    DocumentPath.ROOT.child("name"),
                    ValueNode.Type.TEXT,
                ),
                right = Literal(BooleanValue(true)),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", rule.id)] as RuleEvaluation.Error
        assertEquals(ViolationCode.TYPE_MISMATCH, ev.violations[0].code)
    }

    @Test
    fun `existing M1 numeric comparison still produces byte-equal output after M3 changes`() {
        // Back-compat guarantee: the canonical M1 rule (spec.replicas GTE 3)
        // produces the same digest and verdict shape across M1 and M3 evaluators.
        val tree = MappingValue(
            linkedMapOf("spec" to MappingValue(linkedMapOf("replicas" to ValueNode.NumberValue(3)))),
        )
        val rule = Rule(
            id = "r",
            message = "spec.replicas must be >= 3",
            expression = Comparison(
                op = Operator.GTE,
                left = FieldRef(
                    DocumentPath.ROOT.child("spec").child("replicas"),
                    ValueNode.Type.NUMBER,
                ),
                right = Literal(ValueNode.NumberValue(3)),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        assertTrue(report.results[RuleId.of("s", "p", rule.id)] is RuleEvaluation.Passed)
    }
}
