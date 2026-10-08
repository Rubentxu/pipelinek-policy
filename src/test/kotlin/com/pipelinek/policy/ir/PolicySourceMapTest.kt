package com.pipelinek.policy.ir

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.bundle.PolicySourceMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** WU-7: source-map lookup must be stable and checkout-independent. */
class PolicySourceMapTest {
    @Test
    fun `lookup by stable id returns the source ref regardless of checkout`() {
        val ref = PolicySourceRef("policies/replicas.policy.kts", 10, 5, 12, 40, "rule[r]")
        val map = PolicySourceMap(mapOf("policy.p.rule.r" to ref))
        // The lookup key is checkout-independent: derived from stable ids, not file paths.
        assertEquals(ref, map.lookup("policy.p.rule.r"))
        assertEquals("policies/replicas.policy.kts", map.lookup("policy.p.rule.r")?.file)
        assertNull(map.lookup("missing"))
    }

    @Test
    fun `bundle exposes the document source refs through the source map`() {
        val lowered = PolicyIrLowerer.lower(
            com.pipelinek.policy.kernel.policy.PolicySet(
                "p",
                listOf(
                    com.pipelinek.policy.kernel.policy.Policy(
                        "policy",
                        listOf(
                            com.pipelinek.policy.kernel.policy.Rule(
                                "r", "ok",
                                com.pipelinek.policy.kernel.expression.Expression.Literal(
                                    com.pipelinek.policy.kernel.value.ValueNode.BooleanValue(true),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val doc = lowered.copy(
            sourceRefs = mapOf(
                "policy.policy.rule.r" to PolicySourceRef("a.kts", 1, 1, 2, 2, "rule[r]"),
            ),
        )
        val bundle = PolicyBundle(doc)
        assertEquals("a.kts", bundle.sourceMap.lookup("policy.policy.rule.r")?.file)
        assertEquals(1, bundle.sourceMap.lookup("policy.policy.rule.r")?.startLine)
    }
}
