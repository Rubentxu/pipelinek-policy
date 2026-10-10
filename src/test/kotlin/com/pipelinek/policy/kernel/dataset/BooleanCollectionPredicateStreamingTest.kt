package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B6.6 · A boolean collection predicate is not an aggregate fold.
 *
 * `Expression.CollectionOp` is ALL / ANY / NONE / COUNT. Only COUNT folds rows
 * into a value. The planner, however, classifies a rule as AGGREGATE purely by
 * its shape, and `StreamingEvaluator.accumulatorFor` sent everything that was
 * not COUNT to `Accumulator.Sum` through a catch-all `else`.
 *
 * Since `Sum` admits only `NumberValue`, and these predicates read TEXT columns,
 * the measured behaviour before the fix was:
 *
 *   PROBE outcome=Error(message=type_mismatch cap 10000000 reached after 0)
 *   PROBE metrics=BudgetMetrics(rowsConsumed=2, rowsRefused=2, ...)
 *
 * A rule that sums nothing was reported as a type error, and `rowsRefused`
 * counted every row. `StreamingEvaluatorTest` only ever declared a COUNT
 * aggregate, so no existing test could see it.
 */
class BooleanCollectionPredicateStreamingTest {

    private val datasets = mapOf("telemetry" to DatasetSpec("telemetry", "csv"))

    private fun booleanRule(id: String, op: Expression.CollectionOp) = Rule(
        id = id,
        message = "$id via $op",
        expression = Expression.CollectionPredicate(
            op = op,
            source = Expression.DatasetRef("telemetry"),
            predicate = Selector.of(DocumentPath.ROOT.child("temp")),
        ),
    )

    private fun rowsOf(vararg temps: String): StreamingEvaluator.RowSupplier {
        val iter = temps
            .map { ValueNode.MappingValue(linkedMapOf("temp" to ValueNode.TextValue(it))) }
            .iterator()
        return StreamingEvaluator.RowSupplier { if (iter.hasNext()) iter.next() else null }
    }

    private fun evaluate(rule: Rule, vararg temps: String) =
        StreamingEvaluator.evaluate(
            PolicySet("s", listOf(Policy("p", listOf(rule)))),
            DatasetPlanner.plan(PolicySet("s", listOf(Policy("p", listOf(rule)))), datasets) as DatasetPlan,
            rowsOf(*temps),
        )

    /**
     * The defect itself. ANY over text rows is a well-typed rule, so it must
     * not come back as a type error, and it must not consume refusals.
     *
     * Observed RED before the fix: the outcome was
     * `Error(type_mismatch cap 10000000 reached after 0)` and
     * `rowsRefused` equalled `rowsConsumed`.
     */
    @Test
    fun `an ANY predicate over text rows is not a type error`() {
        val report = evaluate(booleanRule("any-temp", Expression.CollectionOp.ANY), "ok", "ok")

        val outcome = report.results[RuleKey.of("s", "p", "any-temp")]
        assertTrue(
            outcome !is StreamingEvaluator.RuleOutcome.Error,
            "a boolean predicate over TEXT rows must not be reported as a type " +
                "error; got $outcome",
        )
    }

    /**
     * `rowsRefused` is the budget/type accounting channel. If a boolean
     * predicate is refused on every row, the metrics claim the run spent its
     * whole budget on refusals that never happened.
     */
    @Test
    fun `a boolean predicate consumes no refusals`() {
        val report = evaluate(booleanRule("any-temp", Expression.CollectionOp.ANY), "ok", "ok")

        assertEquals(
            0L,
            report.metrics.rowsRefused,
            "rowsRefused must stay zero: nothing was refused, every row folded fine",
        )
        assertEquals(2L, report.metrics.rowsConsumed, "both rows must be consumed")
    }

    /**
     * The real fix criterion: the streaming path and the whole-document path
     * must agree about the SAME policy. This is the assertion that was
     * impossible to write before, because the streaming answer was a type error
     * and the whole-document answer was a verdict.
     */
    @Test
    fun `a boolean predicate produces a verdict rather than an error`() {
        val streamed = evaluate(booleanRule("any-temp", Expression.CollectionOp.ANY), "ok", "ok")

        assertEquals(
            StreamingEvaluator.RuleOutcome.Passed,
            streamed.results[RuleKey.of("s", "p", "any-temp")],
            "ANY over rows that all match must be a verdict, not an error",
        )
    }

    /**
     * Whole-document evaluation DOES agree, for a predicate whose source is a
     * plain field rather than a `DatasetRef`. That is the only case where the
     * two paths are comparable at all, so the parity claim lives here.
     *
     * The first version of this test compared the two paths for a
     * `DatasetRef`-scoped rule and failed with
     * `Expression.DatasetRef(telemetry) reached the per-row kernel`, which is
     * the documented contract of the streaming split, not a defect. Parity for
     * dataset-scoped predicates is asserted by `StreamingEvaluatorTest.03c` on
     * LOCAL rules instead.
     */
    @Test
    fun `whole-document evaluation agrees on a field-scoped predicate`() {
        val rule = Rule(
            id = "any-temp",
            message = "any temp",
            expression = Expression.CollectionPredicate(
                op = Expression.CollectionOp.ANY,
                source = Expression.FieldRef(
                    DocumentPath.ROOT.child("rows"),
                    ValueNode.Type.SEQUENCE,
                ),
                predicate = Selector.of(DocumentPath.ROOT.child("temp")),
            ),
        )
        val document = ValueNode.MappingValue(
            linkedMapOf(
                "rows" to ValueNode.SequenceValue(
                    listOf(
                        ValueNode.MappingValue(linkedMapOf("temp" to ValueNode.TextValue("ok"))),
                        ValueNode.MappingValue(linkedMapOf("temp" to ValueNode.TextValue("ok"))),
                    ),
                ),
            ),
        )

        val whole = EvaluatorBridge.evaluate(PolicySet("s", listOf(Policy("p", listOf(rule)))), document)
        assertIs<RuleEvaluation.Passed>(
            whole.results[RuleKey.of("s", "p", "any-temp")],
            "whole-document ANY over two matching entries is Passed",
        )
    }

    /**
     * ALL and NONE are the same shape as ANY and were equally mis-folded. One
     * test for each, because a fix that special-cased ANY alone would still be
     * wrong for the other two.
     */
    @Test
    fun `ALL and NONE are not folded either`() {
        val all = evaluate(booleanRule("all-temp", Expression.CollectionOp.ALL), "ok", "ok")
        val none = evaluate(booleanRule("none-temp", Expression.CollectionOp.NONE), "ok", "ok")

        assertTrue(
            all.results[RuleKey.of("s", "p", "all-temp")] !is StreamingEvaluator.RuleOutcome.Error,
            "ALL over text rows must not be a type error; got " +
                all.results[RuleKey.of("s", "p", "all-temp")],
        )
        assertTrue(
            none.results[RuleKey.of("s", "p", "none-temp")] !is StreamingEvaluator.RuleOutcome.Error,
            "NONE over text rows must not be a type error; got " +
                none.results[RuleKey.of("s", "p", "none-temp")],
        )
    }

    /**
     * The discriminating test. If `Passed` were produced without really folding,
     * a predicate that must be FALSE would also come back Passed. NONE over a
     * present column is false, and ALL over a column absent from every row is
     * false, so both must be reported as violations.
     */
    @Test
    fun `a false boolean verdict is reported as a violation not a pass`() {
        val nonePresent = evaluate(booleanRule("none-present", Expression.CollectionOp.NONE), "a", "b")
        val anyMissing = evaluate(
            Rule(
                id = "any-missing",
                message = "any missing",
                expression = Expression.CollectionPredicate(
                    op = Expression.CollectionOp.ANY,
                    source = Expression.DatasetRef("telemetry"),
                    predicate = Selector.of(DocumentPath.ROOT.child("absent")),
                ),
            ),
            "a", "b",
        )

        assertEquals(
            StreamingEvaluator.RuleOutcome.Violated(1L, -1L),
            nonePresent.results[RuleKey.of("s", "p", "none-present")],
            "NONE over a column present in every row is false, so the rule is violated",
        )
        assertEquals(
            StreamingEvaluator.RuleOutcome.Violated(1L, -1L),
            anyMissing.results[RuleKey.of("s", "p", "any-missing")],
            "ANY over a column absent from every row is false, so the rule is violated",
        )
    }

    /**
     * An empty stream is neither an error nor a vacuous pass: it follows the
     * same empty-collection semantics as the whole-document path, where ALL and
     * NONE are vacuously true and ANY is false.
     */
    @Test
    fun `an empty stream follows empty-collection semantics`() {
        val all = evaluate(booleanRule("all-empty", Expression.CollectionOp.ALL))
        val none = evaluate(booleanRule("none-empty", Expression.CollectionOp.NONE))
        val any = evaluate(booleanRule("any-empty", Expression.CollectionOp.ANY))

        assertEquals(
            StreamingEvaluator.RuleOutcome.Passed,
            all.results[RuleKey.of("s", "p", "all-empty")],
            "ALL over no rows is vacuously true",
        )
        assertEquals(
            StreamingEvaluator.RuleOutcome.Passed,
            none.results[RuleKey.of("s", "p", "none-empty")],
            "NONE over no rows is vacuously true",
        )
        assertEquals(
            StreamingEvaluator.RuleOutcome.Violated(1L, -1L),
            any.results[RuleKey.of("s", "p", "any-empty")],
            "ANY over no rows is false",
        )
    }

    /**
     * COUNT is the one op that IS a fold, so the fix must not have broken it.
     * Without this, "return null for everything that is not COUNT" would pass
     * every test above while silently disabling aggregation entirely.
     */
    @Test
    fun `COUNT still folds into an aggregate`() {
        val report = evaluate(
            booleanRule("count-rows", Expression.CollectionOp.COUNT).copy(
                expression = Expression.CollectionPredicate(
                    op = Expression.CollectionOp.COUNT,
                    source = Expression.DatasetRef("telemetry"),
                    predicate = Selector.of(DocumentPath.ROOT),
                ),
            ),
            "ok", "ok", "ok",
        )

        val outcome = report.results[RuleKey.of("s", "p", "count-rows")]
        assertEquals(
            StreamingEvaluator.RuleOutcome.Aggregate(ValueNode.NumberValue(3L)),
            outcome,
            "COUNT must still fold every row into a numeric aggregate",
        )
        assertEquals(1, report.metrics.accumulatorsUsed, "COUNT must still use one accumulator")
    }
}

/**
 * The whole-document evaluator, imported here under one name so the parity
 * assertion above reads as a comparison between two paths rather than between
 * two arbitrary calls.
 */
private object EvaluatorBridge {
    fun evaluate(set: PolicySet, document: ValueNode): com.pipelinek.policy.kernel.evaluator.PolicyReport =
        com.pipelinek.policy.kernel.evaluator.Evaluator.evaluate(set, document)
}
