package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M8 REQ 01/02 · Named datasets + shape classification (01a/01b, 02a/02c).
 *
 * 02b (GLOBAL refusal) lives in the planner tests (WU-5): the analyzer
 * only CLASSIFIES; refusing is a plan-time concern (law 12).
 */
class DatasetShapeTest {

    private fun localRule(id: String) = Rule(
        id = id,
        message = "local $id",
        expression = Expression.Comparison(
            Expression.FieldRef(DocumentPath.ROOT.child("temp"), ValueNode.Type.TEXT),
            Expression.Operator.TEXT_EQUALS,
            Expression.Literal(ValueNode.TextValue("ok")),
        ),
    )

    @Test
    fun `01a - dataset spec digest is stable and deterministic`() {
        val a = DatasetSpec("telemetry", "csv")
        val b = DatasetSpec("telemetry", "csv")
        assertEquals(a.canonicalDigest, b.canonicalDigest)
        assertEquals(16, a.canonicalDigest.length)
    }

    @Test
    fun `01b - falsification - different format changes the digest`() {
        val csv = DatasetSpec("telemetry", "csv")
        val jsonl = DatasetSpec("telemetry", "jsonl")
        assertNotEquals(csv.canonicalDigest, jsonl.canonicalDigest, "format is part of identity")
        val declared = DatasetSpec("telemetry", "csv", requiresGlobalIndex = true)
        assertNotEquals(csv.canonicalDigest, declared.canonicalDigest, "declaration is part of identity")
    }

    @Test
    fun `02a - per-row rules classify as LOCAL`() {
        val set = PolicySet("s", listOf(Policy("p", listOf(localRule("a"), localRule("b")))))
        val result = DatasetShapeAnalyzer.analyze(set)
        assertEquals(DatasetShape.LOCAL, result.shapesByRule["a"])
        assertEquals(DatasetShape.LOCAL, result.shapesByRule["b"])
        assertEquals(DatasetShape.LOCAL, result.worst)
    }

    @Test
    fun `02c - COUNT over a dataset is AGGREGATE`() {
        // CollectionPredicate with a DatasetRef source: bounded, single-pass.
        val selector = com.pipelinek.policy.kernel.selector.Selector.of(
            DocumentPath.ROOT.child("temp"),
        )
        val aggregateRule = Rule(
            id = "count-hot",
            message = "count rows with hot temp",
            expression = Expression.CollectionPredicate(
                op = Expression.CollectionOp.COUNT,
                source = Expression.DatasetRef("telemetry"),
                predicate = selector,
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(aggregateRule))))
        val result = DatasetShapeAnalyzer.analyze(set)
        assertEquals(DatasetShape.AGGREGATE, result.shapesByRule["count-hot"])
        assertEquals(DatasetShape.AGGREGATE, result.worst)
    }

    @Test
    fun `02 - free-floating DatasetRef is GLOBAL (waiting for the planner to refuse)`() {
        val globalRule = Rule(
            id = "cross-row",
            message = "compares against another row",
            expression = Expression.Comparison(
                Expression.DatasetRef("telemetry"),
                Expression.Operator.TEXT_EQUALS,
                Expression.Literal(ValueNode.TextValue("x")),
            ),
        )
        val set = PolicySet("s", listOf(Policy("p", listOf(globalRule))))
        val result = DatasetShapeAnalyzer.analyze(set)
        assertEquals(DatasetShape.GLOBAL, result.shapesByRule["cross-row"])
        assertEquals(DatasetShape.GLOBAL, result.worst)
        assertTrue(result.worst > DatasetShape.AGGREGATE, "enum order documents the budget ladder")
    }
}
