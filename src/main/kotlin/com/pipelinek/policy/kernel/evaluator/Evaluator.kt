package com.pipelinek.policy.kernel.evaluator

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.CollectionOp
import com.pipelinek.policy.kernel.expression.Expression.CollectionPredicate
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.PolicyViolation
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.policy.ViolationCode
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueDigest
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * Spec REQ §"Policy/Rule and pure evaluator" + §"PolicyReport and canonical value hashing".
 * + M3 §"DSL symbolic API" + §"Collection predicates" + §"Rule combinators".
 *
 * Pure evaluator: no I/O of any kind (architectural law 5). The evaluator:
 *   1. Walks each rule's `appliesWhen` first; a non-truthy result short-circuits
 *      the rule to `NotApplicable` (NOT `Violated` — spec REQ §"appliesWhen(false)
 *      yields NotApplicable"). M1 rules without `appliesWhen` are unchanged.
 *   2. Walks the rule's main `expression` and produces a `RuleEvaluation`
 *      (`Passed | Violated | NotApplicable | Error`).
 *   3. Bundles all per-rule outcomes into a `PolicyReport` with a deterministic
 *      digest that is **insertion-order independent** (canonical hashing).
 *
 * M3 NEW branches:
 *   - `Expression.CollectionPredicate`: cursor over `SequenceValue.elements` or
 *     `MappingValue.entries` with `Selector` predicate. NEVER materializes the
 *     collection (single pass, short-circuit). `Missing` on the source ⇒
 *     `false` (ANY/ALL) or `0` (COUNT) or `true` (NONE). Type mismatch on an
 *     element ⇒ `COLLECTION_PREDICATE_FAILED`.
 *   - `Operator.TEXT_EQUALS` / `Operator.BOOLEAN_EQUALS`: structural `==` over
 *     `TextValue` / `BooleanValue`. NO coercion to Number (law 9).
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

    private fun evaluateRule(rule: Rule, tree: ValueNode): RuleEvaluation {
        // M3: appliesWhen short-circuit. If `appliesWhen` is non-null and its
        // verdict is falsy (false / Missing), the rule is `NotApplicable`.
        // A typed refusal INSIDE appliesWhen surfaces as Error so the author
        // knows their guard predicate is malformed.
        rule.appliesWhen?.let { gate ->
            return when (val gateResult = evalAppliesWhen(gate, tree)) {
                AppliesWhenOutcome.True -> evaluateMainExpression(rule, tree)
                AppliesWhenOutcome.FalseOrMissing -> RuleEvaluation.NotApplicable
                is AppliesWhenOutcome.TypedRefusal -> RuleEvaluation.Error(gateResult.violation)
            }
        }
        return evaluateMainExpression(rule, tree)
    }

    @Suppress("ReturnCount", "LongMethod", "ComplexMethod", "TooGenericExceptionCaught")
    private fun evaluateMainExpression(rule: Rule, tree: ValueNode): RuleEvaluation {
        return try {
            val outcome: Boolean = try {
                evalBoolean(rule.expression, tree)
            } catch (e: CountAsLongSignal) {
                // M3: a `CollectionPredicate(COUNT, ...)` is a Long-verdict
                // expression; it never participates in `Passed`/`Violated`.
                // We embed it in a numeric comparison with 0 to give the
                // author a meaningful Pass/Fail when used as the rule's main
                // expression (rare, but the spec leaves room for it).
                return RuleEvaluation.Violated(
                    listOf(
                        PolicyViolation(
                            code = ViolationCode.COMPARISON_FAILED,
                            location = locationOf(rule.expression),
                            message = rule.message,
                            expected = "count>=1",
                            actual = e.value.toString(),
                        ),
                    ),
                )
            }
            if (outcome) RuleEvaluation.Passed
            else RuleEvaluation.Violated(
                listOf(
                    buildViolation(
                        rule = rule,
                        expr = rule.expression,
                        code = ViolationCode.COMPARISON_FAILED,
                        tree = tree,
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
                        expected = rule.expected,
                        actual = rule.actual,
                    ),
                ),
            )
        } catch (e: PolicyEvalException) {
            // COLLECTION_PREDICATE_FAILED ⇒ Violated; other typed refusals ⇒ Error.
            when (e.violation.code) {
                ViolationCode.COLLECTION_PREDICATE_FAILED -> RuleEvaluation.Violated(
                    listOf(
                        e.violation.copy(
                            message = rule.message,
                            expected = rule.expected,
                            actual = rule.actual,
                        ),
                    ),
                )
                else -> RuleEvaluation.Error(e.violation)
            }
        }
    }

    private sealed interface AppliesWhenOutcome {
        data object True : AppliesWhenOutcome
        data object FalseOrMissing : AppliesWhenOutcome
        data class TypedRefusal(val violation: PolicyViolation) : AppliesWhenOutcome
    }

    private fun evalAppliesWhen(expression: Expression, tree: ValueNode): AppliesWhenOutcome =
        when (val v = tryAppliesWhen(expression, tree)) {
            is AppliesWhenOutcomeRaw.BooleanResult -> v.toOutcome()
            AppliesWhenOutcomeRaw.Missing -> AppliesWhenOutcome.FalseOrMissing
            is AppliesWhenOutcomeRaw.Refusal -> AppliesWhenOutcome.TypedRefusal(v.violation)
        }

    private sealed interface AppliesWhenOutcomeRaw {
        data class BooleanResult(val b: Boolean) : AppliesWhenOutcomeRaw {
            fun toOutcome(): AppliesWhenOutcome =
                if (b) AppliesWhenOutcome.True else AppliesWhenOutcome.FalseOrMissing
        }
        data object Missing : AppliesWhenOutcomeRaw
        data class Refusal(val violation: PolicyViolation) : AppliesWhenOutcomeRaw
    }

    private fun tryAppliesWhen(expression: Expression, tree: ValueNode): AppliesWhenOutcomeRaw {
        // The Missing short-circuit into `FalseOrMissing` deliberately swallows
        // the exception — the diagnostic location is not actionable here; the
        // appliesWhen gate's job is to decide "should I evaluate?" and a
        // missing source resolves to "no".
        @Suppress("SwallowedException")
        return try {
            AppliesWhenOutcomeRaw.BooleanResult(evalBoolean(expression, tree))
        } catch (e: MissingValueException) {
            AppliesWhenOutcomeRaw.Missing
        } catch (e: PolicyEvalException) {
            AppliesWhenOutcomeRaw.Refusal(e.violation)
        }
    }

    private fun buildViolation(
        rule: Rule,
        expr: Expression,
        code: ViolationCode,
        tree: ValueNode,
    ): PolicyViolation {
        val comparison = expr as? Comparison
        return PolicyViolation(
            code = code,
            location = locationOf(expr),
            message = rule.message,
            expected = rule.expected
                ?: describeOperator(comparison) + (describeRightSide(comparison) ?: ""),
            actual = rule.actual ?: describeActual(comparison, tree),
        )
    }

    /**
     * Boolean evaluation of an expression over the tree. May throw
     * [PolicyEvalException] for typed refusals (architectural law 9: no silent
     * coercion; we surface a TypeMismatch instead of pretending it compared).
     */
    private fun evalBoolean(expression: Expression, tree: ValueNode): Boolean = when (expression) {
        is Literal -> {
            // Boolean literal — true/false propagates; non-boolean literals are
            // treated as typed refusals (no implicit bool coercion).
            val v = expression.value
            when (v) {
                is ValueNode.BooleanValue -> v.boolean
                else -> throw PolicyEvalException(
                    PolicyViolation(
                        code = ViolationCode.TYPE_MISMATCH,
                        location = DocumentPath.ROOT,
                        message = "boolean expression cannot be coerced from ${v.type}",
                        expected = "Boolean",
                        actual = v.type.name,
                    ),
                )
            }
        }
        is FieldRef -> throw PolicyEvalException(
            PolicyViolation(
                code = ViolationCode.TYPE_MISMATCH,
                location = expression.path,
                message = "field reference is not a boolean expression on its own",
                expected = "Boolean",
                actual = "FieldRef",
            ),
        )
        is Comparison -> {
            val leftVal = evalValue(expression.left, tree)
            val rightVal = evalValue(expression.right, tree)
            compare(leftVal, expression.op, rightVal, expression)
        }
        is CollectionPredicate -> evalCollectionPredicate(expression, tree)
    }

    /** Recursively evaluate an expression that resolves to a leaf value. */
    private fun evalValue(expression: Expression, tree: ValueNode): ValueNode = when (expression) {
        is Literal -> expression.value
        is FieldRef -> {
            val sel = Selector.of(expression.path).expectingType(expression.expectedType)
            when (val result = sel.resolve(tree)) {
                is Selector.Result.Present -> result.node
                is Selector.Result.Missing -> {
                    if (sel.isRequired()) {
                        throw MissingValueException(expression.path)
                    } else {
                        throw PolicyEvalException(
                            PolicyViolation(
                                code = ViolationCode.MISSING_REQUIRED_VALUE,
                                location = expression.path,
                                message = "missing optional value at ${expression.path}",
                            ),
                        )
                    }
                }
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
        is CollectionPredicate -> error("collection predicate is not a leaf value")
    }

    private fun compare(left: ValueNode, op: Operator, right: ValueNode, src: Comparison): Boolean {
        return when (op) {
            Operator.TEXT_EQUALS -> textEquals(left, right, src)
            Operator.BOOLEAN_EQUALS -> booleanEquals(left, right, src)
            else -> numericCompare(left, op, right, src)
        }
    }

    private fun textEquals(left: ValueNode, right: ValueNode, src: Comparison): Boolean {
        val lt = left as? ValueNode.TextValue ?: throw PolicyEvalException(
            PolicyViolation(
                code = ViolationCode.TYPE_MISMATCH,
                location = locationOf(src.left),
                message = "textEquals LHS must be Text, got ${left.type}",
                expected = "Text",
                actual = left.type.name,
            ),
        )
        val rt = right as? ValueNode.TextValue ?: throw PolicyEvalException(
            PolicyViolation(
                code = ViolationCode.TYPE_MISMATCH,
                location = locationOf(src.right),
                message = "textEquals RHS must be Text, got ${right.type}",
                expected = "Text",
                actual = right.type.name,
            ),
        )
        return lt.text == rt.text
    }

    private fun booleanEquals(left: ValueNode, right: ValueNode, src: Comparison): Boolean {
        val lb = left as? ValueNode.BooleanValue ?: throw PolicyEvalException(
            PolicyViolation(
                code = ViolationCode.TYPE_MISMATCH,
                location = locationOf(src.left),
                message = "booleanEquals LHS must be Boolean, got ${left.type}",
                expected = "Boolean",
                actual = left.type.name,
            ),
        )
        val rb = right as? ValueNode.BooleanValue ?: throw PolicyEvalException(
            PolicyViolation(
                code = ViolationCode.TYPE_MISMATCH,
                location = locationOf(src.right),
                message = "booleanEquals RHS must be Boolean, got ${right.type}",
                expected = "Boolean",
                actual = right.type.name,
            ),
        )
        return lb.boolean == rb.boolean
    }

    private fun numericCompare(left: ValueNode, op: Operator, right: ValueNode, src: Comparison): Boolean {
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
            Operator.TEXT_EQUALS, Operator.BOOLEAN_EQUALS -> error(
                "numericCompare called for non-numeric op $op"
            )
        }
    }

    /**
     * M3: CollectionPredicate evaluation. Cursor-based: walks `elements` /
     * `entries` with a single `for` pass and short-circuits per op. The
     * predicate is a `Selector` (NOT an author lambda — law 4).
     *
     * Verdict matrix (the predicate is satisfied per element iff
     * `selector.resolve(elementNode)` is `Present` and matches the predicate's
     * declared expectations):
     *   - ALL: every element matches ⇒ true. First non-match ⇒ false with
     *          COLLECTION_PREDICATE_FAILED at the offending key/index.
     *   - ANY: at least one element matches ⇒ true. Otherwise false.
     *   - NONE: no element matches ⇒ true. Otherwise false with violation.
     *   - COUNT: number of matching elements, returned as Long (overflow-safe).
     *
     * `Missing` on the source ⇒ falsy / 0 without traversing the collection.
     * `MappingValue` is walked per-entry `(k, v)`; the predicate resolves
     * over `v` and the violation location extends with `k`.
     */
    @Suppress("ThrowsCount", "LongMethod")
    private fun evalCollectionPredicate(node: CollectionPredicate, tree: ValueNode): Boolean {
        // Source resolution: a `MissingValueException` from the source FieldRef
        // is a REQUIRED-source refusal, NOT an empty-collection cursor. We
        // propagate it so the rule evaluator surfaces MISSING_REQUIRED_VALUE.
        // The empty-collection short-circuit only applies when the source
        // resolves to a SequenceValue(empty) / MappingValue(empty).
        // MissingValueException is NOT caught here; we let it propagate to
        // evaluateRule, which converts it to Violated(MISSING_REQUIRED_VALUE).
        val sourceValue: ValueNode = evalValue(node.source, tree)

        // Special case: COUNT can return 0 if the source resolves to an
        // empty SequenceValue / MappingValue. The Boolean API can't express
        // Long — we route it through a signal.
        if (node.op == CollectionOp.COUNT) {
            val n = countMatching(sourceValue, node.predicate)
            throw CountAsLongSignal(n)
        }

        val perEntry: List<Pair<String, ValueNode>> = perEntry(sourceValue, node)

        return when (node.op) {
            CollectionOp.ALL -> allMatches(node, perEntry)
            CollectionOp.ANY -> perEntry.any { (_, v) -> matchesAtLocation(node.predicate, v, null) }
            CollectionOp.NONE -> perEntry.none { (_, v) -> matchesAtLocation(node.predicate, v, null) }
            CollectionOp.COUNT -> error("COUNT routed above")
        }
    }

    private fun perEntry(
        sourceValue: ValueNode,
        node: CollectionPredicate,
    ): List<Pair<String, ValueNode>> = when (sourceValue) {
        is ValueNode.SequenceValue -> sourceValue.elements.withIndex()
            .map { it.index.toString() to it.value }
        is ValueNode.MappingValue -> sourceValue.entries.entries
            .map { it.key to it.value }
        else -> throw typeMismatchForCollectionSource(node, sourceValue)
    }

    private fun typeMismatchForCollectionSource(
        node: CollectionPredicate,
        sourceValue: ValueNode,
    ): PolicyEvalException = PolicyEvalException(
        PolicyViolation(
            code = ViolationCode.TYPE_MISMATCH,
            location = locationOf(node.source),
            message = "CollectionPredicate source must be Sequence or Mapping, got ${sourceValue.type}",
            expected = "Sequence|Mapping",
            actual = sourceValue.type.name,
        ),
    )

    private fun allMatches(
        node: CollectionPredicate,
        perEntry: List<Pair<String, ValueNode>>,
    ): Boolean {
        for ((k, v) in perEntry) {
            if (!matchesAtLocation(node.predicate, v, k)) {
                throw PolicyEvalException(
                    PolicyViolation(
                        code = ViolationCode.COLLECTION_PREDICATE_FAILED,
                        location = locationOf(node.source).child(k),
                        message = "all: predicate failed at $k",
                    ),
                )
            }
        }
        return true
    }

    /**
     * Helper used by COUNT and by the test suite to fetch the Long verdict of
     * a COUNT without going through evalBoolean. Cursor-based, single pass.
     */
    private fun countMatching(sourceValue: ValueNode, predicate: Selector): Long {
        return when (sourceValue) {
            is ValueNode.SequenceValue -> {
                var n = 0L
                for (v in sourceValue.elements) {
                    if (matchesAtLocation(predicate, v, null)) n++
                }
                n
            }
            is ValueNode.MappingValue -> {
                var n = 0L
                for ((_, v) in sourceValue.entries) {
                    if (matchesAtLocation(predicate, v, null)) n++
                }
                n
            }
            else -> throw PolicyEvalException(
                PolicyViolation(
                    code = ViolationCode.TYPE_MISMATCH,
                    location = DocumentPath.ROOT,
                    message = "count: source must be Sequence or Mapping, got ${sourceValue.type}",
                    expected = "Sequence|Mapping",
                    actual = sourceValue.type.name,
                ),
            )
        }
    }

    /**
     * Resolve the predicate over an element. The predicate is a `Selector`
     * rooted at a synthetic path; scalar elements are wrapped in
     * `{"value" -> element}` so that `Selector.optional(...value)` works.
     * Mapping elements are NOT wrapped — the predicate navigates the mapping
     * directly (its segments are the entry keys). This is the semantic that
     * M3 commits to; the DSL `count`/`all`/`any`/`none` builders attach
     * `Selector.optional(value)` for scalar collections and project the right
     * path for mapping collections.
     */
    @Suppress("MaxLineLength")
    private fun matchesAtLocation(
        predicate: Selector,
        element: ValueNode,
        @Suppress("UNUSED_PARAMETER") key: String?,
    ): Boolean {
        val rooted: ValueNode = if (element is ValueNode.MappingValue) {
            element
        } else {
            ValueNode.MappingValue(linkedMapOf("value" to element))
        }
        return when (val r = predicate.resolve(rooted)) {
            is Selector.Result.Present -> true
            is Selector.Result.Missing -> !predicate.isRequired()
            is Selector.Result.TypeMismatch -> false
        }
    }

    /**
     * Marker exception used to smuggle the Long verdict of `COUNT` back to
     * `evalBoolean`, which only knows `Boolean`. We catch it at the boundary.
     */
    private class CountAsLongSignal(val value: Long) : RuntimeException("count=$value")

    private fun locationOf(expression: Expression): DocumentPath = when (expression) {
        is Literal -> DocumentPath.ROOT
        is FieldRef -> expression.path
        is Comparison -> locationOf(expression.left)
        is CollectionPredicate -> locationOf(expression.source)
    }

    private fun describeOperator(c: Comparison?): String? = c?.op?.let {
        when (it) {
            Operator.EQ -> "="
            Operator.NEQ -> "!="
            Operator.GT -> ">"
            Operator.GTE -> ">="
            Operator.LT -> "<"
            Operator.LTE -> "<="
            Operator.TEXT_EQUALS -> "==text"
            Operator.BOOLEAN_EQUALS -> "==bool"
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
