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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RC-S1 · Aggregate preconsumo refusal.
 *
 * The streaming fold (StreamingEvaluator.accumulatorFor) only executes a
 * top-level [Expression.CollectionPredicate] with a direct
 * [Expression.DatasetRef] and no `appliesWhen`. Every other AGGREGATE-shaped
 * rule was silently dropped from the report because the fold branch returned
 * null and `report()` skipped it (law-12 violation).
 *
 * The fix moves the gate to the planner: every AGGREGATE-shaped rule is
 * checked against the executable subset BEFORE any row is pulled, and the
 * unsupported shape becomes a typed [PlanRefusal] naming the offending
 * [RuleKey]. No planned rule may vanish from the result, and GLOBAL is
 * refused regardless of `requiresGlobalIndex` with a message that does not
 * promise the index plan would unlock it.
 */
class AggregatePlanAdmissionTest {

    private val datasets = mapOf("rows" to DatasetSpec("rows", "csv"))

    private fun aggregateRule(id: String, source: Expression = Expression.DatasetRef("rows")) = Rule(
        id = id,
        message = "$id counts",
        expression = Expression.CollectionPredicate(
            op = Expression.CollectionOp.COUNT,
            source = source,
            predicate = Selector.of(DocumentPath.ROOT),
        ),
    )

    @Test
    fun `top-level COUNT over direct DatasetRef without appliesWhen is admitted`() {
        val set = PolicySet("s", listOf(Policy("p", listOf(aggregateRule("countRows")))))
        val result = DatasetPlanner.plan(set, datasets)
        val plan = assertIs<DatasetPlan>(result)
        assertEquals(setOf(RuleKey.of("s", "p", "countRows")), plan.aggregateRuleKeys)
    }

    @Test
    fun `nested COUNT in Comparison is refused before any row is pulled`() {
        val rule = Rule(
            id = "big",
            message = "big",
            expression = Expression.Comparison(
                Expression.CollectionPredicate(
                    op = Expression.CollectionOp.COUNT,
                    source = Expression.DatasetRef("rows"),
                    predicate = Selector.of(DocumentPath.ROOT),
                ),
                Expression.Operator.GTE,
                Expression.Literal(ValueNode.NumberValue(2)),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val result = DatasetPlanner.plan(set, datasets)
        val refusal = assertIs<PlanRefusal>(result)
        assertTrue(
            refusal.reason.contains("AGGREGATE"),
            "the planner must surface the executable-shape limitation, was: ${refusal.reason}",
        )
        assertEquals(listOf(RuleKey.of("s", "p", "big")), refusal.offendingRules)
    }

    @Test
    fun `negated ANY is refused rather than omitted`() {
        val rule = Rule(
            id = "negAny",
            message = "negAny",
            expression = Expression.Not(
                Expression.CollectionPredicate(
                    op = Expression.CollectionOp.ANY,
                    source = Expression.DatasetRef("rows"),
                    predicate = Selector.of(DocumentPath.ROOT),
                ),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val result = DatasetPlanner.plan(set, datasets)
        val refusal = assertIs<PlanRefusal>(result)
        assertEquals(listOf(RuleKey.of("s", "p", "negAny")), refusal.offendingRules)
    }

    @Test
    fun `aggregate with nested source is refused rather than executed`() {
        val rule = Rule(
            id = "nest",
            message = "nest",
            expression = Expression.CollectionPredicate(
                op = Expression.CollectionOp.COUNT,
                // Nested: a Comparison with a DatasetRef inside, not the
                // direct DatasetRef the executable subset requires.
                source = Expression.Comparison(
                    Expression.DatasetRef("rows"),
                    Expression.Operator.EQ,
                    Expression.DatasetRef("rows"),
                ),
                predicate = Selector.of(DocumentPath.ROOT),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val result = DatasetPlanner.plan(set, datasets)
        val refusal = assertIs<PlanRefusal>(result)
        assertEquals(listOf(RuleKey.of("s", "p", "nest")), refusal.offendingRules)
    }

    @Test
    fun `aggregate appliesWhen is refused rather than executed even with a literal main`() {
        val rule = Rule(
            id = "gate",
            message = "gate",
            appliesWhen = Expression.Literal(ValueNode.BooleanValue(true)),
            expression = Expression.CollectionPredicate(
                op = Expression.CollectionOp.COUNT,
                source = Expression.DatasetRef("rows"),
                predicate = Selector.of(DocumentPath.ROOT),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val result = DatasetPlanner.plan(set, datasets)
        val refusal = assertIs<PlanRefusal>(result)
        assertEquals(listOf(RuleKey.of("s", "p", "gate")), refusal.offendingRules)
    }

    @Test
    fun `GLOBAL is refused without requiresGlobalIndex and the message does not promise index plan unlocks it`() {
        val rule = Rule(
            id = "global",
            message = "global",
            expression = Expression.DatasetRef("rows"),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val result = DatasetPlanner.plan(
            set,
            mapOf("rows" to DatasetSpec("rows", "csv", requiresGlobalIndex = false)),
        )
        val refusal = assertIs<PlanRefusal>(result)
        assertEquals(listOf(RuleKey.of("s", "p", "global")), refusal.offendingRules)
        assertTrue(
            !refusal.reason.contains("requiresGlobalIndex", ignoreCase = true),
            "GLOBAL refusal must not suggest that declaring requiresGlobalIndex unlocks execution; was: ${refusal.reason}",
        )
    }

    @Test
    fun `GLOBAL is refused with requiresGlobalIndex true and the message does not promise index plan unlocks it`() {
        val rule = Rule(
            id = "global",
            message = "global",
            expression = Expression.DatasetRef("rows"),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(rule))))
        val result = DatasetPlanner.plan(
            set,
            mapOf("rows" to DatasetSpec("rows", "csv", requiresGlobalIndex = true)),
        )
        val refusal = assertIs<PlanRefusal>(result)
        assertEquals(listOf(RuleKey.of("s", "p", "global")), refusal.offendingRules)
        assertTrue(
            !refusal.reason.contains("requiresGlobalIndex", ignoreCase = true),
            "GLOBAL refusal must not suggest that declaring requiresGlobalIndex unlocks execution; was: ${refusal.reason}",
        )
    }

    @Test
    fun `no planned rule vanishes from the report for any admitted plan`() {
        // Sanity: a plan with only executable aggregates plans every rule
        // into the report. The streaming run is exercised by the integration
        // path; here we pin that the plan surface itself never drops a
        // planned key.
        val set = PolicySet(
            "s",
            listOf(
                Policy(
                    "p",
                    listOf(
                        aggregateRule("countRows"),
                        Rule(
                            id = "local",
                            message = "local",
                            expression = Expression.Comparison(
                                Expression.FieldRef(DocumentPath.ROOT.child("n"), ValueNode.Type.NUMBER),
                                Expression.Operator.EQ,
                                Expression.Literal(ValueNode.NumberValue(1)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = DatasetPlanner.plan(set, datasets)
        val plan = assertIs<DatasetPlan>(result)
        assertEquals(
            setOf(RuleKey.of("s", "p", "local")),
            plan.localRuleKeys,
        )
        assertEquals(
            setOf(RuleKey.of("s", "p", "countRows")),
            plan.aggregateRuleKeys,
        )
        assertNotNull(plan)
    }
}
