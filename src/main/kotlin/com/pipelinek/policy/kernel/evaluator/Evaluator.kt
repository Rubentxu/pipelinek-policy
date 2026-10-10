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
import java.math.BigDecimal
import java.math.BigInteger
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.policy.RuleSeverity
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
        val results: Map<RuleKey, RuleEvaluation> = set.policies
            .flatMap { policy -> policy.rules.map { it to policy.id } }
            .associate { (rule, policyId) -> RuleKey.of(set.id, policyId, rule.id) to evaluateRule(rule, tree) }
        return PolicyReport(
            policySetId = set.id,
            resourceFingerprint = canonicalFingerprint(tree),
            results = results,
            // B4.5 / ADR-0015: the report is the only thing `PolicyDiff` gets,
            // so the author-declared severity has to travel with it. A missing
            // entry means "that rule declared no severity", NOT "INFO".
            severities = set.policies
                .flatMap { policy -> policy.rules.map { rule -> policy.id to rule } }
                .mapNotNull { (policyId, rule) ->
                    rule.severity?.let { severity ->
                        RuleKey.of(set.id, policyId, rule.id) to severity
                    }
                }
                .toMap(),
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

    @Suppress("ReturnCount", "LongMethod", "ComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
    private fun evaluateMainExpression(rule: Rule, tree: ValueNode): RuleEvaluation {
        return try {
            val outcome: Boolean = evalBoolean(rule.expression, tree)
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
        } catch (_: OptionalMissingValueException) {
            // Optional absence is control flow here: it makes the rule NotApplicable.
            RuleEvaluation.NotApplicable
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

    @Suppress("SwallowedException") // both catches convert the failure into typed outcomes
    private fun tryAppliesWhen(expression: Expression, tree: ValueNode): AppliesWhenOutcomeRaw {
        // The Missing short-circuit into `FalseOrMissing` deliberately swallows
        // the exception — the diagnostic location is not actionable here; the
        // appliesWhen gate's job is to decide "should I evaluate?" and a
        // missing source resolves to "no".
        return try {
            AppliesWhenOutcomeRaw.BooleanResult(evalBoolean(expression, tree))
        } catch (e: OptionalMissingValueException) {
            AppliesWhenOutcomeRaw.Missing
        } catch (e: MissingValueException) {
            AppliesWhenOutcomeRaw.Missing
        } catch (e: PolicyEvalException) {
            AppliesWhenOutcomeRaw.Refusal(e.violation)
        } catch (e: IllegalStateException) {
            // B1.6: an unsubstituted Reference (or any malformed gate) is a
            // LOUD typed refusal — never a silent NotApplicable skip.
            // The original message is carried into the violation on purpose.
            AppliesWhenOutcomeRaw.Refusal(
                PolicyViolation(
                    code = ViolationCode.TYPE_MISMATCH,
                    location = DocumentPath.ROOT,
                    message = "appliesWhen is not evaluable: ${e.message}",
                    expected = "evaluable boolean expression",
                    actual = "malformed gate",
                ),
            )
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
        // B1.3: explicit negation — evaluate the body as a Boolean and flip.
        // Typed refusals propagate (no silent coercion).
        is Expression.Not -> !evalBoolean(expression.body, tree)
        is Expression.DatasetRef ->
            error(
                "Expression.DatasetRef(${expression.name}) reached the per-row kernel — " +
                    "dataset expressions are evaluated by StreamingEvaluator with a DatasetPlan",
            )
        is Expression.Reference ->
            error(
                "Expression.Reference reached the kernel — DSL ParamSubstitutor " +
                    "failed to substitute \${${expression.name}}",
            )
    }

    /** Recursively evaluate an expression that resolves to a leaf value. */
    private fun evalValue(expression: Expression, tree: ValueNode): ValueNode = when (expression) {
        is Literal -> expression.value
        is Expression.Not -> throw PolicyEvalException(
            PolicyViolation(
                code = ViolationCode.TYPE_MISMATCH,
                location = locationOf(expression),
                message = "boolean negation cannot be a numeric operand",
                expected = "Number",
                actual = "Not:Boolean",
            ),
        )
        // B1.4: COUNT is a typed Long-valued expression (never a Boolean).
        is CollectionPredicate -> {
            if (expression.op == CollectionOp.COUNT) {
                ValueNode.NumberValue(countOf(expression, tree))
            } else {
                throw PolicyEvalException(
                    PolicyViolation(
                        code = ViolationCode.TYPE_MISMATCH,
                        location = locationOf(expression),
                        message = "${expression.op} produces a Boolean and cannot be a numeric operand",
                        expected = "COUNT:Number",
                        actual = "${expression.op}:Boolean",
                    ),
                )
            }
        }
        is FieldRef -> evalFieldRef(expression, tree)
        is Comparison -> error("comparison is not a leaf value")
        is Expression.DatasetRef ->
            error(
                "Expression.DatasetRef(${expression.name}) reached the per-row kernel — " +
                    "dataset expressions are evaluated by StreamingEvaluator with a DatasetPlan",
            )
        is Expression.Reference ->
            error(
                "Expression.Reference reached the kernel — DSL ParamSubstitutor " +
                    "failed to substitute \${${expression.name}}",
        )
    }

    private fun evalFieldRef(expression: FieldRef, tree: ValueNode): ValueNode {
        val baseSelector = if (expression.optional) {
            Selector.optional(expression.path)
        } else {
            Selector.of(expression.path)
        }
        val sel = baseSelector.expectingType(expression.expectedType)
        return when (val result = sel.resolve(tree)) {
            is Selector.Result.Present -> result.node
            is Selector.Result.Missing -> missingFieldValue(expression)
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

    private fun missingFieldValue(expression: FieldRef): Nothing =
        if (expression.optional) {
            throw OptionalMissingValueException(expression.path)
        } else {
            throw MissingValueException(expression.path)
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
        requireFinite(ln.number, locationOf(src.left))
        requireFinite(rn.number, locationOf(src.right))
        val cmp: Int = exactCompare(ln, rn)
        return when (op) {
            Operator.EQ -> cmp == 0
            Operator.NEQ -> cmp != 0
            Operator.GT -> cmp > 0
            Operator.GTE -> cmp >= 0
            Operator.LT -> cmp < 0
            Operator.LTE -> cmp <= 0
            Operator.TEXT_EQUALS, Operator.BOOLEAN_EQUALS -> error(
                "numericCompare called for non-numeric op $op"
            )
        }
    }

    /**
     * B1.2 (P1): exact numeric comparison. No `toDouble()` anywhere —
     * 2^53+1 vs 2^53+2 and long decimal scales compare exactly as written.
     * Long/Int/Short/Byte/BigInteger share one integer view; Float/Double/
     * BigDecimal go through `toBigDecimal()` (BigDecimal wraps its exact
     * decimal literal; Float/Double expose their binary value). No silent
     * coercion of shape (law 9): only the VALUE comparison is unified.
     */
    private fun exactCompare(ln: ValueNode.NumberValue, rn: ValueNode.NumberValue): Int {
        val l = ln.number
        val r = rn.number
        val bothIntegral = (l is Long || l is Int || l is Short || l is Byte || l is BigInteger) &&
            (r is Long || r is Int || r is Short || r is Byte || r is BigInteger)
        if (bothIntegral) {
            return toBigIntegerExact(l).compareTo(toBigIntegerExact(r))
        }
        return toBigDecimalExact(l).compareTo(toBigDecimalExact(r))
    }

    private fun requireFinite(number: Number, location: DocumentPath) {
        val finite = when (number) {
            is Double -> number.isFinite()
            is Float -> number.isFinite()
            else -> true
        }
        if (!finite) {
            throw PolicyEvalException(
                PolicyViolation(
                    code = ViolationCode.TYPE_MISMATCH,
                    location = location,
                    message = "non-finite numeric values cannot be compared",
                    expected = "finite Number",
                    actual = number.toString(),
                ),
            )
        }
    }

    private fun toBigIntegerExact(n: Number): BigInteger = when (n) {
        is BigInteger -> n
        is Long -> BigInteger.valueOf(n)
        is Int -> BigInteger.valueOf(n.toLong())
        is Short -> BigInteger.valueOf(n.toLong())
        is Byte -> BigInteger.valueOf(n.toLong())
        else -> error("not an integral carrier: ${n::class.simpleName}")
    }

    private fun toBigDecimalExact(n: Number): BigDecimal = when (n) {
        is BigDecimal -> n
        is BigInteger -> BigDecimal(n)
        is Double -> BigDecimal(n)
        is Float -> BigDecimal(n.toDouble())
        is Long -> BigDecimal.valueOf(n)
        is Int -> BigDecimal.valueOf(n.toLong())
        is Short -> BigDecimal.valueOf(n.toLong())
        is Byte -> BigDecimal.valueOf(n.toLong())
        else -> error("not a decimal carrier: ${n::class.simpleName}")
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
        if (node.op == CollectionOp.COUNT) {
            throw PolicyEvalException(
                PolicyViolation(
                    code = ViolationCode.TYPE_MISMATCH,
                    location = locationOf(node),
                    message = "COUNT produces a Number and must be used in a numeric comparison",
                    expected = "Boolean-valued collection predicate",
                    actual = "COUNT:Number",
                ),
            )
        }
        // Source resolution: a `MissingValueException` from the source FieldRef
        // is a REQUIRED-source refusal, NOT an empty-collection cursor. We
        // propagate it so the rule evaluator surfaces MISSING_REQUIRED_VALUE.
        // The empty-collection short-circuit only applies when the source
        // resolves to a SequenceValue(empty) / MappingValue(empty).
        // MissingValueException is NOT caught here; we let it propagate to
        // evaluateRule, which converts it to Violated(MISSING_REQUIRED_VALUE).
        val sourceValue: ValueNode = evalValue(node.source, tree)

        val perEntry: List<Pair<String, ValueNode>> = perEntry(sourceValue, node)

        return when (node.op) {
            CollectionOp.ALL -> allMatches(node, perEntry)
            CollectionOp.ANY -> perEntry.any { (_, v) -> matchesAtLocation(node.predicate, v, null) }
            CollectionOp.NONE -> perEntry.none { (_, v) -> matchesAtLocation(node.predicate, v, null) }
            CollectionOp.COUNT -> error("COUNT was rejected before boolean evaluation")
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
     * B1.4: the Long verdict of a `CollectionPredicate(COUNT, ...)` as a
     * typed value expression. Resolves the source (REQUIRED-source refusals
     * still propagate) and counts matching entries in a single cursor pass.
     */
    private fun countOf(node: CollectionPredicate, tree: ValueNode): Long {
        val sourceValue: ValueNode = evalValue(node.source, tree)
        return countMatching(sourceValue, node.predicate)
    }

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

    private fun locationOf(expression: Expression): DocumentPath = when (expression) {
        is Literal -> DocumentPath.ROOT
        is FieldRef -> expression.path
        is Comparison -> locationOf(expression.left)
        is Expression.Not -> locationOf(expression.body)
        is CollectionPredicate -> locationOf(expression.source)
        is Expression.DatasetRef -> DocumentPath.ROOT
        is Expression.Reference -> DocumentPath.ROOT
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

    /** Optional path is absent: skip this rule (or treat its appliesWhen as false). */
    private class OptionalMissingValueException(val location: DocumentPath) :
        RuntimeException("missing optional value at $location")
}

/**
 * B1.1: contextual rule identity. A rule ID is only unique inside its policy,
 * and a policy is only meaningful inside its policy set. Keep the components
 * structural so legal punctuation in identifiers cannot create key collisions.
 */
data class RuleKey(
    val policySetId: String,
    val policyId: String,
    val ruleId: String,
) {
    /** Injective canonical form for digesting and string-based report surfaces. */
    val value: String
        get() = listOf(policySetId, policyId, ruleId).joinToString("") { part -> "${part.length}:$part" }

    companion object {
        fun of(policySetId: String, policyId: String, ruleId: String): RuleKey =
            RuleKey(policySetId, policyId, ruleId)
    }
}

/** Backwards source name retained for Kotlin callers; identity is [RuleKey]. */
typealias RuleId = RuleKey

/**
 * Spec REQ §"PolicyReport and canonical value hashing" — deterministic report.
 */
data class PolicyReport(
    val policySetId: String,
    val resourceFingerprint: String,
    val results: Map<RuleKey, RuleEvaluation>,
    /**
     * B4.5 / ADR-0015 — author-declared severity per rule.
     *
     * Only rules that DECLARED a severity appear here. An absent key is a
     * meaningful "not declared" and must never be read as a default, least of
     * all as `INFO`: `PolicyDiff` emits `SEVERITY_CHANGED` only when both
     * sides carry a value and they differ.
     */
    val severities: Map<RuleKey, RuleSeverity> = emptyMap(),
) {

    /** Combined hex-encoded SHA-256 over length-prefixed identity, corpus, and sorted results. */
    val digest: String by lazy {
        val serialized = buildString {
            append(encodeParts(policySetId, resourceFingerprint))
            val sorted = results.entries.sortedBy { it.key.value }
            append(sorted.size).append(':')
            sorted.forEach { (key, evaluation) ->
                append(encodeParts(key.value, canonicalEvaluation(evaluation)))
            }
        }
        ValueDigest.sha256Hex(serialized.toByteArray(Charsets.UTF_8))
    }

    private fun canonicalEvaluation(ev: RuleEvaluation): String = when (ev) {
        RuleEvaluation.Passed -> "passed"
        RuleEvaluation.NotApplicable -> "not-applicable"
        is RuleEvaluation.Violated -> encodeViolations("violated", ev.violations)
        is RuleEvaluation.Error -> encodeViolations("error", ev.violations)
    }

    private fun encodeViolations(kind: String, violations: List<PolicyViolation>): String {
        val sorted = violations.sortedWith(compareBy({ it.code.ordinal }, { it.location.toString() }))
        return kind + sorted.size + ":" + sorted.joinToString("") { violation ->
            encodeParts(
                violation.code.name,
                violation.location.toString(),
                violation.message,
                violation.expected,
                violation.actual,
            )
        }
    }

    private fun encodeParts(vararg parts: String?): String = parts.joinToString("") { part ->
        if (part == null) "-1:" else "${part.length}:$part"
    }
}
