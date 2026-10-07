package com.pipelinek.policy.kernel.evaluator

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.PolicyViolation
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.policy.ViolationCode
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueDigest
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * Spec REQ §"Policy/Rule and pure evaluator" + §"PolicyReport and canonical value hashing".
 *
 * Pure evaluator: no I/O of any kind (architectural law 5). The evaluator:
 *   1. Walks each rule's expression against the input tree.
 *   2. Produces a `RuleEvaluation` (`Passed | Violated | NotApplicable | Error`).
 *   3. Bundles all per-rule outcomes into a `PolicyReport` with a deterministic
 *      digest that is **insertion-order independent** (canonical hashing).
 *
 * Canonical hashing: a `MappingValue` is hashed by sorting its keys before
 * stringifying; this guarantees two maps with the same key/value content but
 * different insertion order produce the same digest (spec UAT (f)).
 */
@Suppress("TooManyFunctions") // Pure evaluator groups recursion, comparison, canonical hashing, helpers.
object Evaluator {

    fun evaluate(set: PolicySet, tree: ValueNode): PolicyReport {
        val results: Map<RuleId, RuleEvaluation> = set.policies
            .flatMap { policy -> policy.rules.map { it to policy.id } }
            .associate { (rule, _) -> RuleId(rule.id) to evaluateRule(rule, tree) }
        return PolicyReport(
            policySetId = set.id,
            resourceFingerprint = canonicalFingerprint(tree),
            results = results,
        )
    }

    private fun evaluateRule(rule: com.pipelinek.policy.kernel.policy.Rule, tree: ValueNode): RuleEvaluation =
        try {
            val outcome: Boolean = evalBoolean(rule.expression, tree)
            if (outcome) RuleEvaluation.Passed
            else RuleEvaluation.Violated(
                listOf(
                    PolicyViolation(
                        code = ViolationCode.COMPARISON_FAILED,
                        location = locationOf(rule.expression),
                        message = rule.message,
                        expected = describeOperator(rule.expression as? Comparison) +
                            (describeRightSide(rule.expression as? Comparison) ?: ""),
                        actual = describeActual(rule.expression as? Comparison, tree),
                    ),
                ),
            )
        } catch (e: MissingValueException) {
            // Spec REQ §Policy/Rule scenario: missing required value -> Violated.
            RuleEvaluation.Violated(
                listOf(
                    PolicyViolation(
                        code = ViolationCode.MISSING_REQUIRED_VALUE,
                        location = e.location,
                        message = rule.message,
                    ),
                ),
            )
        } catch (e: PolicyEvalException) {
            RuleEvaluation.Error(e.violation)
        }

    /**
     * Boolean evaluation of an expression over the tree. May throw
     * [PolicyEvalException] for typed refusals (architectural law 9: no silent
     * coercion; we surface a TypeMismatch instead of pretending it compared).
     */
    private fun evalBoolean(expression: Expression, tree: ValueNode): Boolean = when (expression) {
        is Literal -> error("literal expression cannot be evaluated as a boolean")
        is FieldRef -> error("field reference is not a boolean expression on its own")
        is Comparison -> {
            val leftVal = evalValue(expression.left, tree)
            val rightVal = evalValue(expression.right, tree)
            compare(leftVal, expression.op, rightVal, expression)
        }
    }

    /** Recursively evaluate an expression that resolves to a leaf value. */
    private fun evalValue(expression: Expression, tree: ValueNode): ValueNode = when (expression) {
        is Literal -> expression.value
        is FieldRef -> {
            val result = Selector.of(expression.path).expectingType(expression.expectedType).resolve(tree)
            when (result) {
                is Selector.Result.Present -> result.node
                is Selector.Result.Missing -> throw MissingValueException(expression.path)
                is Selector.Result.TypeMismatch -> throw PolicyEvalException(
                    PolicyViolation(
                        code = ViolationCode.TYPE_MISMATCH,
                        location = expression.path,
                        message = "expected ${expression.expectedType} at ${expression.path}, got ${result.actual}",
                        expected = expression.expectedType.name,
                        actual = result.actual.name,
                    ),
                )
            }
        }
        is Comparison -> error("comparison is not a leaf value")
    }

    private fun compare(left: ValueNode, op: Operator, right: ValueNode, src: Comparison): Boolean {
        // No silent coercion (architectural law 9): both sides must be NumberValue.
        val ln = left as? ValueNode.NumberValue
            ?: throw PolicyEvalException(
                PolicyViolation(
                    code = ViolationCode.TYPE_MISMATCH,
                    location = locationOf(src.left),
                    message = "comparison LHS must be Number, got ${left.type}",
                    expected = "Number",
                    actual = left.type.name,
                ),
            )
        val rn = right as? ValueNode.NumberValue
            ?: throw PolicyEvalException(
                PolicyViolation(
                    code = ViolationCode.TYPE_MISMATCH,
                    location = locationOf(src.right),
                    message = "comparison RHS must be Number, got ${right.type}",
                    expected = "Number",
                    actual = right.type.name,
                ),
            )
        val l = ln.number.toDouble()
        val r = rn.number.toDouble()
        return when (op) {
            Operator.EQ -> l == r
            Operator.NEQ -> l != r
            Operator.GT -> l > r
            Operator.GTE -> l >= r
            Operator.LT -> l < r
            Operator.LTE -> l <= r
        }
    }

    private fun locationOf(expression: Expression): DocumentPath = when (expression) {
        is Literal -> DocumentPath.ROOT
        is FieldRef -> expression.path
        is Comparison -> locationOf(expression.left)
    }

    private fun describeOperator(c: Comparison?): String? = c?.op?.let {
        when (it) {
            Operator.EQ -> "="
            Operator.NEQ -> "!="
            Operator.GT -> ">"
            Operator.GTE -> ">="
            Operator.LT -> "<"
            Operator.LTE -> "<="
        }
    }

    private fun describeActual(c: Comparison?, tree: ValueNode): String? {
        // The actual value is the LHS of the comparison (the field's value).
        val lhs = c?.left as? FieldRef ?: return null
        val node = Selector.of(lhs.path).expectingType(lhs.expectedType).resolve(tree)
        return when (node) {
            is Selector.Result.Present -> when (val n = node.node) {
                is ValueNode.NumberValue -> n.number.toString()
                is ValueNode.TextValue -> n.text
                is ValueNode.BooleanValue -> n.boolean.toString()
                else -> null
            }
            else -> null
        }
    }

    private fun describeRightSide(c: Comparison?): String? = (c?.right as? Literal)?.value
        ?.let { v ->
            when (v) {
                is ValueNode.NumberValue -> v.number.toString()
                is ValueNode.TextValue -> v.text
                else -> v.toString()
            }
        }

    /** Canonical fingerprint for the resource tree (insertion-order independent). */
    private fun canonicalFingerprint(tree: ValueNode): String =
        tree.canonicalDigest()

    private class PolicyEvalException(val violation: PolicyViolation) :
        RuntimeException(violation.message)

    /** Distinct exception for missing-value: yields Violated (not Error) per spec REQ §Policy/Rule. */
    private class MissingValueException(val location: DocumentPath) :
        RuntimeException("missing required value at $location")
}

/** Stable identity for a rule inside a report (rules are data classes; map key uses `id`). */
@JvmInline
value class RuleId(val value: String)

/**
 * Spec REQ §"PolicyReport and canonical value hashing" — deterministic report.
 */
data class PolicyReport(
    val policySetId: String,
    val resourceFingerprint: String,
    val results: Map<RuleId, RuleEvaluation>,
) {

    /** Combined hex-encoded SHA-256 over `policySetId` + `resourceFingerprint` + sorted results. */
    val digest: String by lazy {
        val serialized = buildString {
            append(policySetId).append('|')
            append(resourceFingerprint).append('|')
            // Sort by rule id so iteration over results is canonical.
            results.entries.sortedBy { it.key.value }.forEach { (id, ev) ->
                append(id.value).append('=')
                append(canonicalEvaluation(ev)).append(';')
            }
        }
        ValueDigest.sha256Hex(serialized.toByteArray(Charsets.UTF_8))
    }

    private fun canonicalEvaluation(ev: RuleEvaluation): String = when (ev) {
        RuleEvaluation.Passed -> "passed"
        RuleEvaluation.NotApplicable -> "not-applicable"
        is RuleEvaluation.Violated -> "violated[" + ev.violations
            .sortedWith(compareBy({ it.code.ordinal }, { it.location.toString() }))
            .joinToString(",") { "${it.code.name}@${it.location}|${it.expected ?: "_"}|${it.actual ?: "_"}" } + "]"
        is RuleEvaluation.Error -> "error[" + ev.violations
            .sortedWith(compareBy({ it.code.ordinal }, { it.location.toString() }))
            .joinToString(",") { "${it.code.name}@${it.location}|${it.expected ?: "_"}|${it.actual ?: "_"}" } + "]"
    }
}
