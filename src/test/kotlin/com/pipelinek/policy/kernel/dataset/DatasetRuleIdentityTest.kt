package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * B5.5 · Rule identity in the dataset path.
 *
 * The streaming path keyed its tally, accumulator and report maps by bare
 * `rule.id`. A `ruleId` is only unique WITHIN its policy, so two policies in
 * the same set carrying the same `ruleId` collided: they shared one tally and
 * one report entry, and one of the two verdicts vanished from the report
 * without any refusal or error.
 *
 * The whole-document evaluator already keys by
 * [RuleKey.of(policySetId, policyId, ruleId)] (B1.1). These tests pin the
 * streaming path to the same identity so the two routes cannot diverge.
 */
class DatasetRuleIdentityTest {

    private val datasets = mapOf("telemetry" to DatasetSpec("telemetry", "csv"))

    private fun textRule(id: String, expected: String) = Rule(
        id = id,
        message = "$id expects $expected",
        expression = Expression.Comparison(
            Expression.FieldRef(DocumentPath.ROOT.child("temp"), ValueNode.Type.TEXT),
            Expression.Operator.TEXT_EQUALS,
            Expression.Literal(ValueNode.TextValue(expected)),
        ),
    )

    private fun rowsOf(vararg temps: String): StreamingEvaluator.RowSupplier {
        val iter = temps.map { ValueNode.MappingValue(linkedMapOf("temp" to ValueNode.TextValue(it))) }.iterator()
        return StreamingEvaluator.RowSupplier { if (iter.hasNext()) iter.next() else null }
    }

    /**
     * Two POLICIES, each with a rule whose id is the same string but which
     * expects a different value. They are genuinely different rules: only
     * `policyA/shared` should pass on a "ok" row.
     */
    private fun collidingSet() = PolicySet(
        "set-1",
        listOf(
            Policy("policyA", listOf(textRule("shared", "ok"))),
            Policy("policyB", listOf(textRule("shared", "bad"))),
        ),
    )

    @Test
    fun `01a a ruleId shared by two policies yields TWO distinct results`() {
        val set = collidingSet()
        val plan = DatasetPlanner.plan(set, datasets) as DatasetPlan
        val report = StreamingEvaluator.evaluate(set, plan, rowsOf("ok", "bad"))

        // Two rules, same id string, different policies. Both must survive as
        // distinct entries keyed by RuleKey. Today the map is keyed by bare
        // `rule.id`, so this does not even compile against the current type —
        // which is the point: the defect is in the public contract.
        val keyA = RuleKey.of("set-1", "policyA", "shared")
        val keyB = RuleKey.of("set-1", "policyB", "shared")
        val results: Map<RuleKey, StreamingEvaluator.RuleOutcome> = report.results
        assertEquals(2, results.size, "results must hold one entry per rule, not per id string")
        assertNotNull(results[keyA], "policyA/shared missing from the report")
        assertNotNull(results[keyB], "policyB/shared missing from the report")
    }

    @Test
    fun `01b colliding ruleIds are keyed by RuleKey not by bare id`() {
        val set = collidingSet()
        val plan = DatasetPlanner.plan(set, datasets) as DatasetPlan
        val report = StreamingEvaluator.evaluate(set, plan, rowsOf("ok", "ok", "bad"))

        // Rows are ["ok", "ok", "bad"]. policyA/shared expects "ok" and violates
        // exactly once, on the "bad" row. policyB/shared expects "bad" and
        // violates twice, on the two "ok" rows.
        //
        // The DIFFERENT counts are the point. Before the fix both rules shared
        // one tally keyed "shared", so both report entries showed the same
        // merged count and neither number could be attributed to its rule.
        val a = report.results.getValue(RuleKey.of("set-1", "policyA", "shared"))
        val b = report.results.getValue(RuleKey.of("set-1", "policyB", "shared"))
        val violatedA = a as? StreamingEvaluator.RuleOutcome.Violated
            ?: throw AssertionError("policyA/shared must be Violated, was $a")
        val violatedB = b as? StreamingEvaluator.RuleOutcome.Violated
            ?: throw AssertionError("policyB/shared must be Violated, was $b")
        assertEquals(1L, violatedA.rowCount, "policyA/shared must count only the 'bad' row")
        assertEquals(2L, violatedB.rowCount, "policyB/shared must count both 'ok' rows")
    }

    @Test
    fun `01c streaming report keys match the whole-document evaluator keys`() {
        // Route parity on IDENTITY, not just on values: the same rule must be
        // addressable by the same RuleKey in both routes. This is the assertion
        // that fails if one path reverts to bare rule.id.
        val set = collidingSet()
        val plan = DatasetPlanner.plan(set, datasets) as DatasetPlan
        val report = StreamingEvaluator.evaluate(set, plan, rowsOf("ok", "bad"))

        val rows = listOf("ok", "bad").map { ValueNode.MappingValue(linkedMapOf("temp" to ValueNode.TextValue(it))) }
        val whole = Evaluator.evaluate(set, ValueNode.SequenceValue(rows))

        val streamingKeys = report.results.keys.toSet()
        val wholeKeys = whole.results.keys.filter { it.ruleId == "shared" }.toSet()
        assertEquals(wholeKeys, streamingKeys, "streaming and whole-document must expose the same RuleKeys")
    }
}
