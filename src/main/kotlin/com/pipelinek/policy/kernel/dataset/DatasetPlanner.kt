package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.policy.PolicySet

/**
 * M8 WU-5 · Plan-time refusal. The planner REJECTS what it cannot execute
 * with bounded memory (law 12): GLOBAL-shaped rules without an explicit
 * index plan. No silent weakening.
 */
data class PlanRefusal(
    val reason: String,
    /**
     * H2.1 · The offending rules by identity, not by name.
     *
     * This was `List<String>` of rule ids, so a refusal could not say WHICH rule
     * offended when two policies shared a name, and the CLI printed the bare id
     * with no way for an author to act on it. [RuleKey] names the policy too.
     */
    val offendingRules: List<RuleKey>,
)

/** Whether an aggregate over a dataset needs an index or a plain pass. */
sealed interface IndexPlan {
    /** Single-pass fold over the row cursor; no index materialized. */
    data object SinglePass : IndexPlan

    /**
     * Explicit in-memory index over one column, built once during the pass
     * (the planner only emits this when the policy declared the dataset
     * with `requiresGlobalIndex = true` AND the access shape is indexable).
     */
    data class InMemoryIndex(val dataset: String, val column: String) : IndexPlan
}

/**
 * Executable plan produced by [DatasetPlanner]: LOCAL rules stream row by
 * row through the per-row kernel evaluator; AGGREGATE rules fold via
 * capped accumulators under the declared [IndexPlan].
 */
data class DatasetPlan(
    val policySetId: String,
    /**
     * H2.1 · Keys, not ids.
     *
     * These were `List<String>` of bare rule ids, and rule ids are unique only
     * within a policy, so two policies could each declare `check` and the plan
     * could not say which one it meant. Keyed by [RuleKey], identity survives
     * the whole evaluation and the two classification lists can no longer
     * overlap by accident.
     */
    val localRuleKeys: Set<RuleKey>,
    val aggregateRuleKeys: Set<RuleKey>,
    val indexPlan: IndexPlan,
    val rowBudget: Long,
    val accumulatorCap: Long,
)

/** Pure planner: PolicySet → DatasetPlan or PlanRefusal. No I/O (law 5). */
object DatasetPlanner {

    const val DEFAULT_ROW_BUDGET: Long = 10_000_000L
    const val DEFAULT_ACCUMULATOR_CAP: Long = 10_000_000L

    fun plan(
        set: PolicySet,
        datasets: Map<String, DatasetSpec>,
        rowBudget: Long = DEFAULT_ROW_BUDGET,
        accumulatorCap: Long = DEFAULT_ACCUMULATOR_CAP,
    ): Any { // DatasetPlan | PlanRefusal — sum type without leaking sealed hierarchies
        val analysis = DatasetShapeAnalyzer.analyze(set)
        val global = analysis.shapesByRule.filterValues { it == DatasetShape.GLOBAL }.keys.toList()
        if (global.isNotEmpty()) {
            return PlanRefusal(
                reason = "GLOBAL access requires an index plan; the kernel refuses unbounded " +
                    "cross-row evaluation (law 12). Declare requiresGlobalIndex or reshape the rule.",
                offendingRules = global,
            )
        }
        val aggregate = analysis.shapesByRule.filterValues { it == DatasetShape.AGGREGATE }.keys.toSet()
        val local = analysis.shapesByRule.filterValues { it == DatasetShape.LOCAL }.keys.toSet()

        // Aggregates are single-pass folds; an index is only materialized when
        // a dataset explicitly declares requiresGlobalIndex (the GLOBAL access
        // itself was already refused above, so this stays bounded).
        val indexPlan: IndexPlan =
            if (datasets.values.any { it.requiresGlobalIndex }) {
                val entry = datasets.entries.first { it.value.requiresGlobalIndex }
                IndexPlan.InMemoryIndex(entry.key, column = "id")
            } else {
                IndexPlan.SinglePass
            }
        return DatasetPlan(
            policySetId = set.id,
            localRuleKeys = local,
            aggregateRuleKeys = aggregate,
            indexPlan = indexPlan,
            rowBudget = rowBudget,
            accumulatorCap = accumulatorCap,
        )
    }
}
