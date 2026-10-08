package com.pipelinek.policy.ir

import com.pipelinek.policy.bundle.PolicyBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WU-10: deterministic pack reproducibility.
 * Building the same bundle twice must yield byte-identical packs (same artifact digest).
 */
class BundleReproducibilityTest {
    private fun document() = PolicyIrLowerer.lower(
        com.pipelinek.policy.kernel.policy.PolicySet(
            "repro",
            listOf(
                com.pipelinek.policy.kernel.policy.Policy(
                    "p",
                    listOf(
                        com.pipelinek.policy.kernel.policy.Rule(
                            "r", "must hold",
                            com.pipelinek.policy.kernel.expression.Expression.Literal(
                                com.pipelinek.policy.kernel.value.ValueNode.BooleanValue(true),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        functions = listOf("z.fn", "a.fn"),
    )

    @Test
    fun `packing the same document twice yields byte-identical bundles`() {
        val first = PolicyBundle(document(), metadata = mapOf("author" to "uat", "z" to "1"))
        val second = PolicyBundle(document(), metadata = mapOf("z" to "1", "author" to "uat"))
        assertTrue(first.pack().contentEquals(second.pack()))
        assertEquals(first.manifest.artifactDigest, second.manifest.artifactDigest)
        assertEquals(first.manifest.semanticDigest, second.manifest.semanticDigest)
    }

    @Test
    fun `manifest functions are canonically sorted`() {
        val bundle = PolicyBundle(document())
        assertEquals(listOf("a.fn", "z.fn"), bundle.manifest.requiredFunctions)
    }
}
