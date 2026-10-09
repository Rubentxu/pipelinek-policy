package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule

/**
 * M8 REQ 01/02 · Named datasets + LOCAL/AGGREGATE/GLOBAL shape.
 *
 * The streaming budget of docs/07 §5 IS the contract:
 *   LOCAL      O(1) additional per row
 *   AGGREGATE  bounded/specialized accumulator (single pass, capped)
 *   GLOBAL     index/materialization declared explicitly
 *
 * A GLOBAL policy MUST NOT disguise itself as streaming-safe
 * (architectural law 12): [DatasetShapeAnalyzer] only marks GLOBAL
 * when the rule was DECLARED to require it (via DatasetSpec.requires
 * or an explicit dataset-wide reference outside a bounded predicate).
 */
enum class DatasetShape { LOCAL, AGGREGATE, GLOBAL }

/**
 * REQ 01 · A named dataset. `id` names it; `format` is the source
 * format (csv/jsonl/...); `requiresGlobalIndex` is the EXPLICIT
 * declaration that unlocks GLOBAL rules (law 12).
 */
data class DatasetSpec(
    val id: String,
    val format: String,
    val resourceKind: String = "row",
    val requiresGlobalIndex: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "dataset id must not be blank" }
        require(format.isNotBlank()) { "dataset format must not be blank" }
    }

    /** Canonical digest: stable, format-sensitive (01a/01b). */
    val canonicalDigest: String by lazy {
        val seed = "$id|$format|$resourceKind|$requiresGlobalIndex"
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
    }
}

/**
 * REQ 02 · Classifies every rule of a PolicySet against a dataset.
 *
 * Classification (v1, conservative):
 *   - Any `Expression.DatasetRef` inside a BOUNDED dataset predicate
 *     (`CollectionPredicate` with op ALL/ANY/NONE/COUNT) ⇒ AGGREGATE.
 *   - A `DatasetRef` anywhere ELSE (free-floating dataset access,
 *     cross-row comparison) ⇒ GLOBAL — legal only if the spec declared
 *     `requiresGlobalIndex`; the ANALYZER still reports GLOBAL and the
 *     PLANNER is the one that refuses without a declaration (law 12:
 *     refusal is a plan-time outcome, typed, never silent downgrade).
 *   - Everything else (only FieldRef/Literal over the current row) ⇒ LOCAL.
 */
object DatasetShapeAnalyzer {

    data class Result(val shapesByRule: Map<String, DatasetShape>) {
        val worst: DatasetShape
            get() = when {
                DatasetShape.GLOBAL in shapesByRule.values -> DatasetShape.GLOBAL
                DatasetShape.AGGREGATE in shapesByRule.values -> DatasetShape.AGGREGATE
                else -> DatasetShape.LOCAL
            }
    }

    fun analyze(set: PolicySet): Result {
        val shapes = linkedMapOf<String, DatasetShape>()
        for (policy in set.policies) {
            for (rule in policy.rules) {
                shapes[rule.id] = ruleShape(rule)
            }
        }
        return Result(shapes)
    }

    fun ruleShape(rule: Rule): DatasetShape {
        val inGate = rule.appliesWhen?.let { shapeOfSubtree(it) } ?: DatasetShape.LOCAL
        val inMain = shapeOfSubtree(rule.expression)
        return worstOf(inGate, inMain)
    }

    private fun worstOf(a: DatasetShape, b: DatasetShape): DatasetShape =
        if (a.ordinal >= b.ordinal) a else b

    private fun shapeOfSubtree(expression: Expression): DatasetShape = when (expression) {
        is Expression.Literal -> DatasetShape.LOCAL
        is Expression.FieldRef -> DatasetShape.LOCAL
        is Expression.Reference -> DatasetShape.LOCAL
        is Expression.Not -> shapeOfSubtree(expression.body)
        is Expression.DatasetRef -> DatasetShape.GLOBAL // free-floating dataset access
        is Expression.Comparison -> worstOf(
            shapeOfSubtree(expression.left),
            shapeOfSubtree(expression.right),
        )
        is Expression.CollectionPredicate ->
            // A DatasetRef inside a bounded predicate is AGGREGATE, not GLOBAL.
            if (containsDatasetRef(expression)) DatasetShape.AGGREGATE
            else shapeOfSubtree(expression.source)
    }

    private fun containsDatasetRef(expression: Expression): Boolean = when (expression) {
        is Expression.DatasetRef -> true
        is Expression.Literal -> false
        is Expression.FieldRef -> false
        is Expression.Reference -> false
        is Expression.Not -> containsDatasetRef(expression.body)
        is Expression.Comparison ->
            containsDatasetRef(expression.left) || containsDatasetRef(expression.right)
        is Expression.CollectionPredicate ->
            containsDatasetRef(expression.source) ||
                expression.predicate.toString().contains("DatasetRef")
    }
}
