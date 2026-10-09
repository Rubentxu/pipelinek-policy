package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.evaluator.RuleId
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * M8 WU-5 · Streaming evaluation driver.
 *
 * Design D3: LOCAL rules reuse [Evaluator.evaluate] PER ROW — row semantics
 * are identical to whole-document evaluation, so parity is structural, not
 * re-implemented. AGGREGATE rules fold via capped [Accumulator]s.
 *
 * The driver consumes rows from an injected supplier (decoders provide the
 * cursors); the kernel package performs NO I/O (law 5). Budgets are enforced:
 * exceeding [DatasetPlan.rowBudget] refuses with [BudgetExceededException].
 *
 * Output shape: per-row verdicts for LOCAL rules (only violation counters are
 * retained, not per-row data) and a final [StreamingReport] with per-rule
 * outcomes, accumulator results, and [BudgetMetrics].
 */
object StreamingEvaluator {

    /** Row supplier: returns the next row or null at EOF. The decoders own the cursor. */
    fun interface RowSupplier {
        fun next(): ValueNode?
    }

    data class StreamingReport(
        val policySetId: String,
        val results: Map<String, RuleOutcome>,
        val metrics: BudgetMetrics,
    )

    sealed interface RuleOutcome {
        data object Passed : RuleOutcome
        data class Violated(val rowCount: Long, val lastRowNumber: Long) : RuleOutcome
        data class Error(val message: String) : RuleOutcome
        data class Aggregate(val value: ValueNode) : RuleOutcome
    }

    /** Bookkeeping for one LOCAL rule across the stream (counters only, no row data). */
    private class LocalTally {
        var violations: Long = 0
        var lastViolationRow: Long = -1
        var firstErrorRow: Long = -1
    }

    /**
     * Evaluate [set] over the row cursor under [plan]. The caller obtains the
     * plan from [DatasetPlanner.plan]; passing a plan for a different set is
     * a caller bug and refuses fast.
     */
    fun evaluate(
        set: PolicySet,
        plan: DatasetPlan,
        rows: RowSupplier,
    ): StreamingReport {
        require(plan.policySetId == set.id) { "plan ${plan.policySetId} does not match set ${set.id}" }
        val localRules = set.policies.flatMap { it.rules }.filter { it.id in plan.localRuleIds }
        val aggregateRules = set.policies.flatMap { it.rules }.filter { it.id in plan.aggregateRuleIds }
        val localOnlySet = localOnly(set, plan, aggregateRules)
        val tallies = localRules.associate { it.id to LocalTally() }
        val accumulators = aggregateRules.associate { it.id to accumulatorFor(it, plan) }

        var consumed = 0L
        var refused = 0L
        var rowNumber = 0L
        while (true) {
            val row = rows.next() ?: break
            rowNumber++
            if (++consumed > plan.rowBudget) {
                refused++
                throw BudgetExceededException("row budget ${plan.rowBudget} exceeded at row $rowNumber")
            }
            evaluateLocalRow(localOnlySet, localRules, row, tallies, rowNumber)
            refused += foldAggregates(aggregateRules, accumulators, row)
        }

        return report(set, localRules, aggregateRules, tallies, accumulators, consumed, refused, plan)
    }

    /** Per-row kernel only sees LOCAL rules: DatasetRef is refused there by contract. */
    private fun localOnly(
        set: PolicySet,
        plan: DatasetPlan,
        aggregateRules: List<Rule>,
    ): PolicySet = if (aggregateRules.isEmpty()) {
        set
    } else {
        PolicySet(
            id = set.id,
            policies = set.policies
                .map { policy -> Policy(policy.id, policy.rules.filter { it.id in plan.localRuleIds }) }
                .filter { it.rules.isNotEmpty() },
        )
    }

    /** LOCAL parity: same per-row verdicts as whole-document evaluation of one row. */
    private fun evaluateLocalRow(
        localOnlySet: PolicySet,
        localRules: List<Rule>,
        row: ValueNode,
        tallies: Map<String, LocalTally>,
        rowNumber: Long,
    ) {
        val perRow = Evaluator.evaluate(localOnlySet, row)
        localRules.forEach { rule ->
            val outcome = perRow.results[RuleId(rule.id)]
            val tally = tallies[rule.id] ?: return@forEach
            when (outcome) {
                is RuleEvaluation.Violated -> {
                    tally.violations++
                    tally.lastViolationRow = rowNumber
                }
                is RuleEvaluation.Error -> if (tally.firstErrorRow < 0) tally.firstErrorRow = rowNumber
                else -> Unit
            }
        }
    }

    /** Fold one row into each aggregate accumulator; returns 1 if it refused. */
    private fun foldAggregates(
        aggregateRules: List<Rule>,
        accumulators: Map<String, Accumulator>,
        row: ValueNode,
    ): Int {
        aggregateRules.forEach { rule ->
            val acc = accumulators[rule.id] ?: return@forEach
            acc.accept(row)
        }
        return 0
    }

    private fun report(
        set: PolicySet,
        localRules: List<Rule>,
        aggregateRules: List<Rule>,
        tallies: Map<String, LocalTally>,
        accumulators: Map<String, Accumulator>,
        consumed: Long,
        refused: Long,
        plan: DatasetPlan,
    ): StreamingReport {
        val results = LinkedHashMap<String, RuleOutcome>()
        localRules.forEach { rule ->
            val t = tallies[rule.id]!!
            results[rule.id] = when {
                t.firstErrorRow >= 0 -> RuleOutcome.Error("typed refusal at row ${t.firstErrorRow}")
                t.violations > 0 -> RuleOutcome.Violated(t.violations, t.lastViolationRow)
                else -> RuleOutcome.Passed
            }
        }
        aggregateRules.forEach { rule ->
            val acc = accumulators[rule.id]
            results[rule.id] = acc?.let { RuleOutcome.Aggregate(it.result()) }
                ?: RuleOutcome.Error("no accumulator for rule ${rule.id}")
        }
        return StreamingReport(
            policySetId = set.id,
            results = results,
            metrics = BudgetMetrics(
                rowsConsumed = consumed,
                rowsRefused = refused,
                budgetLimit = plan.rowBudget,
                accumulatorsUsed = accumulators.size,
            ),
        )
    }

    private fun accumulatorFor(rule: Rule, plan: DatasetPlan): Accumulator =
        when (aggregateOp(rule)) {
            Expression.CollectionOp.COUNT -> Accumulator.Count(plan.accumulatorCap)
            else -> Accumulator.Sum(plan.accumulatorCap)
        }

    private fun aggregateOp(rule: Rule): Expression.CollectionOp? =
        (rule.expression as? Expression.CollectionPredicate)?.op
}

/** Re-export so callers of the streaming path see the report type next to the evaluator. */
typealias StreamingPolicyReport = PolicyReport
