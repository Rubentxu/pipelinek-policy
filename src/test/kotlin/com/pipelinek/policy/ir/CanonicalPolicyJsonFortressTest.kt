package com.pipelinek.policy.ir

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.ParamValue
import com.pipelinek.policy.kernel.policy.PolicyLayer
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleRef
import com.pipelinek.policy.kernel.policy.Supersession
import com.pipelinek.policy.kernel.policy.SupersessionAuthority
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CanonicalPolicyJsonFortressTest {
    @Test
    fun `dataset references survive canonical IR round trip`() {
        val document = documentWith(Expression.DatasetRef("telemetry"))

        assertEquals(document, CanonicalPolicyJson.decode(CanonicalPolicyJson.encode(document)))
    }

    @Test
    fun `document and rule semantic fields survive canonical IR round trip`() {
        val rule = Rule(
            id = "rule",
            message = "must be enabled",
            expression = Expression.Literal(ValueNode.BooleanValue(true)),
            appliesWhen = Expression.Literal(ValueNode.BooleanValue(false)),
            code = "POLICY_DISABLED",
            expected = "enabled",
            actual = "disabled",
            params = mapOf("limit" to ParamValue.IntV(3)),
            supersession = Supersession(
                supersedes = RuleRef("policy", "old-rule"),
                reason = "replacement",
                authority = SupersessionAuthority(
                    issuer = "platform",
                    grantedLayers = setOf(PolicyLayer.ORGANIZATION),
                    grantDigest = "sha256:fortress",
                ),
                scope = "repository",
                validity = "current release",
            ),
        )
        val document = PolicyIrDocument(
            policySet = PolicySet("set", listOf(Policy("policy", listOf(rule)))),
            functions = listOf("a.fn", "z.fn"),
            parameters = mapOf("global-limit" to ParamValue.LongV(9L)),
            shapes = listOf(ShapeConstraint("items.count", ValueNode.Type.NUMBER, "schema")),
            sourceRefs = mapOf(
                "rule" to PolicySourceRef("policies/main.kt", 2, 3, 4, 5, "mainRule"),
            ),
            languageVersion = "m8",
        )

        assertEquals(document, CanonicalPolicyJson.decode(CanonicalPolicyJson.encode(document)))
    }

    @Test
    fun `collection selectors encode by stable path segments instead of object stringification`() {
        val first = documentWithSelector(
            Selector.of(DocumentPath.ROOT.child("a.b").child("c"))
                .expectingType(ValueNode.Type.TEXT)
                .asOptional(),
        )
        val equivalent = documentWithSelector(
            Selector.of(DocumentPath.ROOT.child("a.b").child("c"))
                .expectingType(ValueNode.Type.TEXT)
                .asOptional(),
        )
        val encoded = CanonicalPolicyJson.encode(first).decodeToString()

        assertContentEquals(CanonicalPolicyJson.encode(first), CanonicalPolicyJson.encode(equivalent))
        val expectedPredicate =
            "\"predicate\":{\"segments\":[\"a.b\",\"c\"],\"expectedType\":\"TEXT\",\"optional\":true}"
        assertTrue(encoded.contains(expectedPredicate), encoded)
        assertEquals(first, CanonicalPolicyJson.decode(CanonicalPolicyJson.encode(first)))
        assertFalse(encoded.contains("Selector@"))
    }

    @Test
    fun `source locations change artifact digest but not semantic digest`() {
        val first = documentWith(
            Expression.Literal(ValueNode.BooleanValue(true)),
            sourceFile = "checkout-one/policy.kt",
        )
        val second = documentWith(
            Expression.Literal(ValueNode.BooleanValue(true)),
            sourceFile = "checkout-two/policy.kt",
        )

        assertEquals(CanonicalPolicyJson.semanticDigest(first), CanonicalPolicyJson.semanticDigest(second))
        assertNotEquals(PolicyBundle(first).manifest.artifactDigest, PolicyBundle(second).manifest.artifactDigest)
    }

    @Test
    fun `decimal number carrier survives canonical IR round trip without double rounding`() {
        val document = documentWith(
            Expression.Literal(ValueNode.NumberValue(BigDecimal("0.1000000000000000001"))),
        )

        assertEquals(document, CanonicalPolicyJson.decode(CanonicalPolicyJson.encode(document)))
    }

    private fun documentWith(expression: Expression, sourceFile: String = "policy.kt") = PolicyIrDocument(
        policySet = PolicySet("set", listOf(Policy("policy", listOf(Rule("rule", "message", expression))))),
        sourceRefs = mapOf("rule" to PolicySourceRef(sourceFile, 1, 1, 1, 8, "rule")),
    )

    private fun documentWithSelector(selector: Selector) = documentWith(
        Expression.CollectionPredicate(
            op = Expression.CollectionOp.ANY,
            source = Expression.FieldRef(DocumentPath.ROOT.child("items"), ValueNode.Type.SEQUENCE),
            predicate = selector,
        ),
    )
}
