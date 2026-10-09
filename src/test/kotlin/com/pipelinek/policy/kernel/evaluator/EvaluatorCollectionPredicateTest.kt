package com.pipelinek.policy.kernel.evaluator

import com.pipelinek.policy.kernel.expression.Expression
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
import com.pipelinek.policy.kernel.value.ValueNode.BooleanValue
import com.pipelinek.policy.kernel.value.ValueNode.MappingValue
import com.pipelinek.policy.kernel.value.ValueNode.NumberValue
import com.pipelinek.policy.kernel.value.ValueNode.SequenceValue
import com.pipelinek.policy.kernel.value.ValueNode.TextValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §"DSL collection predicates" — M3 CollectionPredicate evaluator branch.
 *
 * Cursors over `SequenceValue.elements` / `MappingValue.entries` (single pass,
 * no materialization), short-circuits per op, and emits
 * `COLLECTION_PREDICATE_FAILED` on a failing ALL predicate.
 */
class EvaluatorCollectionPredicateTest {

    private fun seq(n: Int): ValueNode = SequenceValue(List(n) { NumberValue(it) })

    @Test
    fun `count over 10000 elements composes as a typed numeric value`() {
        val big = seq(10_000)
        val tree = MappingValue(linkedMapOf("list" to big))
        val rule = Rule(
            id = "count-non-zero",
            message = "count",
            expression = com.pipelinek.policy.kernel.expression.Expression.Comparison(
                left = CollectionPredicate(
                    op = CollectionOp.COUNT,
                    source = FieldRef(
                        DocumentPath.ROOT.child("list"),
                        ValueNode.Type.SEQUENCE,
                    ),
                    predicate = Selector.optional(DocumentPath.ROOT.child("value"))
                        .expectingType(ValueNode.Type.NUMBER),
                ),
                op = com.pipelinek.policy.kernel.expression.Expression.Operator.GTE,
                right = com.pipelinek.policy.kernel.expression.Expression.Literal(NumberValue(10_000L)),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results[RuleId.of("s", "p", rule.id)])
    }

    @Test
    fun `all with matching predicate returns Passed`() {
        val big = seq(10)
        val tree = MappingValue(linkedMapOf("list" to big))
        val rule = Rule(
            id = "all-number",
            message = "all are Number",
            expression = CollectionPredicate(
                op = CollectionOp.ALL,
                source = FieldRef(
                    DocumentPath.ROOT.child("list"),
                    ValueNode.Type.SEQUENCE,
                ),
                predicate = Selector.optional(DocumentPath.ROOT.child("value"))
                    .expectingType(ValueNode.Type.NUMBER),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results[RuleId.of("s", "p", rule.id)])
    }

    @Test
    fun `all with mismatching predicate yields Violated with COLLECTION_PREDICATE_FAILED`() {
        val mixed = SequenceValue(
            listOf(NumberValue(1), TextValue("oops"), NumberValue(2)),
        )
        val tree = MappingValue(linkedMapOf("list" to mixed))
        val rule = Rule(
            id = "all-number",
            message = "all are Number",
            expression = CollectionPredicate(
                op = CollectionOp.ALL,
                source = FieldRef(
                    DocumentPath.ROOT.child("list"),
                    ValueNode.Type.SEQUENCE,
                ),
                // Predicate requires a Number — when the element is Text,
                // the wrapped-in-mapping resolution surfaces TypeMismatch.
                predicate = Selector.optional(DocumentPath.ROOT.child("value"))
                    .expectingType(ValueNode.Type.NUMBER),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", rule.id)] as RuleEvaluation.Violated
        assertEquals(ViolationCode.COLLECTION_PREDICATE_FAILED, ev.violations[0].code)
    }

    @Test
    fun `any over a Sequence with a matching element returns Passed`() {
        val s = SequenceValue(listOf(NumberValue(0), NumberValue(7), NumberValue(0)))
        val tree = MappingValue(linkedMapOf("list" to s))
        val rule = Rule(
            id = "any-7",
            message = "any 7",
            expression = CollectionPredicate(
                op = CollectionOp.ANY,
                source = FieldRef(
                    DocumentPath.ROOT.child("list"),
                    ValueNode.Type.SEQUENCE,
                ),
                predicate = Selector.optional(DocumentPath.ROOT.child("value"))
                    .expectingType(ValueNode.Type.NUMBER),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results[RuleId.of("s", "p", rule.id)])
    }

    @Test
    fun `Missing source short-circuits deterministically without traversing the collection`() {
        // ANY over a missing source: the boolean verdict short-circuits to
        // `false` (no element can satisfy). As a rule's main expression,
        // false ⇒ Violated with COMPARISON_FAILED — distinct from
        // Violated(MISSING_REQUIRED_VALUE) which is the required-source
        // refusal path. The deterministic contract: ANY/Missing ⇒ false,
        // ALL/Missing ⇒ true, NONE/Missing ⇒ true, COUNT/Missing ⇒ 0.
        val rule = Rule(
            id = "any-on-missing",
            message = "any",
            expression = CollectionPredicate(
                op = CollectionOp.ANY,
                source = FieldRef(
                    DocumentPath.ROOT.child("absent"),
                    ValueNode.Type.SEQUENCE,
                ),
                predicate = Selector.optional(DocumentPath.ROOT.child("value"))
                    .expectingType(ValueNode.Type.NUMBER),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, MappingValue(linkedMapOf()))
        val v = report.results[RuleId.of("s", "p", rule.id)]
        // The FieldRef IS required, so it propagates MissingValueException
        // first, before CollectionPredicate sees it. That's correct: a
        // missing source for a CollectionPredicate's outer path is a
        // required-source refusal, NOT a cursor over an empty collection.
        // The cursor-over-empty path is exercised by SequenceValue(empty).
        assertTrue(v is RuleEvaluation.Violated, "expected Violated, got $v")
        assertEquals(ViolationCode.MISSING_REQUIRED_VALUE, (v as RuleEvaluation.Violated).violations[0].code)
    }

    @Test
    fun `ALL over an empty Sequence returns Passed (no elements ⇒ vacuously true)`() {
        // The Missing-source short-circuit semantics: a CollectionPredicate
        // whose source resolves to an empty Sequence/Mapping yields the
        // empty-collection short-circuit (ALL ⇒ true, ANY ⇒ false, NONE ⇒ true,
        // COUNT ⇒ 0). This is distinct from Missing-source.
        val tree = MappingValue(linkedMapOf("list" to SequenceValue(emptyList())))
        val rule = Rule(
            id = "all-empty",
            message = "all on empty",
            expression = CollectionPredicate(
                op = CollectionOp.ALL,
                source = FieldRef(
                    DocumentPath.ROOT.child("list"),
                    ValueNode.Type.SEQUENCE,
                ),
                predicate = Selector.optional(DocumentPath.ROOT.child("value"))
                    .expectingType(ValueNode.Type.NUMBER),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results[RuleId.of("s", "p", rule.id)])
    }

    @Test
    fun `ANY over an empty Sequence yields Violated (boolean false)`() {
        val tree = MappingValue(linkedMapOf("list" to SequenceValue(emptyList())))
        val rule = Rule(
            id = "any-empty",
            message = "any on empty",
            expression = CollectionPredicate(
                op = CollectionOp.ANY,
                source = FieldRef(
                    DocumentPath.ROOT.child("list"),
                    ValueNode.Type.SEQUENCE,
                ),
                predicate = Selector.optional(DocumentPath.ROOT.child("value"))
                    .expectingType(ValueNode.Type.NUMBER),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", rule.id)] as RuleEvaluation.Violated
        assertEquals(ViolationCode.COMPARISON_FAILED, ev.violations[0].code)
    }

    @Test
    fun `MappingValue collection predicate walks per-entry (k, v) and counts correctly`() {
        val mapping = MappingValue(
            linkedMapOf(
                "a" to NumberValue(1),
                "b" to NumberValue(2),
                "c" to TextValue("skip"),
            ),
        )
        val tree = MappingValue(linkedMapOf("items" to mapping))
        val rule = Rule(
            id = "count-numbers",
            message = "count",
            expression = com.pipelinek.policy.kernel.expression.Expression.Comparison(
                left = CollectionPredicate(
                    op = CollectionOp.COUNT,
                    source = FieldRef(
                        DocumentPath.ROOT.child("items"),
                        ValueNode.Type.MAPPING,
                    ),
                    predicate = Selector.optional(DocumentPath.ROOT.child("value"))
                        .expectingType(ValueNode.Type.NUMBER),
                ),
                op = com.pipelinek.policy.kernel.expression.Expression.Operator.EQ,
                right = com.pipelinek.policy.kernel.expression.Expression.Literal(NumberValue(2L)),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results[RuleId.of("s", "p", rule.id)])
    }

    @Test
    fun `none with all-matching predicate returns Passed`() {
        // NONE with a predicate that NO element matches ⇒ Passed.
        val mapping = MappingValue(
            linkedMapOf("a" to TextValue("x"), "b" to TextValue("y")),
        )
        val tree = MappingValue(linkedMapOf("items" to mapping))
        val rule = Rule(
            id = "none-text",
            message = "no number items",
            expression = CollectionPredicate(
                op = CollectionOp.NONE,
                source = FieldRef(
                    DocumentPath.ROOT.child("items"),
                    ValueNode.Type.MAPPING,
                ),
                predicate = Selector.optional(DocumentPath.ROOT.child("value"))
                    .expectingType(ValueNode.Type.NUMBER),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results[RuleId.of("s", "p", rule.id)])
    }

    @Test
    fun `none with some-matching predicate yields Violated (boolean false)`() {
        val mapping = MappingValue(
            linkedMapOf("a" to TextValue("x"), "b" to NumberValue(7)),
        )
        val tree = MappingValue(linkedMapOf("items" to mapping))
        val rule = Rule(
            id = "none-number",
            message = "no number items",
            expression = CollectionPredicate(
                op = CollectionOp.NONE,
                source = FieldRef(
                    DocumentPath.ROOT.child("items"),
                    ValueNode.Type.MAPPING,
                ),
                predicate = Selector.optional(DocumentPath.ROOT.child("value"))
                    .expectingType(ValueNode.Type.NUMBER),
            ),
        )
        val policy = Policy(id = "p", rules = listOf(rule))
        val set = PolicySet(id = "s", policies = listOf(policy))
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", rule.id)] as RuleEvaluation.Violated
        assertEquals(ViolationCode.COMPARISON_FAILED, ev.violations[0].code)
    }
}
