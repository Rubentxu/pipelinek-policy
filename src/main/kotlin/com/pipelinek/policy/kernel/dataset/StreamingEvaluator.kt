package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.evaluator.RuleId
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
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
 * Output shape: per-row verdicts for LOCAL rules (streamed, not retained per
 * row beyond violations) and a final [StreamingReport] with per-rule
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

        var consumed = 0L
        var refused = 0L
        val violatedRows = mutableMapOf<String, Pair<Long, Long>>() // ruleId -> (count, lastRow)
        val accumulators = aggregateRules.associate { rule ->
            rule.id to accumulatorFor(rule, plan)
        }

        // Per-row kernel only sees LOCAL rules: aggregate expressions carry
        // DatasetRef, which the per-row kernel refuses by contract (D3).
        val localOnlySet = if (aggregateRules.isEmpty()) set else PolicySet(
            id = set.id,
            policies = set.policies.map { policy ->
                Policy(policy.id, policy.rules.filter { it.id in plan.localRuleIds })
            }.filter { it.rules.isNotEmpty() },
        )

        // --- Single pass: LOCAL via per-row kernel, AGGREGATE via fold ---
        var rowNumber = 0L
        while (true) {
            val row = try {
                rows.next() ?: break
            } catch (budget: BudgetExceededException) {
                refused++
                throw budget
            }
            rowNumber++
            if (++consumed > plan.rowBudget) {
                refused++
                throw BudgetExceededException("row budget ${plan.rowBudget} exceeded at row $rowNumber")
            }

            // LOCAL parity: run the LOCAL-only set per row exactly like
            // whole-document evaluation of a single-row document.
            val perRow = Evaluator.evaluate(localOnlySet, row)
            localRules.forEach { rule ->
                val outcome = perRow.results[RuleId(rule.id)]
                if (outcome is RuleEvaluation.Violated) {
                    val prev = violatedRows[rule.id]
                    violatedRows[rule.id] = (prev?.first ?: 0L) + 1L to rowNumber
                } else if (outcome is RuleEvaluation.Error) {
                    // Surface the first typed refusal per rule.
                    if (!violatedRows.containsKey("!${rule.id}")) {
                        violatedRows["!${rule.id}"] = 0L to rowNumber
                    }
                }
            }

            // AGGREGATE fold.
            aggregateRules.forEach { rule ->
                val acc = accumulators[rule.id] ?: return@forEach
                val contribution = aggregateContribution(rule, row)
                    ?: return@forEach // SELECT-style filter did not match this row
                try {
                    acc.accept(contribution)
                } catch (budget: BudgetExceededException) {
                    refused++
                    throw budget
                } catch (mismatch: SumTypeMismatchException) {
                    throw mismatch
                }
            }
        }

        // --- Materialize outcomes ---
        val results = LinkedHashMap<String, RuleOutcome>()
        localRules.forEach { rule ->
            val err = violatedRows["!${rule.id}"]
            when {
                err != null -> results[rule.id] = RuleOutcome.Error("typed refusal at row ${err.second}")
                violatedRows.containsKey(rule.id) -> {
                    val (count, last) = violatedRows[rule.id]!!
                    results[rule.id] = RuleOutcome.Violated(count, last)
                }
                else -> results[rule.id] = RuleOutcome.Passed
            }
        }
        aggregateRules.forEach { rule ->
            val acc = accumulators[rule.id]
            results[rule.id] = if (acc == null) {
                RuleOutcome.Error("no accumulator for rule ${rule.id}")
            } else {
                RuleOutcome.Aggregate(acc.result())
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

    private fun accumulatorFor(
        rule: com.pipelinek.policy.kernel.policy.Rule,
        plan: DatasetPlan,
    ): Accumulator = when (aggregateOp(rule)) {
        com.pipelinek.policy.kernel.expression.Expression.CollectionOp.COUNT ->
            Accumulator.Count(plan.accumulatorCap)
        else -> Accumulator.Sum(plan.accumulatorCap)
    }

    private fun aggregateOp(rule: com.pipelinek.policy.kernel.policy.Rule): com.pipelinek.policy.kernel.expression.Expression.CollectionOp? =
        (rule.expression as? com.pipelinek.policy.kernel.expression.Expression.CollectionPredicate)?.op

    /**
     * Row contribution for the aggregate: for COUNT/ALL/ANY/NONE the row
     * itself is folded when it exists (COUNT semantics: every row counts,
     * selector-level filtering arrives with the planner's SELECT support).
     */
    private fun aggregateContribution(
        rule: com.pipelinek.policy.kernel.policy.Rule,
        row: ValueNode,
    ): ValueNode = row
}

/** Re-export so callers of the streaming path see the report type next to the evaluator. */
typealias StreamingPolicyReport = PolicyReport
