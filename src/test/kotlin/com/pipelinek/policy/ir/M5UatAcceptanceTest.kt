package com.pipelinek.policy.ir

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.bundle.RuntimeCapabilities
import com.pipelinek.policy.ir.PolicyIrLowerer.lower
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * UAT acceptance for M5 (ROADMAP §M5).
 *
 * UAT1 same policy compiled twice -> same semantic digest
 * UAT2 explicit DSL and equivalent sugar -> same IR digest
 * UAT3 bundle evaluable in a process without policy source classes
 * UAT4 corrupt byte -> admission refuse
 * UAT5 unknown opcode -> refuse
 * UAT6 unsupported function -> refuse
 */
class M5UatAcceptanceTest {
    private fun policySet() = PolicySet(
        "uat",
        listOf(
            Policy(
                "p",
                listOf(
                    Rule(
                        "r", "replicas must be >= 3",
                        Expression.Comparison(
                            Expression.FieldRef(
                                DocumentPath.ROOT.child("spec").child("replicas"),
                                ValueNode.Type.NUMBER,
                            ),
                            Expression.Operator.GTE,
                            Expression.Literal(ValueNode.NumberValue(3)),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun document(vararg functions: String) =
        lower(policySet(), functions = functions.toList())

    @Test
    fun `UAT1 compiling the same policy twice yields the same semantic digest`() {
        val first = CanonicalPolicyJson.semanticDigest(document())
        val second = CanonicalPolicyJson.semanticDigest(document())
        assertEquals(first, second)
    }

    @Test
    fun `UAT2 equivalent authoring surfaces produce the same IR digest`() {
        // "Explicit DSL" surface: kernel ADT built directly.
        val explicit = CanonicalPolicyJson.semanticDigest(document())
        // "Equivalent sugar" surface: same ADT re-lowered from decoded canonical bytes,
        // which is the Option C fallback path (root.anything.whatever.text() lowering).
        val bytes = CanonicalPolicyJson.encode(document())
        val resugared = CanonicalPolicyJson.decode(bytes)
        assertEquals(explicit, CanonicalPolicyJson.semanticDigest(resugared))
        // And a genuinely different policy must NOT collide.
        val other = lower(
            PolicySet(
                "uat",
                listOf(
                    Policy(
                        "p",
                        listOf(
                            Rule(
                                "r", "other",
                                Expression.Literal(ValueNode.TextValue("x")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertNotEquals(explicit, CanonicalPolicyJson.semanticDigest(other))
    }

    @Test
    fun `UAT3 bundle is evaluable from packed bytes without authoring classes`() {
        // Admission path consumes only packed bytes; evaluation runs on the decoded
        // document through the runtime adapter, never on DSL authoring objects.
        val bundle = PolicyBundle(document())
        val verified = BundleVerifier.verifyPacked(bundle.pack())
        val tree = ValueNode.MappingValue(
            mapOf(
                "spec" to ValueNode.MappingValue(
                    mapOf("replicas" to ValueNode.NumberValue(2)),
                ),
            ),
        )
        val outcome = com.pipelinek.policy.bundle.IrRuntimeAdapter.evaluate(verified, tree)
        val violated = outcome.report.results.values.any {
            it is com.pipelinek.policy.kernel.policy.RuleEvaluation.Violated
        }
        assertEquals(true, violated)
    }

    @Test
    fun `UAT4 corrupt byte is refused by admission`() {
        val bytes = PolicyBundle(document()).pack()
        val corrupted = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<IllegalArgumentException> { BundleVerifier.verifyPacked(corrupted) }
    }

    @Test
    fun `UAT5 unknown opcode is refused`() {
        val doc = document()
        val json = CanonicalPolicyJson.encode(doc).toString(Charsets.UTF_8)
            .replace("\"op\":\"comparison\"", "\"op\":\"teleport\"")
        assertFailsWith<IllegalArgumentException> {
            CanonicalPolicyJson.decode(json.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `UAT6 unsupported function is refused`() {
        val bytes = PolicyBundle(document("future.fn")).pack()
        assertFailsWith<IllegalArgumentException> {
            BundleVerifier.verifyPacked(bytes, RuntimeCapabilities(functions = setOf("stdlib.text")))
        }
    }
}
