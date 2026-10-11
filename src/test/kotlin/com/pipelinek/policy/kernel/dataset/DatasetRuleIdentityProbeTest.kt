package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * H2.1 · A plan must identify rules by RuleKey, not by bare text.
 *
 * `DatasetPlan` carries `localRuleIds: List<String>` and both consumers test
 * membership with `rule.id in plan.localRuleIds`. Rule ids are only unique WITHIN
 * a policy, so two policies may each declare a rule called `check` and the plan
 * cannot tell them apart.
 *
 * The consequence is not cosmetic. `rulesByKey(...).filter { rule.id in
 * plan.localRuleIds }` keeps the AGGREGATE `check` as well, so it is folded as a
 * LOCAL rule per row; and the `localOnlySet` built for the per-row evaluator
 * retains the other policy's LOCAL `check` only if the filter happens to let it
 * through, which it does not -- so the same id silently drops or duplicates
 * work depending on which policy declared it first.
 *
 * Fixtures must therefore use DISTINCT ids for the probe to be meaningful: two
 * rules named `check` in two policies.
 */
class DatasetRuleIdentityProbeTest {

    private val datasets = mapOf("telemetry" to DatasetSpec("telemetry", "csv"))

    /**
     * One row, then EOF.
     *
     * The first version returned the same row forever, which is fine for a LOCAL
     * rule but makes an UNFOLDED aggregate look like it ran to the budget: with
     * the pre-fix classification the run drained all ten million rows and
     * reported `row_budget cap 10000000 reached after 10000000`. That message is
     * a symptom of the misclassification, not of an infinite stream, and it is
     * the evidence that the defect was live -- but it also means the fixture has
     * to terminate so the assertion measures classification rather than budget.
     */
    private fun row(n: ValueNode): StreamingEvaluator.RowSupplier {
        val sent = booleanArrayOf(false)
        return StreamingEvaluator.RowSupplier {
            if (sent[0]) null else {
                sent[0] = true
                ValueNode.MappingValue(linkedMapOf("n" to n))
            }
        }
    }

    private fun localRule(id: String) = Rule(
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
        message = "$id counts rows",
        expression = Expression.CollectionPredicate(
            op = Expression.CollectionOp.COUNT,
            source = Expression.DatasetRef("telemetry"),
            predicate = Selector.of(DocumentPath.ROOT),
        ),
    )

    @Test
    fun `a rule id shared by a LOCAL and an AGGREGATE rule must not collide`() {
        // Two policies, each declaring a rule named "check". One is LOCAL, one is
        // AGGREGATE. The plan splits by id, so both classify as the same rule.
        val set = PolicySet(
            "s",
            listOf(
                Policy("alpha", listOf(localRule("check"))),
                Policy("beta", listOf(aggregateRule("check"))),
            ),
        )

        val plan = DatasetPlanner.plan(set, datasets) as DatasetPlan
        val report = StreamingEvaluator.evaluate(set, plan, row(ValueNode.NumberValue(1)))

        val localKey = RuleKey.of("s", "alpha", "check")
        val aggregateKey = RuleKey.of("s", "beta", "check")

        // The AGGREGATE rule must be folded, not evaluated per row. If the plan
        // classifies by bare id, both keys land in `localRuleIds` and the
        // aggregate is reported as a LOCAL verdict.
        assertTrue(
            report.results[aggregateKey] is StreamingEvaluator.RuleOutcome.Aggregate,
            "the AGGREGATE rule was not folded; got ${report.results[aggregateKey]} " +
                "(plan.localRuleKeys=${plan.localRuleKeys}, aggregateRuleKeys=${plan.aggregateRuleKeys})",
        )
        assertTrue(
            report.results[localKey] is StreamingEvaluator.RuleOutcome.Passed,
            "the LOCAL rule should have passed; got ${report.results[localKey]}",
        )
    }

    /**
     * The other half, and the reason this is a defect rather than a limitation.
     *
     * Before the fix, `Map&lt;String, DatasetShape&gt;` meant the rule declared LAST
     * won: whichever `check` came later overwrote the earlier shape. Declaring the
     * aggregate second planned it as LOCAL; declaring it first planned it as
     * AGGREGATE. So the same policy set produced two different plans purely from
     * declaration order, and an author had no way to tell which one they got.
     *
     * Asserting only one direction would miss that entirely, so both orders run
     * and their plans must agree.
     */
    @Test
    fun `declaration order must not change the plan`() {
        val local = localRule("check")
        val aggregate = aggregateRule("check")

        fun planOf(policies: List<Policy>): DatasetPlan =
            DatasetPlanner.plan(PolicySet("s", policies), datasets) as DatasetPlan

        val aggregateSecond = planOf(listOf(Policy("alpha", listOf(local)), Policy("beta", listOf(aggregate))))
        val aggregateFirst = planOf(listOf(Policy("beta", listOf(aggregate)), Policy("alpha", listOf(local))))

        assertEquals(
            aggregateFirst.localRuleKeys,
            aggregateSecond.localRuleKeys,
            "local classification depends on order",
        )
        assertEquals(
            aggregateFirst.aggregateRuleKeys,
            aggregateSecond.aggregateRuleKeys,
            "aggregate classification depends on order",
        )
        assertEquals(setOf(RuleKey.of("s", "alpha", "check")), aggregateSecond.localRuleKeys)
        assertEquals(setOf(RuleKey.of("s", "beta", "check")), aggregateSecond.aggregateRuleKeys)
    }

    /**
     * The consumption side, and the fixture that R2 actually needs.
     *
     * `localOnly` rebuilds the per-row policy set by testing each rule key for
     * membership in the plan's LOCAL set. That whole branch is skipped when the
     * plan holds no AGGREGATE rules, so a fixture with only LOCAL rules never
     * executes it: the first attempt at mutant R2 mutated that line and the
     * whole probe class stayed green, because the mutant was unreachable rather
     * than because the test was wrong.
     *
     * So the fixture must mix shapes -- a same-named LOCAL rule in one policy
     * and a same-named AGGREGATE rule in another. That forces the AGGREGATE
     * policy out of the per-row set while keeping both LOCAL `check` rules in
     * it. Collapsing the key to the bare rule id then drops the LOCAL rule whose
     * policy name does not match, which is the observable difference.
     */
    @Test
    fun `a same-named LOCAL rule survives alongside a same-named AGGREGATE rule`() {
        val set = PolicySet(
            "s",
            listOf(
                Policy("alpha", listOf(localRule("check"))),
                Policy("beta", listOf(aggregateRule("check"))),
            ),
        )

        val plan = DatasetPlanner.plan(set, datasets) as DatasetPlan
        val report = StreamingEvaluator.evaluate(set, plan, row(ValueNode.NumberValue(1)))

        val localKey = RuleKey.of("s", "alpha", "check")
        assertEquals(setOf(localKey), plan.localRuleKeys, "only the LOCAL rule belongs to the per-row set")
        assertEquals(
            setOf(RuleKey.of("s", "beta", "check")),
            plan.aggregateRuleKeys,
            "the AGGREGATE rule must not be evaluated per row",
        )
        assertTrue(
            report.results.containsKey(localKey),
            "the LOCAL rule must still be evaluated per row; got ${report.results.keys}",
        )
    }

    /**
     * The observable consequence, which is what R2 finally detects.
     *
     * `report()` walks the plan's LOCAL keys and reads a tally per key, so a rule
     * that is dropped from the per-row subset still gets an entry -- it just
     * never accumulates anything and is reported Passed forever. Asserting only
     * `containsKey` therefore passes even when the LOCAL rule is not evaluated
     * at all, which is why the first two versions of this mutant survived.
     *
     * Feeding a row that VIOLATES the rule is what separates the two cases: a
     * genuinely evaluated rule reports Violated, while a rule that fell out of
     * the per-row set reports Passed on the same row. Asserting the verdict, not
     * merely its presence, is the check that bites.
     */
    @Test
    fun `a same-named LOCAL rule still counts its violations next to a same-named AGGREGATE rule`() {
        val set = PolicySet(
            "s",
            listOf(
                Policy("alpha", listOf(localRule("check"))),
                Policy("beta", listOf(aggregateRule("check"))),
            ),
        )

        // The rule requires n == 1, so a row of 2 violates it.
        val plan = DatasetPlanner.plan(set, datasets) as DatasetPlan
        val report = StreamingEvaluator.evaluate(set, plan, row(ValueNode.NumberValue(2)))

        val localKey = RuleKey.of("s", "alpha", "check")
        assertTrue(
            report.results[localKey] is StreamingEvaluator.RuleOutcome.Violated,
            "the LOCAL rule must be evaluated per row and report its violation; " +
                "got ${report.results[localKey]} (a Passed here means the rule was " +
                "silently dropped from the per-row subset rather than evaluated)",
        )
    }

    /**
     * Two LOCAL rules sharing a name must BOTH be evaluated.
     *
     * The pre-fix `localOnlySet` filtered with `it.id in plan.localRuleIds`, so a
     * name shared by two policies could drop one of them from the per-row set
     * while keeping the other, or vice versa. Both verdicts must appear.
     */
    @Test
    fun `two LOCAL rules sharing a name are both evaluated`() {
        val set = PolicySet(
            "s",
            listOf(
                Policy("alpha", listOf(localRule("check"))),
                Policy("beta", listOf(localRule("check"))),
            ),
        )
        val plan = DatasetPlanner.plan(set, datasets) as DatasetPlan
        val report = StreamingEvaluator.evaluate(set, plan, row(ValueNode.NumberValue(1)))

        assertEquals(
            2,
            plan.localRuleKeys.size,
            "both same-named LOCAL rules must be planned, got ${plan.localRuleKeys}",
        )
        assertTrue(
            report.results.keys.containsAll(
                listOf(RuleKey.of("s", "alpha", "check"), RuleKey.of("s", "beta", "check")),
            ),
            "both same-named LOCAL rules must appear in the report; got ${report.results.keys}",
        )
    }
}
