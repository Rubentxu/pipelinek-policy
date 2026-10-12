package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule

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
        // The `datasets` map is part of the planner's public contract. H2.4 will
        // require it: when `requiresGlobalIndex` becomes meaningful, the planner
        // must consult the dataset spec to decide whether to emit InMemoryIndex.
        // Today the GLOBAL execution path is not implemented (law 12) and the
        // planner refuses any GLOBAL-shaped rule before touching this map, so
        // the parameter is intentionally unused. Suppress detekt UnusedParameter
        // until H2.4 lands and the parameter becomes load-bearing.
        @Suppress("UnusedParameter")
        datasets: Map<String, DatasetSpec>,
        rowBudget: Long = DEFAULT_ROW_BUDGET,
        accumulatorCap: Long = DEFAULT_ACCUMULATOR_CAP,
    ): PlanResult {
        val analysis = DatasetShapeAnalyzer.analyze(set)
        val global = analysis.shapesByRule.filterValues { it == DatasetShape.GLOBAL }.keys.toList()
        if (global.isNotEmpty()) {
            return PlanRefusal(
                // RC-S1: GLOBAL is refused unconditionally. `requiresGlobalIndex`
                // is a future seam and not a runtime enabler; promising the
                // opposite would invite the same silent omission RC-S1 exists to
                // close. The plan is honest about why and what to do next.
                reason = "GLOBAL access is not executable in the current kernel (law 12). " +
                    "Reshape the rule as a top-level CollectionPredicate over a DatasetRef, " +
                    "or split it into local predicates.",
                offendingRules = global,
            )
        }
        val aggregateKeys = analysis.shapesByRule.filterValues { it == DatasetShape.AGGREGATE }.keys
        // RC-S1: every AGGREGATE-shaped rule must be in the *executable* subset
        // before any row is pulled. `accumulatorFor` only folds the four
        // accumulator-friendly shapes (top-level CollectionPredicate with a
        // direct DatasetRef and no `appliesWhen`); anything else would either
        // be silently dropped from the report (the bug) or widen the executor
        // surface beyond what was certified. The preconsumo check refuses
        // the unsupported shape with the rule identity so the caller can act
        // on it. No planned rule disappears from the result.
        val unsuportedAggregates = aggregateKeys.filterNot { isExecutableAggregate(it, set) }
        if (unsuportedAggregates.isNotEmpty()) {
            return PlanRefusal(
                reason = "AGGREGATE shape is not executable: only top-level CollectionPredicate " +
                    "over a direct DatasetRef with no appliesWhen is currently foldable. " +
                    "Reshape the rule or move it to a whole-document evaluator.",
                offendingRules = unsuportedAggregates,
            )
        }
        val aggregate = aggregateKeys.toSet()
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

    /**
     * RC-S1 · The executable subset of AGGREGATE.
     *
     * The streaming fold (StreamingEvaluator.accumulatorFor) admits exactly
     * one shape today: a top-level [Expression.CollectionPredicate] whose
     * source is a direct [Expression.DatasetRef] and whose rule has no
     * `appliesWhen` (the gate is not folded). Every other AGGREGATE-shaped
     * rule — a COUNT inside a Comparison, a Not over ANY, a nested source,
     * or an aggregate gate with a non-Literal main — would be silently
     * omitted from the report because the fold branch returns null and
     * `report()` skips it. That silence is the law-12 violation this guard
     * removes.
     *
     * Refusing before any row is pulled keeps the kernel pure and the
     * authority over what is runnable with the planner, which is where the
     * shape was classified to begin with.
     */
    private fun isExecutableAggregate(key: RuleKey, set: PolicySet): Boolean {
        val rule = set.policies.firstOrNull { it.id == key.policyId }
            ?.rules?.firstOrNull { it.id == key.ruleId } ?: return false
        if (rule.appliesWhen != null) return false
        val expr = rule.expression
        if (expr !is Expression.CollectionPredicate) return false
        if (expr.source !is Expression.DatasetRef) return false
        return true
    }
}
