package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.evaluator.RuleKey
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
        // B5.5: keyed by RuleKey (policySetId, policyId, ruleId), matching the
        // whole-document evaluator. A `ruleId` is unique only WITHIN its policy,
        // so keying by the bare string silently dropped one of two same-named
        // rules from the report with no refusal.
        val results: Map<RuleKey, RuleOutcome>,
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
        // B5.5: flatten WHILE KEEPING the owning policy id. The previous
        // `flatMap { it.rules }` threw the policy identity away, so every later
        // map keyed by bare rule.id could not tell two same-named rules apart.
        val localRules = rulesByKey(set).filter { (_, _, rule) -> rule.id in plan.localRuleIds }
        val aggregateRules = rulesByKey(set).filter { (_, _, rule) -> rule.id in plan.aggregateRuleIds }
        val localOnlySet = localOnly(set, plan, aggregateRules.map { it.third })
        val tallies = localRules.associate { (key, _, _) -> key to LocalTally() }
        val accumulators = aggregateRules.associate { (key, _, rule) -> key to accumulatorFor(rule, plan) }

        var consumed = 0L
        var refused = 0L
        var rowNumber = 0L
        // B5.6: per-run, never object state — law 10 forbids a global or
        // cross-run mutable registry, and a leaked map would leak refusals
        // between evaluations.
        val aggregateRefusals = mutableMapOf<RuleKey, Accumulator.Outcome.Refused>()
        while (true) {
            val row = rows.next() ?: break
            rowNumber++
            if (++consumed > plan.rowBudget) {
                refused++
                throw BudgetExceededException("row budget ${plan.rowBudget} exceeded at row $rowNumber")
            }
            evaluateLocalRow(localOnlySet, row, tallies, rowNumber)
            refused += foldAggregates(aggregateRules, accumulators, row, aggregateRefusals)
        }

        return report(
            set,
            localRules,
            aggregateRules,
            tallies,
            accumulators,
            consumed,
            refused,
            plan,
            aggregateRefusals,
        )
    }

    /**
     * B5.5: every rule in [set] paired with its owning policy id and its full
     * [RuleKey]. Callers filter on the triple so identity survives the whole
     * evaluation.
     */
    private fun rulesByKey(set: PolicySet): List<Triple<RuleKey, String, Rule>> =
        set.policies.flatMap { policy ->
            policy.rules.map { rule -> Triple(RuleKey.of(set.id, policy.id, rule.id), policy.id, rule) }
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
        row: ValueNode,
        tallies: Map<RuleKey, LocalTally>,
        rowNumber: Long,
    ) {
        val perRow = Evaluator.evaluate(localOnlySet, row)
        // B1.1: report keys are (policyId, ruleId); walk policies so each
        // lookup carries its own policy identity.
        localOnlySet.policies.forEach { policy ->
            policy.rules.forEach { rule ->
                val key = RuleKey.of(localOnlySet.id, policy.id, rule.id)
                val outcome = perRow.results[key]
                val tally = tallies[key] ?: return@forEach
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
    }

    /**
     * Fold one row into each aggregate accumulator; returns the count of
     * accumulators that REFUSED the row.
     *
     * B5.6: a refusal is now a value, so the caller can record which rule
     * refused and why. The old code returned 0 unconditionally and let an
     * exception escape from inside the fold.
     */
    private fun foldAggregates(
        aggregateRules: List<Triple<RuleKey, String, Rule>>,
        accumulators: Map<RuleKey, Accumulator>,
        row: ValueNode,
        aggregateRefusals: MutableMap<RuleKey, Accumulator.Outcome.Refused>,
    ): Int {
        var refused = 0
        aggregateRules.forEach { (key, _, _) ->
            val acc = accumulators[key] ?: return@forEach
            val outcome = acc.accept(row)
            if (outcome is Accumulator.Outcome.Refused) {
                refused++
                aggregateRefusals[key] = outcome
            }
        }
        return refused
    }

    private fun report(
        set: PolicySet,
        localRules: List<Triple<RuleKey, String, Rule>>,
        aggregateRules: List<Triple<RuleKey, String, Rule>>,
        tallies: Map<RuleKey, LocalTally>,
        accumulators: Map<RuleKey, Accumulator>,
        consumed: Long,
        refused: Long,
        plan: DatasetPlan,
        aggregateRefusals: Map<RuleKey, Accumulator.Outcome.Refused>,
    ): StreamingReport {
        val results = LinkedHashMap<RuleKey, RuleOutcome>()
        localRules.forEach { (key, _, _) ->
            val t = tallies[key]!!
            results[key] = when {
                t.firstErrorRow >= 0 -> RuleOutcome.Error("typed refusal at row ${t.firstErrorRow}")
                t.violations > 0 -> RuleOutcome.Violated(t.violations, t.lastViolationRow)
                else -> RuleOutcome.Passed
            }
        }
        aggregateRules.forEach { (key, _, _) ->
            val refusal = aggregateRefusals[key]
            results[key] = when {
                // B5.6: a refusal names the cap and the numbers. The previous
                // code reported a bare Aggregate over a silently truncated
                // fold, so a budget stop looked like a successful aggregate.
                refusal != null -> RuleOutcome.Error(
                    "${refusal.cap.name.lowercase()} cap ${refusal.limit} reached after ${refusal.consumed}",
                )
                else -> accumulators[key]?.let { RuleOutcome.Aggregate(it.result()) }
                    ?: RuleOutcome.Error("no accumulator for rule ${key.ruleId}")
            }
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
