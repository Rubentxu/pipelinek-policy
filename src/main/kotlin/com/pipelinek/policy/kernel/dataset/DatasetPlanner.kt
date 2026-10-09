package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.policy.PolicySet

/**
 * M8 WU-5 · Plan-time refusal. The planner REJECTS what it cannot execute
 * with bounded memory (law 12): GLOBAL-shaped rules without an explicit
 * index plan. No silent weakening.
 */
data class PlanRefusal(
    val reason: String,
    val offendingRules: List<String>,
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
    val localRuleIds: List<String>,
    val aggregateRuleIds: List<String>,
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
        val aggregate = analysis.shapesByRule.filterValues { it == DatasetShape.AGGREGATE }.keys.toList()
        val local = analysis.shapesByRule.filterValues { it == DatasetShape.LOCAL }.keys.toList()

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
            localRuleIds = local,
            aggregateRuleIds = aggregate,
            indexPlan = indexPlan,
            rowBudget = rowBudget,
            accumulatorCap = accumulatorCap,
        )
    }
}
