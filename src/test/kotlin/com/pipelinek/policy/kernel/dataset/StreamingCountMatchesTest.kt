package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * H2.3 · Streaming COUNT must count MATCHES, not rows.
 *
 * `Accumulator.Count.accept` carries the comment "Count does not inspect
 * content: any node counts as one row" and increments unconditionally:
 *
 *     // Count does not inspect content: any node counts as one row.
 *     @Suppress("UNUSED_EXPRESSION")
 *     value
 *     count++
 *
 * M8_SPECIFICATION REQ M8-04 04b states the budget in terms of MATCHES: "cap 10
 * con 11 matches => refusal BUDGET_EXCEEDED". Counting rows instead means a
 * stream full of non-matching rows still consumes -- and is counted against --
 * the cap, and a COUNT rule is satisfied by rows that do not satisfy its own
 * predicate. That is a policy author writing "count rows where X" and getting
 * "count every row".
 *
 * The pre-existing test `05b - COUNT aggregate folds every row via accumulator`
 * cannot see this. Its predicate is `Selector.of(DocumentPath.ROOT)` -- the
 * whole row -- so every row matches by construction, and its fixture is four
 * uniform rows. Rows and matches are the same four, so the assertion pins
 * nothing about the distinction. It asserts `NumberValue(4L)` for four rows,
 * which is exactly what a row counter produces, so it cannot fail if the
 * implementation only ever counts rows.
 *
 * These cases use a predicate that matches a strict SUBSET of the rows, so
 * matches and rows differ and the two implementations disagree.
 */
class StreamingCountMatchesTest {

    private val rows = listOf(
        ValueNode.MappingValue(linkedMapOf("n" to ValueNode.NumberValue(1))),
        ValueNode.MappingValue(linkedMapOf("n" to ValueNode.TextValue("skip"))),
        ValueNode.MappingValue(linkedMapOf("n" to ValueNode.NumberValue(2))),
        ValueNode.MappingValue(linkedMapOf("n" to ValueNode.TextValue("skip"))),
        ValueNode.MappingValue(linkedMapOf("n" to ValueNode.NumberValue(3))),
    )

    /** Five rows; three hold a Number, so a NUMBER predicate on `n` matches 3. */
    private val matchCount = 3L

    private fun supplier(): StreamingEvaluator.RowSupplier = supplierFor(rows)

    private fun supplierFor(source: List<ValueNode>): StreamingEvaluator.RowSupplier {
        val iter = source.iterator()
        return StreamingEvaluator.RowSupplier { if (iter.hasNext()) iter.next() else null }
    }

    private fun countOver(predicate: Selector): ValueNode {
        val rule = Rule(
            id = "count-matches",
            message = "count matching rows",
            expression = Expression.CollectionPredicate(
                op = Expression.CollectionOp.COUNT,
                source = Expression.DatasetRef("telemetry"),
                predicate = predicate,
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val plan = DatasetPlanner.plan(
            set,
            mapOf("telemetry" to DatasetSpec("telemetry", "csv")),
        ) as DatasetPlan
        val report = StreamingEvaluator.evaluate(set, plan, supplier())
        val agg = report.results.getValue(RuleKey.of("s", "p", "count-matches"))
        return (agg as StreamingEvaluator.RuleOutcome.Aggregate).value
    }

    /**
     * The discriminating assertion.
     *
     * Three rows carry a Number and two carry Text. A predicate requiring a
     * Number matches exactly three:
     *   - counting MATCHES gives 3
     *   - counting ROWS    gives 5
     *
     * Spec 04b phrases the budget in terms of matches, so the value must be 3.
     * `Accumulator.Count` as written returns 5, because it increments without
     * consulting the predicate at all.
     */
    @Test
    fun `streaming count counts matching rows and not every row`() {
        val value = countOver(Selector.of(DocumentPath.ROOT.child("n")).expectingType(ValueNode.Type.NUMBER))

        assertEquals(
            ValueNode.NumberValue(matchCount),
            value,
            "COUNT must count the $matchCount rows whose `n` is a NUMBER, not all " +
                "${rows.size} rows; Accumulator.Count increments unconditionally",
        )
    }

    /**
     * The positive control. Every row here matches, so a matcher and a row
     * counter agree. Without this, a mutant that made COUNT always return the
     * match count constant would pass.
     */
    @Test
    fun `streaming count over a predicate every row satisfies equals the row count`() {
        val value = countOver(Selector.of(DocumentPath.ROOT))

        assertEquals(ValueNode.NumberValue(rows.size.toLong()), value)
    }

    /**
     * Spec 04b, the budget half: "cap 10 con 11 matches => refusal BUDGET_EXCEEDED".
     *
     * The cap bounds MATCHES, not rows. Both halves matter and they pull in
     * opposite directions:
     *
     *  - three matching rows against a cap of 2 MUST refuse, or a COUNT over a
     *    long dataset could never be bounded;
     *  - ONE matching row against a cap of 2, padded with non-matching rows,
     *    MUST NOT refuse. Reading costs real work and stays bounded by
     *    [DatasetPlan.rowBudget], but it is not a match, so it must not consume
     *    the match cap. A cap silently re-bound to rows fails here: the single
     *    matching row would be the third row the accumulator ever saw.
     *
     * This is the case that separates "counts matches" from "counts rows" at the
     * BUDGET level, where the first two tests do not.
     */
    @Test
    fun `the count cap bounds matching rows and not rows read`() {
        val rule = Rule(
            id = "count-capped",
            message = "count matching rows",
            expression = Expression.CollectionPredicate(
                op = Expression.CollectionOp.COUNT,
                source = Expression.DatasetRef("telemetry"),
                predicate = Selector.of(DocumentPath.ROOT.child("n")).expectingType(ValueNode.Type.NUMBER),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val key = RuleKey.of("s", "p", "count-capped")

        fun runOver(source: List<ValueNode>, cap: Long): StreamingEvaluator.RuleOutcome {
            val datasets = mapOf("telemetry" to DatasetSpec("telemetry", "csv"))
            val plan = DatasetPlanner.plan(set, datasets, accumulatorCap = cap) as DatasetPlan
            return StreamingEvaluator.evaluate(set, plan, supplierFor(source)).results.getValue(key)
        }

        // Three matching rows, cap 2: the third match is refused. The refusal is
        // reported as a typed Error naming the cap, never as a silent aggregate.
        assertTrue(
            runOver(rows, cap = 2).let {
                it is StreamingEvaluator.RuleOutcome.Error && it.message.startsWith("count cap 2")
            },
            "a cap of 2 over 3 matching rows must refuse; got ${runOver(rows, cap = 2)}",
        )

        // One matching row among five, cap 2: no refusal, and the value is 1.
        val oneMatch = listOf(rows[0]) +
            List(4) { ValueNode.MappingValue(linkedMapOf("n" to ValueNode.TextValue("x"))) }
        val outcome = runOver(oneMatch, cap = 2)

        assertIs<StreamingEvaluator.RuleOutcome.Aggregate>(
            outcome,
            "reading non-matching rows must not refuse: $outcome",
        )
        assertEquals(
            ValueNode.NumberValue(1L),
            outcome.value,
            "only the numeric row is a match",
        )
    }

}
