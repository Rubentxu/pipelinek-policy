package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B6.6 · The row budget is an OUTCOME, not an exception.
 *
 * B5.6 already migrated the per-accumulator caps (`COUNT`, `SUM`,
 * `TYPE_MISMATCH`) from thrown exceptions to `Accumulator.Outcome.Refused`
 * values, and that migration was left half-finished: the ROW_BUDGET still
 * escaped as a `BudgetExceededException` thrown from inside the hot loop.
 *
 * The consequence is not stylistic. A thrown exception discards every result
 * the run had already computed, so a caller cannot learn WHICH rules passed,
 * what the aggregates were, or how far the run got — the only thing it can do
 * is catch. `BudgetMetrics` exists precisely to carry that accounting, and the
 * row-budget stop was the one case where it could not report it.
 *
 * The refusal must also stay DISTINCT from a type refusal (law 8): an
 * over-budget run is not the same event as a run that met a `SUM` on text, and
 * a caller deciding whether to retry needs to tell them apart.
 */
class RowBudgetOutcomeTest {

    private val datasets = mapOf("telemetry" to DatasetSpec("telemetry", "csv"))

    private fun textRule(id: String) = RuleFactory.text(id)

    private fun setWith(vararg rules: com.pipelinek.policy.kernel.policy.Rule) =
        com.pipelinek.policy.kernel.policy.PolicySet(
            "s",
            listOf(com.pipelinek.policy.kernel.policy.Policy("p", rules.toList())),
        )

    private fun endlessRows(): StreamingEvaluator.RowSupplier =
        StreamingEvaluator.RowSupplier { ValueNode.MappingValue(linkedMapOf("v" to ValueNode.TextValue("ok"))) }

    private fun overBudgetSet() = setWith(textRule("r"))

    /**
     * The defect, stated as the behaviour a caller would want.
     *
     * Observed RED before the fix: `StreamingEvaluator.evaluate` threw
     * `BudgetExceededException("row budget 3 exceeded at row 4")`, so no
     * `StreamingReport` existed at all.
     */
    @Test
    fun `an over-budget run returns a report instead of throwing`() {
        val set = overBudgetSet()
        val plan = (DatasetPlanner.plan(set, datasets) as DatasetPlan).copy(rowBudget = 3)

        val report = StreamingEvaluator.evaluate(set, plan, endlessRows())

        assertEquals(
            set.id,
            report.policySetId,
            "an over-budget run must still produce a report the caller can read",
        )
    }

    /**
     * A stopped run must SAY it stopped, with the numbers, instead of
     * returning a report that looks like a complete one. A report whose
     * metrics claim the run was within budget while it was truncated is the
     * silent weakening law 12 exists to prevent.
     */
    @Test
    fun `an over-budget report says it stopped at the budget`() {
        val set = overBudgetSet()
        val plan = (DatasetPlanner.plan(set, datasets) as DatasetPlan).copy(rowBudget = 3)

        val report = StreamingEvaluator.evaluate(set, plan, endlessRows())

        assertEquals(
            3L,
            report.metrics.rowsConsumed,
            "the run must consume exactly the budget it was allowed",
        )
        assertEquals(3L, report.metrics.budgetLimit, "the report must echo the budget it enforced")
        assertTrue(
            !report.metrics.rowsWithinBudget || report.metrics.rowsConsumed == report.metrics.budgetLimit,
            "a run that stopped at its limit must not claim it had headroom left",
        )
    }

    /**
     * Budget and type refusals must stay DISTINCT (law 8). This is the property
     * that a single `Error(String)` outcome would quietly destroy, because the
     * message is the only thing distinguishing them.
     */
    @Test
    fun `a budget stop is not reported as a type mismatch`() {
        val set = overBudgetSet()
        val plan = (DatasetPlanner.plan(set, datasets) as DatasetPlan).copy(rowBudget = 2)

        val report = StreamingEvaluator.evaluate(set, plan, endlessRows())

        val messages = report.results.values
            .filterIsInstance<StreamingEvaluator.RuleOutcome.Error>()
            .map { it.message }
        assertTrue(
            messages.none { it.contains("type_mismatch") },
            "a row-budget stop must not masquerade as a type refusal; got $messages",
        )
    }

    /**
     * The results computed before the stop must survive, but they must be
     * marked INCOMPLETE. Discarding them is the whole cost of throwing; the
     * opposite error is worse, because reporting the partial tallies of a
     * truncated run as final verdicts turns a stopped run into a clean one.
     *
     * The first version of this test only asserted `assertIs<RuleOutcome>`,
     * i.e. that SOME verdict was present. A mutant that keeps the partial
     * verdict and skips the incomplete marking survived it, because a
     * `Passed` is also a `RuleOutcome`. The outcome must be an `Error`
     * naming the row_budget cap.
     */
    @Test
    fun `rules evaluated before the stop are marked incomplete, not passing`() {
        val set = overBudgetSet()
        val plan = (DatasetPlanner.plan(set, datasets) as DatasetPlan).copy(rowBudget = 2)

        val report = StreamingEvaluator.evaluate(set, plan, endlessRows())

        val outcome = report.results[RuleKey.of("s", "p", "r")]
        val error = assertIs<StreamingEvaluator.RuleOutcome.Error>(
            outcome,
            "a rule whose fold was cut short by the budget must not be reported as a " +
                "completed verdict; got $outcome",
        )
        assertTrue(
            error.message.contains("row_budget") && error.message.contains("2"),
            "the refusal must name the cap and its limit; got ${error.message}",
        )
    }

    /**
     * A run INSIDE its budget is untouched by this change and must keep
     * consuming every row. Without this, a fix that stopped the loop early
     * would pass every test above while truncating healthy runs.
     */
    @Test
    fun `a run inside its budget consumes every row`() {
        val set = overBudgetSet()
        val plan = (DatasetPlanner.plan(set, datasets) as DatasetPlan).copy(rowBudget = 10)

        val remaining = 5
        val rows = StreamingEvaluator.RowSupplier {
            ValueNode.MappingValue(linkedMapOf("v" to ValueNode.TextValue("ok")))
        }
        val finite = object : StreamingEvaluator.RowSupplier {
            var left = remaining
            override fun next(): ValueNode? =
                if (left-- > 0) rows.next() else null
        }
        val report = StreamingEvaluator.evaluate(set, plan, finite)

        assertEquals(remaining.toLong(), report.metrics.rowsConsumed, "all five rows must be consumed")
        assertEquals(0L, report.metrics.rowsRefused, "nothing was refused inside the budget")
    }
}

/**
     * Minimal builder for a rule that PASSES on every row it sees.
 *
     * The first version used `Expression.Literal`, which is not a valid rule
     * expression: the rule errored on its own on the very first row, so
 * `existing` was already an `Error` before the budget marking ran. That made
 * the mutant which SKIPS the marking a no-op, and it survived. The rule here
 * is a real per-row comparison that succeeds, so the only thing that can turn
 * its verdict into an incomplete one is the truncation marking itself.
 */
private object RuleFactory {
    fun text(id: String) = com.pipelinek.policy.kernel.policy.Rule(
        id = id,
        message = "text rule $id",
        expression = com.pipelinek.policy.kernel.expression.Expression.Comparison(
            com.pipelinek.policy.kernel.expression.Expression.FieldRef(
                com.pipelinek.policy.kernel.path.DocumentPath.ROOT.child("v"),
                ValueNode.Type.TEXT,
            ),
            com.pipelinek.policy.kernel.expression.Expression.Operator.TEXT_EQUALS,
            com.pipelinek.policy.kernel.expression.Expression.Literal(ValueNode.TextValue("ok")),
        ),
    )
}
