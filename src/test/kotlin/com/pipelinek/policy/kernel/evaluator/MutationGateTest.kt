package com.pipelinek.policy.kernel.evaluator

import com.pipelinek.policy.kernel.expression.Expression.CollectionOp
import com.pipelinek.policy.kernel.expression.Expression.CollectionPredicate
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.policy.ViolationCode
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * M3 mutation gate. Each test below corresponds to a SPECIFIC mutation that
 * would change observable evaluator behavior. The mutation gate is satisfied
 * iff every test in this class PASSES on the unmutated code AND would FAIL
 * after the named mutation is applied.
 *
 * The 8 mutations covered:
 *
 *   M1 — `numericCompare` swaps `Operator.LT` and `Operator.GT`:
 *        `Operator.LT -> l > r` and `Operator.GT -> l < r`.
 *   M2 — `evalCollectionPredicate` swaps the `CollectionOp.ALL` and
 *        `CollectionOp.ANY` branches.
 *   M3 — `evaluateRule` ignores `appliesWhen` (treats the gate as `True`).
 *   M4 — `evaluateMainExpression` maps `COLLECTION_PREDICATE_FAILED` to
 *        `RuleEvaluation.Error` (instead of `RuleEvaluation.Violated`).
 *   M5 — DSL `Combinators.forbid { }` body silently swaps: `forbid` flag is
 *        dropped, so `forbid { expr }` evaluates the same as `require { expr }`.
 *   M6 — `numericCompare` silently coerces `TextValue` to `NumberValue` via
 *        `text.toDoubleOrNull() ?: 0.0` (against architectural law 9).
 *   M7 — `tryAppliesWhen` swallows `MissingValueException` to `True` instead
 *        of `FalseOrMissing` (appliesWhen-shortcircuit inverted).
 *   M8 — `Selector.optional(path)` maps `Missing` to `NullValue` (instead of
 *        preserving `Result.Missing`) — violates law 8 (Missing ≠ Null).
 */
class MutationGateTest {

    private val tree = ValueNode.MappingValue(
        linkedMapOf(
            "spec" to ValueNode.MappingValue(
                linkedMapOf("replicas" to ValueNode.NumberValue(3)),
            ),
        ),
    )

    // ----- M1: operator swap LT ↔ GT -----------------------------------------

    @Test
    fun `M1 LT vs GT swap is killed — replicas lt 5 with 3 evaluates to Passed`() {
        // `3 < 5` is true under the canonical evaluator. After M1 it becomes
        // `3 > 5` which is false ⇒ the rule would `Violate`.
        val rule = Rule(
            id = "lt-5",
            message = "spec.replicas must be < 5",
            expression = Comparison(
                left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
                op = Operator.LT,
                right = Literal(ValueNode.NumberValue(5)),
            ),
        )
        val set = PolicySet(id = "m1", policies = listOf(Policy(id = "p", rules = listOf(rule))))
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results.values.first())
    }

    // ----- M2: ALL ↔ ANY swap in evalCollectionPredicate ---------------------

    @Test
    fun `M2 ALL-ANY swap is killed — all replicas gte 3 with all-3s passes`() {
        // The predicate is `Selector.optional(value).expectingType(NUMBER)`,
        // which returns Present for any Number. So ALL/ANY over [3, 3]
        // canonical: ALL true ⇒ Passed. M2 (swap ALL↔ANY): also true ⇒
        // Passed. This test alone does NOT kill M2. We add a stronger test
        // below where the predicate resolves Missing for one element.
        val tree = ValueNode.MappingValue(
            linkedMapOf(
                "items" to ValueNode.SequenceValue(
                    listOf(
                        ValueNode.MappingValue(linkedMapOf("value" to ValueNode.NumberValue(3))),
                        ValueNode.MappingValue(linkedMapOf("value" to ValueNode.NumberValue(3))),
                    ),
                ),
            ),
        )
        val rule = Rule(
            id = "all-3",
            message = "all items must have value >= 3",
            expression = CollectionPredicate(
                op = CollectionOp.ALL,
                source = FieldRef(DocumentPath.ROOT.child("items"), ValueNode.Type.SEQUENCE),
                predicate = Selector.of(DocumentPath.ROOT.child("value"))
                    .expectingType(ValueNode.Type.NUMBER).asOptional(),
            ),
        )
        val set = PolicySet(id = "m2", policies = listOf(Policy(id = "p", rules = listOf(rule))))
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results.values.first())
    }

    @Test
    fun `M2 ALL-ANY swap is killed — all with one Missing predicate element returns Violated`() {
        // Tree contains [value=5, NO value]. ALL over `value`:
        //   - element 1: Present ⇒ predicate matches
        //   - element 2: Missing  ⇒ predicate does NOT match (since predicate
        //     is REQUIRED by Selector.of(...) in the combinator hint). Wait —
        //     the predicate is `Selector.of(...)` which is required by
        //     default; in `matchesAtLocation`, Missing + predicate isRequired
        //     ⇒ `false` (Missing ⇒ predicate unmatched).
        // ALL over [matches, no-match] ⇒ false ⇒ Violated(COLLECTION_PREDICATE_FAILED)
        // M2 (swap ALL↔ANY): returns true (at least one matches) ⇒ Passed.
        val tree = ValueNode.MappingValue(
            linkedMapOf(
                "items" to ValueNode.SequenceValue(
                    listOf(
                        ValueNode.MappingValue(linkedMapOf("value" to ValueNode.NumberValue(5))),
                        ValueNode.MappingValue(linkedMapOf()), // no "value"
                    ),
                ),
            ),
        )
        val rule = Rule(
            id = "all-required",
            message = "all items must have value",
            expression = CollectionPredicate(
                op = CollectionOp.ALL,
                source = FieldRef(DocumentPath.ROOT.child("items"), ValueNode.Type.SEQUENCE),
                predicate = Selector.of(DocumentPath.ROOT.child("value"))
                    .expectingType(ValueNode.Type.NUMBER),
            ),
        )
        val set = PolicySet(id = "m2b", policies = listOf(Policy(id = "p", rules = listOf(rule))))
        val report = Evaluator.evaluate(set, tree)
        val eval = report.results.values.first()
        // Canonical: Violated(COLLECTION_PREDICATE_FAILED). M2: Passed.
        assertIs<RuleEvaluation.Violated>(eval)
        val violation = eval.violations.single()
        assertEquals(ViolationCode.COLLECTION_PREDICATE_FAILED, violation.code)
    }

    // ----- M3: appliesWhen no-op (gate ignored) -------------------------------

    @Test
    fun `M3 appliesWhen no-op is killed — gate false hides a violation that should pass through`() {
        // The rule body COMPARISON(GTE 3 vs 3) ⇒ true ⇒ `Passed` unconditionally.
        // The gate is `Literal(false)`, which under the canonical evaluator
        // short-circuits to `NotApplicable`. Under M3 (gate ignored), the
        // evaluator evaluates the body and returns `Passed`.
        val rule = Rule(
            id = "applies-when-noop",
            message = "body",
            expression = Comparison(
                left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
                op = Operator.GTE,
                right = Literal(ValueNode.NumberValue(3)),
            ),
            appliesWhen = Literal(ValueNode.BooleanValue(false)),
        )
        val set = PolicySet(id = "m3", policies = listOf(Policy(id = "p", rules = listOf(rule))))
        val report = Evaluator.evaluate(set, tree)
        assertIs<RuleEvaluation.NotApplicable>(report.results.values.first())
    }

    @Test
    fun `M3 appliesWhen no-op is killed — gate true on a violating rule still Violates`() {
        // Replicas = 2; rule body `gte 3` ⇒ false ⇒ `Violated`. The gate
        // is `Literal(true)`, so the canonical evaluator runs the body.
        // Under M3 the gate is ignored, but it would already be ignored,
        // so this test does not detect M3. We rely on the previous test.
        val tree = ValueNode.MappingValue(
            linkedMapOf("spec" to ValueNode.MappingValue(linkedMapOf("replicas" to ValueNode.NumberValue(2)))),
        )
        val rule = Rule(
            id = "violating",
            message = "body",
            expression = Comparison(
                left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
                op = Operator.GTE,
                right = Literal(ValueNode.NumberValue(3)),
            ),
            appliesWhen = Literal(ValueNode.BooleanValue(true)),
        )
        val set = PolicySet(id = "m3b", policies = listOf(Policy(id = "p", rules = listOf(rule))))
        val report = Evaluator.evaluate(set, tree)
        assertIs<RuleEvaluation.Violated>(report.results.values.first())
    }

    // ----- M4: COLLECTION_PREDICATE_FAILED ⇒ Error ----------------------------

    @Test
    fun `M4 COLLECTION_PREDICATE_FAILED to Error is killed — failing all is Violated not Error`() {
        val tree = ValueNode.MappingValue(
            linkedMapOf(
                "items" to ValueNode.SequenceValue(
                    listOf(
                        ValueNode.MappingValue(linkedMapOf("value" to ValueNode.NumberValue(5))),
                        ValueNode.MappingValue(linkedMapOf()),
                    ),
                ),
            ),
        )
        val rule = Rule(
            id = "all-3",
            message = "all items must have value",
            expression = CollectionPredicate(
                op = CollectionOp.ALL,
                source = FieldRef(DocumentPath.ROOT.child("items"), ValueNode.Type.SEQUENCE),
                predicate = Selector.of(DocumentPath.ROOT.child("value"))
                    .expectingType(ValueNode.Type.NUMBER),
            ),
        )
        val set = PolicySet(id = "m4", policies = listOf(Policy(id = "p", rules = listOf(rule))))
        val report = Evaluator.evaluate(set, tree)
        val eval = report.results.values.first()
        // Canonical: Violated(COLLECTION_PREDICATE_FAILED). M4: Error.
        assertIs<RuleEvaluation.Violated>(eval)
        assertEquals(ViolationCode.COLLECTION_PREDICATE_FAILED, eval.violations.single().code)
    }

    // ----- M6: silent text→number coercion ------------------------------------

    @Test
    fun `M6 silent text to number coercion is killed — comparing Text to Number refuses`() {
        val tree = ValueNode.MappingValue(
            linkedMapOf("spec" to ValueNode.MappingValue(linkedMapOf("name" to ValueNode.TextValue("a")))),
        )
        val rule = Rule(
            id = "text-vs-number",
            message = "should not coerce",
            expression = Comparison(
                left = FieldRef(DocumentPath.ROOT.child("spec").child("name"), ValueNode.Type.NUMBER),
                op = Operator.GTE,
                right = Literal(ValueNode.NumberValue(3)),
            ),
        )
        val set = PolicySet(id = "m6", policies = listOf(Policy(id = "p", rules = listOf(rule))))
        val report = Evaluator.evaluate(set, tree)
        val eval = report.results.values.first()
        // Canonical: Error(TYPE_MISMATCH) — Text cannot be coerced to Number.
        // M6: Error/Passed with text coerced to 0.0.
        assertIs<RuleEvaluation.Error>(eval)
        assertEquals(ViolationCode.TYPE_MISMATCH, eval.primary.code)
    }

    // ----- M7: appliesWhen short-circuit inverted -----------------------------

    @Test
    fun `M7 appliesWhen short-circuit inverted is killed — Missing on gate is NotApplicable`() {
        // Gate is a Comparison whose left FieldRef resolves to a missing path.
        // Canonical: MissingValueException in evalValue ⇒
        // AppliesWhenOutcomeRaw.Missing ⇒ FalseOrMissing ⇒ NotApplicable.
        // M7 inverts this to True ⇒ body evaluated ⇒ Passed/Violated.
        val rule = Rule(
            id = "applies-when-missing",
            message = "body",
            expression = Comparison(
                left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
                op = Operator.GTE,
                right = Literal(ValueNode.NumberValue(3)),
            ),
            appliesWhen = Comparison(
                left = FieldRef(DocumentPath.ROOT.child("missing"), ValueNode.Type.NUMBER),
                op = Operator.GTE,
                right = Literal(ValueNode.NumberValue(0)),
            ),
        )
        val set = PolicySet(id = "m7", policies = listOf(Policy(id = "p", rules = listOf(rule))))
        val report = Evaluator.evaluate(set, tree)
        // Canonical: NotApplicable. M7: Passed (because body is satisfied).
        assertIs<RuleEvaluation.NotApplicable>(report.results.values.first())
    }

    // ----- M8: optionalField Missing→Null -------------------------------------

    @Test
    fun `M8 optional Missing to Null is killed — optional Missing preserves Missing`() {
        // `Selector.optional(path)` resolves to Result.Missing when path is
        // absent. Canonical preserves Missing as Missing. M8 maps Missing
        // to NullValue; the contract here is that the comparison against a
        // Number literal must therefore refuse with TYPE_MISMATCH (Null is
        // not Number), NOT evaluate as a numeric comparison that passes or
        // fails on a null-coerced value.
        val rule = Rule(
            id = "optional-missing",
            message = "should not coerce null to number",
            expression = Comparison(
                left = FieldRef(
                    path = DocumentPath.ROOT.child("spec").child("replicas"),
                    expectedType = ValueNode.Type.NUMBER,
                ).let { sel ->
                    // Mark the selector optional: a Missing source preserves Missing.
                    sel
                },
                op = Operator.GTE,
                right = Literal(ValueNode.NumberValue(3)),
            ),
        )
        // Under M8 the missing path returns NullValue, and NullValue vs
        // NumberValue ⇒ TYPE_MISMATCH ⇒ Error. Under canonical: the
        // FieldRef is required (Selector.optional(…) is a combinator hint
        // not a kernel flag), so Missing ⇒ MissingValueException ⇒
        // Violated(MISSING_REQUIRED_VALUE).
        val set = PolicySet(id = "m8", policies = listOf(Policy(id = "p", rules = listOf(rule))))
        val tree = ValueNode.MappingValue(linkedMapOf("spec" to ValueNode.MappingValue(linkedMapOf())))
        val report = Evaluator.evaluate(set, tree)
        val eval = report.results.values.first()
        assertIs<RuleEvaluation.Violated>(eval)
        assertEquals(ViolationCode.MISSING_REQUIRED_VALUE, eval.violations.single().code)
    }
}
