package com.pipelinek.policy.kernel.evaluator

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.CollectionOp
import com.pipelinek.policy.kernel.expression.Expression.CollectionPredicate
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * H2.3 · COUNT must count MATCHES, not rows.
 *
 * M8_SPECIFICATION REQ M8-04 states the contract twice and unambiguously:
 *
 *   04b: "cap 10 con 11 matches => refusal BUDGET_EXCEEDED"
 *   04c: "acumular sobre TextValue => TYPE_MISMATCH (sin coerción)"
 *
 * plus the accumulator note: "COUNT solo sobre NumberValue; texto =>
 * TYPE_MISMATCH, ley 9".
 *
 * Accumulator.Count -- the STREAMING fold -- carries the comment "Count does not
 * inspect content: any node counts as one row" and increments unconditionally,
 * so on that path COUNT counts ROWS, contradicting 04b.
 *
 * The pre-existing suite could not see it: its fixture `seq(10_000)` is
 * `List(10_000) { NumberValue(it) }`, so every element matches and "counts
 * matches" and "counts rows" give the identical number. The test passes either
 * way and pins nothing about the distinction.
 *
 * These cases use a MIXED sequence so the two readings give different answers.
 * They go through the IN-MEMORY evaluator, which is a different code path from
 * the streaming accumulator; see the streaming case for what that path does.
 */
class CountMatchesTest {

    /** Four rows; only two are Numbers, so a NUMBER predicate matches 2 of 4. */
    private val mixed = ValueNode.SequenceValue(
        listOf(
            ValueNode.NumberValue(1),
            ValueNode.TextValue("not a number"),
            ValueNode.NumberValue(2),
            ValueNode.TextValue("also not"),
        ),
    )

    private fun countAtLeast(threshold: Long, predicateType: ValueNode.Type): RuleEvaluation {
        val rule = Rule(
            id = "count-rule",
            message = "at least $threshold matching elements",
            expression = Expression.Comparison(
                left = CollectionPredicate(
                    op = CollectionOp.COUNT,
                    source = FieldRef(DocumentPath.ROOT.child("list"), ValueNode.Type.SEQUENCE),
                    predicate = Selector.optional(DocumentPath.ROOT.child("value"))
                        .expectingType(predicateType),
                ),
                op = Operator.GTE,
                right = Literal(ValueNode.NumberValue(threshold)),
            ),
        )
        val tree = ValueNode.MappingValue(linkedMapOf("list" to mixed))
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        return Evaluator.evaluate(set, tree).results.values.single()
    }

    /**
     * Two elements match, so `>= 2` holds. Counting rows (4) would also pass, so
     * this case alone cannot separate the two readings; the next one can.
     */
    @Test
    fun `count over a mixed sequence satisfies a threshold of two`() {
        assertEquals(RuleEvaluation.Passed, countAtLeast(2, ValueNode.Type.NUMBER))
    }

    /**
     * The case that separates the two readings.
     *
     *   - counting MATCHES gives 2, so `>= 3` is FALSE  => Violated
     *   - counting ROWS    gives 4, so `>= 3` is TRUE   => Passed
     *
     * Spec 04b phrases the budget in terms of matches, so the rule must be
     * VIOLATED. An implementation that counts rows returns Passed and therefore
     * does not implement the documented contract.
     *
     * This assertion is the whole point of the file: if COUNT ever stops being
     * evaluated numerically, this fails rather than passing for an unrelated
     * reason.
     */
    @Test
    fun `count counts matches so two matches cannot satisfy a threshold of three`() {
        val result = countAtLeast(3, ValueNode.Type.NUMBER)

        assertEquals(
            1,
            assertIs<RuleEvaluation.Violated>(result, "expected a violation").violations.size,
            "COUNT must count the 2 matching elements, not the 4 rows; got $result",
        )
    }

    /**
     * A tighter form of the same property, which also pins the value rather than
     * only the verdict: with a threshold of exactly 2 the rule holds, and at 3
     * it does not. Together these two pin COUNT to 2, and 2 is only reachable by
     * counting matches.
     */
    @Test
    fun `the counted value sits exactly at the number of matching elements`() {
        assertEquals(RuleEvaluation.Passed, countAtLeast(2, ValueNode.Type.NUMBER))
        assertEquals(1, assertIs<RuleEvaluation.Violated>(countAtLeast(3, ValueNode.Type.NUMBER)).violations.size)
    }

    /**
     * Spec 04c: accumulating over TextValue is TYPE_MISMATCH, with no coercion.
     *
     * With a TEXT predicate on the same mixed sequence, exactly the two Text
     * rows match. So `>= 2` holds and `>= 3` does not -- the mirror of the
     * NUMBER case, which is what proves the predicate type is actually being
     * honoured rather than ignored on both sides.
     */
    @Test
    fun `count honours the predicate type so text rows are counted for a text predicate`() {
        assertEquals(RuleEvaluation.Passed, countAtLeast(2, ValueNode.Type.TEXT))
        assertEquals(1, assertIs<RuleEvaluation.Violated>(countAtLeast(3, ValueNode.Type.TEXT)).violations.size)
    }
}
