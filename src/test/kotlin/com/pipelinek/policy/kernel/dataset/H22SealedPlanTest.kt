package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * H2.2 · The planner's return must be a closed type, and the planner must
 * only emit executable plans.
 *
 * Before the fix:
 *  - `DatasetPlanner.plan(...)` returns `Any`, so consumers do unchecked
 *    `as DatasetPlan` casts. The plan calls this out as H2.2.
 *  - `IndexPlan.InMemoryIndex` is emitted when a dataset has
 *    `requiresGlobalIndex = true`, but `StreamingEvaluator` never consults
 *    the `indexPlan` field; the value is decorative and the field is dead.
 *
 * These tests will not compile against the current code because the sealed
 * return type [DatasetPlanner.PlanResult] does not exist yet. Compile failure
 * here IS the RED for this defect class.
 */
class H22SealedPlanTest {

    private val datasets = mapOf("telemetry" to DatasetSpec("telemetry", "csv"))
    private val datasetsWithIndex =
        mapOf("telemetry" to DatasetSpec("telemetry", "csv", requiresGlobalIndex = true))

    private fun localOnlyRule(id: String) = Rule(
        id = id,
        message = "$id must be present",
        expression = Expression.Comparison(
            Expression.FieldRef(DocumentPath.ROOT.child("n"), ValueNode.Type.NUMBER),
            Expression.Operator.EQ,
            Expression.Literal(ValueNode.NumberValue(1)),
        ),
    )

    private fun aggregateRule(id: String) = Rule(
        id = id,
        message = "$id counts",
        expression = Expression.CollectionPredicate(
            op = Expression.CollectionOp.COUNT,
            source = Expression.DatasetRef("telemetry"),
            predicate = Selector.of(DocumentPath.ROOT),
        ),
    )

    @Test
    fun `plan return is a closed type -- not Any`() {
        val set = PolicySet("s", listOf(Policy("p", listOf(localOnlyRule("a")))))
        val result: DatasetPlanner.PlanResult = DatasetPlanner.plan(set, datasets)
        val plan: DatasetPlan = assertIs<DatasetPlan>(result)
        assertEquals(setOf(RuleKey.of("s", "p", "a")), plan.localRuleKeys)
    }

    @Test
    fun `refusal is part of the same closed type`() {
        // Documented refusal entry: a rule that the analyzer classifies GLOBAL
        // currently refuses. Force it by giving the analyzer no dataset path
        // for a cross-row expression; the planner must hand back a Refusal
        // wrapped in the sealed type, not a bare PlanRefusal sibling.
        // Documented refusal entry: a free-floating Expression.DatasetRef
        // is classified GLOBAL by the analyzer (DatasetShape.kt:117), and the
        // planner refuses it (the kernel has no bounded GLOBAL executor yet).
        val set = PolicySet(
            "s",
            listOf(
                Policy(
                    "p",
                    listOf(
                        Rule(
                            id = "cross",
                            message = "cross",
                            expression = Expression.DatasetRef("telemetry"),
                        ),
                    ),
                ),
            ),
        )
        val result: DatasetPlanner.PlanResult = DatasetPlanner.plan(set, datasets)
        // Acceptance criterion: the result is a *member* of the sealed return
        // type, not a sibling class. Exhaustive `when` covers both branches;
        // if a third case ever appears the test stops compiling.
        val outcome = when (result) {
            is DatasetPlan -> "plan"
            is PlanRefusal -> "refusal"
        }
        assertEquals(
            "refusal",
            outcome,
            "the analyzer must classify the free DatasetRef as GLOBAL and the planner must refuse it",
        )
    }

    @Test
    fun `planner does not emit InMemoryIndex unless a real GLOBAL execution path exists`() {
        // requiresGlobalIndex is meant to unlock GLOBAL rules. There is no
        // GLOBAL execution path implemented today (H2.4's job). So the
        // planner must NOT emit InMemoryIndex, because nobody consumes it and
        // the field is dead weight.
        val set = PolicySet("s", listOf(Policy("p", listOf(localOnlyRule("a")))))
        val result = DatasetPlanner.plan(set, datasetsWithIndex)
        val plan = assertIs<DatasetPlan>(result)
        assertEquals(
            IndexPlan.SinglePass,
            plan.indexPlan,
            "planner must not emit a decorative InMemoryIndex; the H2.4 GLOBAL path is not implemented",
        )
    }
}
