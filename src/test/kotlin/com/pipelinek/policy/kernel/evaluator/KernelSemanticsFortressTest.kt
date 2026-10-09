package com.pipelinek.policy.kernel.evaluator

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BLOQUE B1 · Falsification gates for kernel semantics (B1.1 RuleKey,
 * B1.2 exact numerics, B1.4 composed COUNT, B1.7 determinism).
 *
 * Red against pre-B1 code where the defect exists (documented per test).
 */
class KernelSemanticsFortressTest {

    private fun textRule(ruleId: String, policyId: String, expected: String): Pair<String, Rule> =
        policyId to Rule(
            id = ruleId,
            message = "$policyId/$ruleId expects $expected",
            expression = Expression.Comparison(
                Expression.FieldRef(DocumentPath.ROOT.child("team"), ValueNode.Type.TEXT),
                Expression.Operator.TEXT_EQUALS,
                Expression.Literal(ValueNode.TextValue(expected)),
            ),
        )

    // --- B1.1: identity collision ---

    @Test
    fun `B1-1 two policies with the same ruleId keep both results`() {
        val (p1, r1) = textRule("same-id", "policy-a", "platform")
        val (p2, r2) = textRule("same-id", "policy-b", "evil")
        val set = PolicySet(
            "s",
            listOf(
                Policy(p1, listOf(r1)),
                Policy(p2, listOf(r2)),
            ),
        )
        val report = Evaluator.evaluate(
            set,
            ValueNode.MappingValue(linkedMapOf("team" to ValueNode.TextValue("evil"))),
        )
        // policy-a/same-id VIOLATES (team=evil != platform);
        // policy-b/same-id PASSES (team=evil == evil).
        // A RuleId-only map loses one of the two: 1 result instead of 2.
        if (report.results.size != 2) {
            fail(
                "rule identity collision: ${report.results.size} result(s) for 2 rules " +
                    "sharing ruleId 'same-id' across policies — one verdict was silently lost",
            )
        }
        val values = report.results.values.sortedBy { it.toString() }
        assertTrue(values.any { it is RuleEvaluation.Violated })
        assertTrue(values.any { it is RuleEvaluation.Passed })
    }

    @Test
    fun `B1-1b dotted policy and rule ids cannot collide`() {
        val (dottedPolicy, dottedRule) = textRule("c", "a.b", "platform")
        val (plainPolicy, plainRule) = textRule("b.c", "a", "evil")
        val set = PolicySet(
            "s",
            listOf(
                Policy(dottedPolicy, listOf(dottedRule)),
                Policy(plainPolicy, listOf(plainRule)),
            ),
        )
        val report = Evaluator.evaluate(
            set,
            ValueNode.MappingValue(linkedMapOf("team" to ValueNode.TextValue("evil"))),
        )
        val dottedKey = RuleKey.of("s", "a.b", "c")
        val plainKey = RuleKey.of("s", "a", "b.c")
        assertTrue(dottedKey != plainKey, "structural key components must never collide")
        assertTrue(dottedKey.value != plainKey.value, "canonical string form must be injective")
        assertEquals(2, report.results.size, "both valid rules must retain a result")
        assertTrue(report.results.containsKey(dottedKey))
        assertTrue(report.results.containsKey(plainKey))
        assertTrue(!report.results.containsKey(RuleKey.of("other-set", "a.b", "c")))

        val reversed = Evaluator.evaluate(
            PolicySet("s", listOf(Policy(plainPolicy, listOf(plainRule)), Policy(dottedPolicy, listOf(dottedRule)))),
            ValueNode.MappingValue(linkedMapOf("team" to ValueNode.TextValue("evil"))),
        )
        assertEquals(report.digest, reversed.digest, "injective keys keep digest stable under insertion order")
    }

    @Test
    fun `B1-1c duplicate identical contextual keys are refused instead of overwritten`() {
        val duplicatePolicy = Policy("p", listOf(textRule("same", "p", "platform").second))
        assertFailsWith<IllegalArgumentException> {
            PolicySet("s", listOf(duplicatePolicy, duplicatePolicy))
        }
    }

    // --- B1.2: exact numerics ---

    @Test
    fun `B1-2a integers beyond 2^53 compare exactly`() {
        // 2^53+1 rounds down to 2^53 as Double. The exact comparison is
        // GT(2^53+1, 2^53) = true; a Double-based mutant collapses both and
        // returns false. This is the discriminator, unlike two values that
        // happen to round to different adjacent doubles.
        val threshold = BigInteger.TWO.pow(53) // 9007199254740992
        val aboveThreshold = threshold.add(BigInteger.ONE) // 9007199254740993
        val rule = Rule(
            id = "beyond-double",
            message = "replicas must be > 2^53",
            expression = Expression.Comparison(
                Expression.FieldRef(DocumentPath.ROOT.child("replicas"), ValueNode.Type.NUMBER),
                Expression.Operator.GT,
                Expression.Literal(ValueNode.NumberValue(threshold)),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val report = Evaluator.evaluate(
            set,
            ValueNode.MappingValue(linkedMapOf("replicas" to ValueNode.NumberValue(aboveThreshold))),
        )
        val outcome = report.results.values.single()
        if (outcome !is RuleEvaluation.Passed) {
            fail("exact numeric comparison lost: $aboveThreshold > $threshold should pass (Double collapse?)")
        }
    }

    @Test
    fun `B1-2b decimals compare exactly (no Double rounding)`() {
        val rule = Rule(
            id = "ratio",
            message = "ratio must be < 0.3",
            expression = Expression.Comparison(
                Expression.FieldRef(DocumentPath.ROOT.child("ratio"), ValueNode.Type.NUMBER),
                Expression.Operator.LT,
                Expression.Literal(ValueNode.NumberValue(BigDecimal("0.3"))),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        // 0.1+0.2 == 0.30000000000000004 as Double sum vs literal 0.3: LT
        // must compare the EXACT decimals, and the resource value here is
        // exactly 0.3 → LT(0.3, 0.3) is false → Violated. Under Double,
        // 0.3 literal is also 0.2999...~ so behavior may coincide; the real
        // discriminator is a value that Double cannot represent.
        val report = Evaluator.evaluate(
            set,
            ValueNode.MappingValue(
                linkedMapOf("ratio" to ValueNode.NumberValue(BigDecimal("0.30000000000000004"))),
            ),
        )
        val outcome = report.results.values.single()
        // LT(0.30000000000000004, 0.3) = false → Violated (COMPARISON_FAILED).
        assertTrue(outcome is RuleEvaluation.Violated, "0.3000...4 < 0.3 must be false")
    }

    @Test
    fun `B1-2c mixed BigInteger and BigDecimal compare exactly`() {
        val exact = BigInteger.TWO.pow(100)
        val rule = Rule(
            id = "mixed-carriers",
            message = "equal integer and decimal carriers compare equal",
            expression = Expression.Comparison(
                Expression.FieldRef(DocumentPath.ROOT.child("n"), ValueNode.Type.NUMBER),
                Expression.Operator.EQ,
                Expression.Literal(ValueNode.NumberValue(BigDecimal("1267650600228229401496703205376.0"))),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val report = Evaluator.evaluate(
            set,
            ValueNode.MappingValue(linkedMapOf("n" to ValueNode.NumberValue(exact))),
        )
        assertEquals(RuleEvaluation.Passed, report.results.values.single())
    }

    @Test
    fun `B1-2d non-finite Double is a typed Error, not evaluator crash`() {
        val rule = Rule(
            id = "nan",
            message = "NaN is not a policy number",
            expression = Expression.Comparison(
                Expression.FieldRef(DocumentPath.ROOT.child("n"), ValueNode.Type.NUMBER),
                Expression.Operator.EQ,
                Expression.Literal(ValueNode.NumberValue(0.0)),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val report = Evaluator.evaluate(
            set,
            ValueNode.MappingValue(linkedMapOf("n" to ValueNode.NumberValue(Double.NaN))),
        )
        val outcome = report.results.values.single()
        assertTrue(outcome is RuleEvaluation.Error, "NaN must be a typed Error, got $outcome")
        assertEquals(
            com.pipelinek.policy.kernel.policy.ViolationCode.TYPE_MISMATCH,
            (outcome as RuleEvaluation.Error).violations.single().code,
        )
    }

    // --- B1.4: composed COUNT >= N ---

    @Test
    fun `B1-4 count(items) GTE 2 composes as a real comparison`() {
        val countExpr = Expression.CollectionPredicate(
            op = Expression.CollectionOp.COUNT,
            source = Expression.FieldRef(DocumentPath.ROOT.child("items"), ValueNode.Type.SEQUENCE),
            predicate = Selector.of(DocumentPath.ROOT),
        )
        val rule = Rule(
            id = "at-least-two",
            message = "items must have >= 2 elements",
            expression = Expression.Comparison(
                countExpr,
                Expression.Operator.GTE,
                Expression.Literal(ValueNode.NumberValue(2)),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))

        val oneItem = ValueNode.MappingValue(
            linkedMapOf(
                "items" to ValueNode.SequenceValue(listOf(ValueNode.TextValue("a"))),
            ),
        )
        val report = Evaluator.evaluate(set, oneItem)
        val outcome = report.results.values.single()
        // COUNT=1, GTE 2 false → must be a Violation, not an exception smuggle
        // or a silent pass.
        assertTrue(
            outcome is RuleEvaluation.Violated,
            "count(items)=1 >= 2 must Violate, got $outcome",
        )
    }

    @Test
    fun `B1-4b count over empty collection is numeric zero`() {
        val countExpr = Expression.CollectionPredicate(
            op = Expression.CollectionOp.COUNT,
            source = Expression.FieldRef(DocumentPath.ROOT.child("items"), ValueNode.Type.SEQUENCE),
            predicate = Selector.of(DocumentPath.ROOT),
        )
        val rule = Rule(
            id = "empty-count",
            message = "empty collection has count zero",
            expression = Expression.Comparison(
                countExpr,
                Expression.Operator.EQ,
                Expression.Literal(ValueNode.NumberValue(0L)),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val empty = ValueNode.MappingValue(
            linkedMapOf("items" to ValueNode.SequenceValue(emptyList())),
        )
        assertEquals(
            RuleEvaluation.Passed,
            Evaluator.evaluate(set, empty).results[RuleKey.of("s", "p", "empty-count")],
        )
    }

    @Test
    fun `B1-4c bare COUNT is a typed error not a boolean or exception signal`() {
        val rule = Rule(
            id = "bare-count",
            message = "COUNT requires a numeric comparison",
            expression = Expression.CollectionPredicate(
                op = Expression.CollectionOp.COUNT,
                source = Expression.FieldRef(DocumentPath.ROOT.child("items"), ValueNode.Type.SEQUENCE),
                predicate = Selector.of(DocumentPath.ROOT),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val resource = ValueNode.MappingValue(
            linkedMapOf("items" to ValueNode.SequenceValue(listOf(ValueNode.TextValue("x")))),
        )
        val outcome = Evaluator.evaluate(set, resource).results[RuleKey.of("s", "p", "bare-count")]
        assertTrue(outcome is RuleEvaluation.Error, "bare COUNT must be a typed Error, got $outcome")
        assertEquals(
            com.pipelinek.policy.kernel.policy.ViolationCode.TYPE_MISMATCH,
            (outcome as RuleEvaluation.Error).violations.single().code,
        )
    }

    // --- B1.7: determinism ---

    @Test
    fun `B1-7 same inputs same digest, insertion order does not matter`() {
        val (_, ra) = textRule("r-a", "p1", "platform")
        val (_, rb) = textRule("r-b", "p2", "platform")
        val tree = ValueNode.MappingValue(linkedMapOf("team" to ValueNode.TextValue("platform")))

        val report1 = Evaluator.evaluate(
            PolicySet("s", listOf(Policy("p1", listOf(ra)), Policy("p2", listOf(rb)))),
            tree,
        )
        val report2 = Evaluator.evaluate(
            PolicySet("s", listOf(Policy("p2", listOf(rb)), Policy("p1", listOf(ra)))),
            tree,
        )

        assertEquals(report1.digest, report2.digest, "digest must be insertion-order independent")
    }
}
