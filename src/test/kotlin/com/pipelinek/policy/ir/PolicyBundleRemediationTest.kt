package com.pipelinek.policy.ir

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.bundle.RuntimeCapabilities
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PolicyBundleRemediationTest {
    private fun document(functions: List<String> = emptyList()) = PolicyIrDocument(
        PolicySet("p", listOf(Policy("policy", listOf(Rule("r", "ok", Expression.Comparison(
            Expression.FieldRef(DocumentPath.ROOT.child("replicas"), ValueNode.Type.NUMBER),
            Expression.Operator.GTE,
            Expression.Literal(ValueNode.NumberValue(3)),
        )))))), functions = functions)

    @Test
    fun `decode is independent of process local registry`() {
        val bytes = CanonicalPolicyJson.encode(document())
        val decoded = CanonicalPolicyJson.decode(bytes)
        assertEquals(
            CanonicalPolicyJson.semanticDigest(document()),
            CanonicalPolicyJson.semanticDigest(decoded),
        )
    }

    @Test
    fun `optional FieldRef and explicit Not survive canonical IR round trip`() {
        val expression = Expression.Not(
            Expression.Comparison(
                Expression.FieldRef(
                    DocumentPath.ROOT.child("metadata").child("team"),
                    ValueNode.Type.TEXT,
                    optional = true,
                ),
                Expression.Operator.TEXT_EQUALS,
                Expression.Literal(ValueNode.TextValue("blocked")),
            ),
        )
        val document = PolicyIrDocument(
            PolicySet("set", listOf(Policy("policy", listOf(Rule("rule", "forbid team", expression))))),
        )

        assertEquals(document, CanonicalPolicyJson.decode(CanonicalPolicyJson.encode(document)))
    }

    @Test fun `external pack admission accepts valid bytes and refuses corruption`() {
        val bundle = PolicyBundle(document())
        val bytes = bundle.pack()
        assertEquals(bundle.manifest.semanticDigest, BundleVerifier.verifyPacked(bytes).semanticDigest)
        val corrupted = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<IllegalArgumentException> { BundleVerifier.verifyPacked(corrupted) }
    }

    @Test fun `unsupported function is refused from external admission`() {
        val bytes = PolicyBundle(document(listOf("future.fn"))).pack()
        assertFailsWith<IllegalArgumentException> { BundleVerifier.verifyPacked(bytes, RuntimeCapabilities()) }
    }
}
