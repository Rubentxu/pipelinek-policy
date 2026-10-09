package com.pipelinek.policy.bundle

import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.ir.LegacyPolicyIrJsonV1
import com.pipelinek.policy.ir.PolicyIrDocument
import com.pipelinek.policy.kernel.evaluator.RuleKey
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
