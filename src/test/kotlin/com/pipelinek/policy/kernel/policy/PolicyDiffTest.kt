package com.pipelinek.policy.kernel.policy

import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M7 REQ-M7-05 — semantic diff A/B over the same corpus.
 * Scenarios 05a-e from M7_SPECIFICATION.
 */
class PolicyDiffTest {

    private val now: Instant = Instant.parse("2026-06-01T12:00:00Z")

    private fun rule(id: String, expected: Long) = Rule(
        id = id,
        message = "spec.replicas must be >= $expected",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
            op = Operator.GTE,
            right = Literal(ValueNode.NumberValue(expected)),
        ),
    )

    private val tree: ValueNode = ValueNode.MappingValue(
        mapOf("spec" to ValueNode.MappingValue(mapOf("replicas" to ValueNode.NumberValue(1)))),
    )

    private fun report(vararg rules: Rule, policyId: String = "p1"): PolicyReport =
        Evaluator.evaluate(PolicySet(policyId, listOf(Policy(policyId, rules.toList()))), tree)

    // --- 05a: new rule violated in B only => NEW_VIOLATION ---

    @Test
    fun `05a rule added in B and violated yields NEW_VIOLATION without privilege expansion`() {
        val a = report(rule("base", 3)) // replicas=1 violates base
        val b = report(rule("base", 3), rule("extra", 2))

        val diff = PolicyDiff.of(a, b)

        val entry = diff.entries.single()
        assertEquals(DiffCategory.NEW_VIOLATION, entry.category)
        assertEquals("extra", entry.ruleId)
        assertEquals(false, entry.privilegeExpansion)
    }

    // --- 05b: mandatory violation disappears in B => RESOLVED_VIOLATION + expansion ---

    @Test
    fun `05b mandatory violation resolved in B is flagged as privilege expansion`() {
        val a = report(rule("floor", 3), rule("other", 2))
        val b = report(rule("other", 2)) // floor dropped; replicas=1 no longer violates it

        val diff = PolicyDiff.of(a, b)

        val entry = diff.entries.single()
        assertEquals(DiffCategory.RESOLVED_VIOLATION, entry.category)
        assertEquals("floor", entry.ruleId)
        assertTrue(entry.privilegeExpansion, "resolved mandatory violation must flag privilege expansion")
    }

    // --- 05c: deterministic digest ---

    @Test
    fun `05c diff digest is deterministic across executions and input order`() {
        val a = report(rule("base", 3))
        val b = report(rule("base", 3), rule("x", 2), rule("y", 5))

        val d1 = PolicyDiff.of(a, b)
        val d2 = PolicyDiff.of(a, b)

        assertEquals(d1.diffDigest, d2.diffDigest)
        // Canonical digest is insensitive to entry list order.
        val shuffled = PolicyDiff(d1.entries.reversed())
        assertEquals(d1.diffDigest, shuffled.diffDigest)
    }

    // --- 05d: corpus mismatch refused ---

    @Test
    fun `05d diff over different corpora is a typed refusal`() {
        val otherTree: ValueNode = ValueNode.MappingValue(
            mapOf("spec" to ValueNode.MappingValue(mapOf("replicas" to ValueNode.NumberValue(99)))),
        )
        val a = report(rule("base", 3))
        val b = Evaluator.evaluate(
            PolicySet("p1", listOf(Policy("p1", listOf(rule("base", 3))))),
            otherTree,
        )

        val refusal = assertFailsWith<PolicyDiffRefusal.CorpusMismatch> { PolicyDiff.of(a, b) }
        assertEquals(a.resourceFingerprint, refusal.fingerprintA)
        assertEquals(b.resourceFingerprint, refusal.fingerprintB)
    }

    // --- 05e: falsification — insertion-order digest must NOT be the digest ---

    @Test
    fun `05e mutation-contract - digest must not depend on entry insertion order`() {
        val a = report(rule("base", 3))
        val b = report(rule("base", 3), rule("x", 2), rule("y", 5))
        val diff = PolicyDiff.of(a, b)
        assertTrue(diff.entries.size >= 2, "need multiple entries to exercise ordering")

        // A digest computed over insertion order (unsorted) would change when
        // the entries are reversed; the canonical one must not.
        val reversed = PolicyDiff(diff.entries.reversed())
        assertEquals(diff.diffDigest, reversed.diffDigest)
    }

    // --- waiver-aware diff: WAIVER_EFFECT_CHANGED ---

    @Test
    fun `waiver effect change between A and B yields WAIVER_EFFECT_CHANGED`() {
        val set = PolicySet("p1", listOf(Policy("p1", listOf(rule("base", 3)))))
        val a = Evaluator.evaluate(set, tree)
        val b = Evaluator.evaluate(set, tree)

        val resolver: (String) -> String? = { "payments" }
        val waiver = Waiver(
            id = "w1",
            policyId = "p1",
            ruleId = "base",
            subject = ResourceSubjectSelector("metadata.team", "payments"),
            reason = "legacy",
            issuer = "sec",
            notBefore = now.minusSeconds(3600),
            notAfter = now.plusSeconds(3600),
        )
        val appA = WaiverMatcher.apply(a, emptyList(), now, resolver)
        val appB = WaiverMatcher.apply(b, listOf(waiver), now, resolver)

        val diff = PolicyDiff.of(a, b, appA, appB)

        val entry = assertIs<DiffEntry>(diff.entries.single { it.category == DiffCategory.WAIVER_EFFECT_CHANGED })
        assertEquals("base", entry.ruleId)
        // The only raw-state-identical change is the waiver effect.
        assertEquals(1, diff.entries.size)
    }
}
