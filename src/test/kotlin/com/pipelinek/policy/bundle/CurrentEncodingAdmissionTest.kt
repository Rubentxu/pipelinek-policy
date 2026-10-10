package com.pipelinek.policy.bundle

import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.ir.LegacyPolicyIrJsonV1
import com.pipelinek.policy.ir.PolicyIrDocument
import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.CollectionOp
import com.pipelinek.policy.kernel.expression.Expression.CollectionPredicate
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.policy.ViolationCode
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B2.9 falsification: admitting the CURRENT IR encoding MUST NOT depend on the
 * LEGACY_V1 encoding being reproducible.
 *
 * `LegacyPolicyIrJsonV1.encode` legitimately refuses documents carrying a
 * `CollectionPredicate`. Before the fix, `BundleIrCanonicality.identify`
 * evaluated that encoding first and returned `null` on refusal, so a bundle
 * packed in the CURRENT format was rejected by `verifyPacked` for the sole
 * reason that no historical encoding could be produced.
 */
class CurrentEncodingAdmissionTest {

    private val items = ValueNode.MappingValue(
        linkedMapOf("items" to ValueNode.SequenceValue(listOf(ValueNode.NumberValue(5L)))),
    )

    @Test
    fun `current bundle with a collection predicate packs verifies and evaluates`() {
        val document = collectionPredicateDocument()

        val verified = BundleVerifier.verifyPacked(PolicyBundle(document).pack())

        assertEquals(
            BundleIrEncoding.CURRENT,
            BundleIrCanonicality.identify(CanonicalPolicyJson.encode(document), verified.bundle.document),
        )
        val evaluation = IrRuntimeAdapter.evaluate(verified, items)
        val anyPositive = checkNotNull(evaluation.report.results[key("any-positive")])
        assertEquals<RuleEvaluation>(RuleEvaluation.Passed, anyPositive)
        val violated = checkNotNull(evaluation.report.results[key("none-positive")])
        assertTrue(violated is RuleEvaluation.Violated, "expected a violation, got $violated")
        assertEquals(ViolationCode.COMPARISON_FAILED, violated.violations.single().code)
    }

    @Test
    fun `an unreproducible legacy encoding does not disqualify the current one`() {
        val document = collectionPredicateDocument()

        assertNull(
            LegacyPolicyIrJsonV1.encode(document),
            "legacy v1 must stay unreproducible for collection predicates",
        )
        assertEquals(
            BundleIrEncoding.CURRENT,
            BundleIrCanonicality.identify(CanonicalPolicyJson.encode(document), document),
        )
    }

    @Test
    fun `the two encodings are disjoint, so admission order cannot decide`() {
        // Why this test exists, and why it does NOT pin an order.
        //
        // The B2.9 fix put CURRENT before LEGACY_V1 in `identify`, and the
        // obvious way to guard that is to reorder the branches and watch a
        // test go red. Two attempts at that test stayed green, and the reason
        // is worth recording rather than deleting.
        //
        // `identify` compares the bundle's bytes against each writer's output.
        // The two writers are DISJOINT — a document either cannot be written
        // by the legacy writer, or produces bytes that differ from the current
        // ones (measured: a literal document is 200/200 identical, a
        // comparison is 322/316 and different). Given disjointness, at most
        // one comparison can ever succeed, so the order of the checks cannot
        // change the answer. The legacy-first ordering is an
        // observational equivalent, not a behavioural difference.
        //
        // So the property actually worth pinning is disjointness itself. If a
        // future writer change made the two encodings coincide, admission
        // would become order-dependent again and a bundle could be admitted
        // under the wrong encoding's manifest rules — which is the original
        // B2.9 defect class. This test fails if that ever happens.
        val collectionPredicate = collectionPredicateDocument()
        val plain = plainDocument()

        // (a) Unreproducible under legacy: the case the fix was written for.
        assertNull(
            LegacyPolicyIrJsonV1.encode(collectionPredicate),
            "collection predicates must stay unreproducible under legacy v1",
        )
        assertEquals(
            BundleIrEncoding.CURRENT,
            BundleIrCanonicality.identify(CanonicalPolicyJson.encode(collectionPredicate), collectionPredicate),
        )

        // (b) Reproducible under both, with DIFFERENT bytes. Disjointness.
        val currentBytes = CanonicalPolicyJson.encode(plain)
        val legacyBytes = checkNotNull(LegacyPolicyIrJsonV1.encode(plain)) {
            "a document without collection predicates must still be legacy-reproducible"
        }
        assertTrue(
            !currentBytes.contentEquals(legacyBytes),
            "the two encodings must never coincide, or admission becomes order-dependent",
        )

        // (c) Each encoding is recognised only on its own bytes, which is
        // what disjointness buys and what the manifest check downstream
        // depends on.
        assertEquals(
            BundleIrEncoding.CURRENT,
            BundleIrCanonicality.identify(currentBytes, plain),
        )
        assertEquals(
            BundleIrEncoding.LEGACY_V1,
            BundleIrCanonicality.identify(legacyBytes, plain),
        )
    }

    /**
     * A document reproducible under both writers, and whose two encodings
     * differ. The expression has to be a comparison: a bare literal encodes
     * identically under both writers, so the bytes coincide and nothing about
     * the order is observable. Measured, not assumed — a literal document gives
     * 200/200 equal bytes, a comparison gives 322/316.
     */
    private fun plainDocument(): PolicyIrDocument = PolicyIrDocument(
        policySet = PolicySet(
            id = "current-set",
            policies = listOf(
                Policy(
                    id = "plain",
                    rules = listOf(
                        Rule(
                            id = "always",
                            message = "always",
                            expression = Expression.Comparison(
                                FieldRef(DocumentPath.ROOT.child("a"), ValueNode.Type.TEXT),
                                Expression.Operator.TEXT_EQUALS,
                                Expression.Literal(ValueNode.TextValue("x")),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun key(ruleId: String) = RuleKey.of("current-set", "collections", ruleId)

    private fun collectionPredicateDocument(): PolicyIrDocument = PolicyIrDocument(
        policySet = PolicySet(
            id = "current-set",
            policies = listOf(
                Policy(
                    id = "collections",
                    rules = listOf(
                        Rule(
                            id = "any-positive",
                            message = "at least one element",
                            expression = predicate(CollectionOp.ANY),
                        ),
                        Rule(
                            id = "none-positive",
                            message = "no element at all",
                            expression = predicate(CollectionOp.NONE),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun predicate(op: CollectionOp): CollectionPredicate = CollectionPredicate(
        op = op,
        source = FieldRef(DocumentPath.ROOT.child("items"), ValueNode.Type.SEQUENCE),
        predicate = Selector.optional(DocumentPath.ROOT.child("value")).expectingType(ValueNode.Type.NUMBER),
    )
}
