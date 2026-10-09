package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8 REQ 02/03/05 · Planner refusals + StreamingEvaluator parity (02b/03c/05b).
 *
 * 03c parity: streaming LOCAL verdicts equal whole-document evaluation over
 * the SAME rows — structural reuse of Evaluator.evaluate per row.
 */
class StreamingEvaluatorTest {

    private val datasets = mapOf("telemetry" to DatasetSpec("telemetry", "csv"))

    private fun textRule(id: String, expected: String) = Rule(
        id = id,
        message = "$id expects $expected",
        expression = Expression.Comparison(
            Expression.FieldRef(DocumentPath.ROOT.child("temp"), ValueNode.Type.TEXT),
            Expression.Operator.TEXT_EQUALS,
            Expression.Literal(ValueNode.TextValue(expected)),
        ),
    )

    private fun countRule(id: String) = Rule(
        id = id,
        message = "count rows",
        expression = Expression.CollectionPredicate(
            op = Expression.CollectionOp.COUNT,
            source = Expression.DatasetRef("telemetry"),
            predicate = Selector.of(DocumentPath.ROOT),
        ),
    )

    private fun rowsOf(vararg temps: String): StreamingEvaluator.RowSupplier {
        val rows = temps.map { t ->
            ValueNode.MappingValue(linkedMapOf("temp" to ValueNode.TextValue(t)))
        }
        val iter = rows.iterator()
        return StreamingEvaluator.RowSupplier { if (iter.hasNext()) iter.next() else null }
    }

    @Test
    fun `02b - falsification - planner refuses GLOBAL rules instead of weakening them`() {
        val globalRule = Rule(
            id = "cross-row",
            message = "global access",
            expression = Expression.Comparison(
                Expression.DatasetRef("telemetry"),
                Expression.Operator.TEXT_EQUALS,
                Expression.Literal(ValueNode.TextValue("x")),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(globalRule))))
        val plan = DatasetPlanner.plan(set, datasets)
        assertTrue(plan is PlanRefusal)
        assertEquals(listOf("cross-row"), plan.offendingRules)
        assertTrue(plan.reason.contains("law 12"))
    }

    @Test
    fun `05b - planner splits LOCAL vs AGGREGATE`() {
        val set = PolicySet(
            "s",
            listOf(Policy("p", listOf(textRule("local-1", "ok"), countRule("count-all")))),
        )
        val plan = DatasetPlanner.plan(set, datasets)
        assertTrue(plan is DatasetPlan)
        assertEquals(listOf("local-1"), plan.localRuleIds)
        assertEquals(listOf("count-all"), plan.aggregateRuleIds)
    }

    @Test
    fun `03c - parity - streaming LOCAL verdicts equal whole-document verdicts per row`() {
        val set = PolicySet("s", listOf(Policy("p", listOf(textRule("must-ok", "ok")))))
        val plan = DatasetPlanner.plan(set, datasets) as DatasetPlan

        val temps = listOf("ok", "bad", "ok", "worse")
        val report = StreamingEvaluator.evaluate(set, plan, rowsOf(*temps.toTypedArray()))

        // Ground truth: whole-document evaluation of the same rows.
        val wholeTree = ValueNode.SequenceValue(
            temps.map { ValueNode.MappingValue(linkedMapOf("temp" to ValueNode.TextValue(it))) },
        )
        val wholeReport = Evaluator.evaluate(set, wholeTree)
        val wholeVerdict =
            wholeReport.results[com.pipelinek.policy.kernel.evaluator.RuleId("must-ok")]!!

        val streamed = report.results["must-ok"]!!
        when (wholeVerdict) {
            is RuleEvaluation.Violated -> {
                val v = streamed as StreamingEvaluator.RuleOutcome.Violated
                // Same offending rows: 2 violations ("bad", "worse"), last at row 4.
                assertEquals(2L, v.rowCount)
                assertEquals(4L, v.lastRowNumber)
            }
            else -> throw AssertionError("whole-document ground truth must be Violated")
        }
    }

    @Test
    fun `03c - parity - passing stream reports Passed for all LOCAL rules`() {
        val set = PolicySet(
            "s",
            listOf(Policy("p", listOf(textRule("r1", "ok"), textRule("r2", "ok")))),
        )
        val plan = DatasetPlanner.plan(set, datasets) as DatasetPlan
        val report = StreamingEvaluator.evaluate(set, plan, rowsOf("ok", "ok", "ok"))
        assertEquals(StreamingEvaluator.RuleOutcome.Passed, report.results["r1"])
        assertEquals(StreamingEvaluator.RuleOutcome.Passed, report.results["r2"])
        assertEquals(3L, report.metrics.rowsConsumed)
        assertTrue(report.metrics.rowsWithinBudget)
    }

    @Test
    fun `05b - COUNT aggregate folds every row via accumulator`() {
        val set = PolicySet("s", listOf(Policy("p", listOf(countRule("count-all")))))
        val plan = DatasetPlanner.plan(set, datasets) as DatasetPlan
        val report = StreamingEvaluator.evaluate(set, plan, rowsOf("a", "b", "c", "d"))
        val agg = report.results["count-all"]!! as StreamingEvaluator.RuleOutcome.Aggregate
        assertEquals(ValueNode.NumberValue(4L), agg.value)
        assertEquals(1, report.metrics.accumulatorsUsed)
    }

    @Test
    fun `05c - falsification - row budget refuses instead of running forever`() {
        val set = PolicySet("s", listOf(Policy("p", listOf(textRule("r", "ok")))))
        val plan = (DatasetPlanner.plan(set, datasets) as DatasetPlan).copy(rowBudget = 3)
        val endless = StreamingEvaluator.RowSupplier { ValueNode.MappingValue(linkedMapOf()) }
        val ex = assertThrows<BudgetExceededException> {
            StreamingEvaluator.evaluate(set, plan, endless)
        }
        assertTrue(ex.message!!.contains("row budget 3"))
    }

    @Test
    fun `05c - falsification - plan of a different set refuses fast`() {
        val set = PolicySet("s", listOf(Policy("p", listOf(textRule("r", "ok")))))
        val other = PolicySet("other", listOf(Policy("p", listOf(textRule("r", "ok")))))
        val plan = DatasetPlanner.plan(other, datasets) as DatasetPlan
        assertThrows<IllegalArgumentException> {
            StreamingEvaluator.evaluate(set, plan, rowsOf("ok"))
        }
    }
}
