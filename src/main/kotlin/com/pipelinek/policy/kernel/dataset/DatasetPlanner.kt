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
) : DatasetPlanner.PlanResult

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
) : DatasetPlanner.PlanResult

/** Pure planner: PolicySet → DatasetPlan or PlanRefusal. No I/O (law 5). */
object DatasetPlanner {

    /**
     * H2.2 · Closed return type of [DatasetPlanner.plan].
     *
     * Was `Any` with a comment "DatasetPlan | PlanRefusal -- sum type without
     * leaking sealed hierarchies". The leak was the point: every consumer did
     * an unchecked `as DatasetPlan` after an `is PlanRefusal` check, which is
     * exactly the kind of fragile cast the plan calls out.
     *
     * Members:
     *  - [DatasetPlan] -- the plan is executable under bounded memory.
     *  - [PlanRefusal] -- the plan was refused, with the offending rules by identity.
     *
     * No third case. Exhaustive `when` over this type is total.
     */
    sealed interface PlanResult

    const val DEFAULT_ROW_BUDGET: Long = 10_000_000L
    const val DEFAULT_ACCUMULATOR_CAP: Long = 10_000_000L

    fun plan(
        set: PolicySet,
        datasets: Map<String, DatasetSpec>,
        rowBudget: Long = DEFAULT_ROW_BUDGET,
        accumulatorCap: Long = DEFAULT_ACCUMULATOR_CAP,
    ): PlanResult {
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

        // H2.2 · The planner only emits an IndexPlan variant that has an
        // executor. Today the executor for InMemoryIndex does not exist
        // (H2.4 owns GLOBAL), so the planner must NEVER emit it -- otherwise
        // the indexPlan field would be decorative and the plan would carry
        // a value no consumer reads.
        //
        // `requiresGlobalIndex` remains on DatasetSpec as the future seam
        // for H2.4: when an executor lands, the planner will look at
        // `global.isEmpty()` AND `requiresGlobalIndex` to decide whether to
        // emit InMemoryIndex. Until then, the planner's contract is: GLOBAL
        // is refused above, so the only legitimate shape is SinglePass.
        val indexPlan: IndexPlan = IndexPlan.SinglePass
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
