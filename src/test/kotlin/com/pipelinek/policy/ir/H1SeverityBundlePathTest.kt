package com.pipelinek.policy.ir

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.DiffCategory
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicyDiff
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleSeverity
import com.pipelinek.policy.kernel.value.ValueNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UAT-H1-01 / UAT-H1-02 — severity must survive the path that makes the defect
 * observable.
 *
 * Why this file exists separately from `PolicyIrSeverityRoundTripTest`:
 * the round-trip tests exercise the canonical codec directly. That is the layer
 * where the loss was fixed, but it is NOT the layer an author or a PipelineK
 * step ever touches. The real path is
 *
 *   PolicySet (in memory) -> PolicyBundle.pack() -> bytes -> verifyPacked()
 *   -> VerifiedBundle -> IrRuntimeAdapter.evaluate()
 *
 * and nothing had ever asserted a declared severity across it. `M5UatAcceptanceTest`
 * walks that path for violations, and `PolicyDiffSeverityTest` asserts
 * `SEVERITY_CHANGED` on in-memory `PolicyReport`s. Neither asks where the
 * severity went. So the P0 could have been "fixed" in the codec while the
 * observable product still lied, and no test would have noticed.
 *
 * UAT-H1-01: a CRITICAL rule keeps CRITICAL across pack → verifyPacked →
 * evaluate.
 * UAT-H1-02: two bundles differing only in severity yield SEVERITY_CHANGED.
 *
 * These are written to fail against the pre-H1.1 codec, where both bundles
 * packed to identical bytes.
 */
class H1SeverityBundlePathTest {

    // --- UAT-H1-01 ---

    @Test
    fun `UAT-H1-01 CRITICAL severity survives pack verifyPacked evaluate`() {
        val verified = verifyPacked(criticalBundle())

        val report = IrRuntimeAdapter.evaluate(verified, violatingTree()).report

        val evaluation = report.results.values.single()
        assertTrue(evaluation.violations.isNotEmpty(), "the rule must violate for this test to mean anything")
        val declared = report.severities[ruleKey]
        assertEquals(
            RuleSeverity.CRITICAL,
            declared,
            "severity declared CRITICAL must arrive CRITICAL through the packed bundle path",
        )
    }

    /**
     * The falsifying companion. Before the fix, the bundle bytes for a CRITICAL
     * rule and an INFO rule were byte-identical, so no amount of evaluation
     * could recover the declared severity: the information was gone before the
     * runtime adapter ever saw the document.
     */
    @Test
    fun `UAT-H1-01 the packed bytes of two severities are distinguishable`() {
        val critical = PolicyBundle(criticalBundle()).pack()
        val info = PolicyBundle(bundleWithSeverity(RuleSeverity.INFO)).pack()

        assertFalse(
            critical.contentEquals(info),
            "a CRITICAL bundle and an INFO bundle must not be the same bytes",
        )
    }

    /**
     * Absence must stay absence across the whole path, not just the codec. An
     * invented default here would make every undeclared rule report INFO.
     */
    @Test
    fun `UAT-H1-01 an undeclared severity stays undeclared through the bundle path`() {
        val verified = verifyPacked(bundleWithSeverity(null))

        val report = IrRuntimeAdapter.evaluate(verified, violatingTree()).report

        assertEquals(
            null,
            report.severities[ruleKey],
            "an absent severity must not be invented anywhere along the path; " +
                "only rules that DECLARED one appear in PolicyReport.severities",
        )
    }

    // --- UAT-H1-02 ---

    @Test
    fun `UAT-H1-02 two bundles differing only in severity yield SEVERITY_CHANGED`() {
        val before = reportOf(bundleWithSeverity(RuleSeverity.WARNING))
        val after = reportOf(bundleWithSeverity(RuleSeverity.ERROR))

        val entries = PolicyDiff.of(before, after).entries

        assertEquals(
            1,
            entries.size,
            "exactly one change is expected when only severity differs; got $entries",
        )
        assertEquals(
            DiffCategory.SEVERITY_CHANGED,
            entries.single().category,
        )
        assertEquals("replicas", entries.single().ruleId)
    }

    /**
     * Guards the negative: the two bundles must be identical in every other
     * respect. Without this, the SEVERITY_CHANGED above could be produced by a
     * difference in the rule expression and the assertion would be vacuous.
     *
     * This compares the decoded documents, not the digests. An earlier version
     * tried to strip `"severity":"WARNING"` out of `semanticDigest`, which
     * cannot work: that field is a SHA-256 of the canonical encoding, so there
     * is no severity substring in it to remove. Comparing the decoded IR is the
     * check that actually expresses "nothing else changed". The document is
     * reached through `VerifiedBundle.bundle.document`.
     */
    @Test
    fun `UAT-H1-02 the two bundles differ only in severity`() {
        val warning = verifyPacked(bundleWithSeverity(RuleSeverity.WARNING))
        val error = verifyPacked(bundleWithSeverity(RuleSeverity.ERROR))

        val warningRule: Rule = warning.bundle.document.policySet.policies.single().rules.single()
        val errorRule: Rule = error.bundle.document.policySet.policies.single().rules.single()

        assertEquals(
            warningRule.copy(severity = null),
            errorRule.copy(severity = null),
            "the decoded rules must be identical once severity is factored out",
        )
    }

    @Test
    fun `UAT-H1-02 same severity on both sides yields no SEVERITY_CHANGED`() {
        val before = reportOf(bundleWithSeverity(RuleSeverity.WARNING))
        val after = reportOf(bundleWithSeverity(RuleSeverity.WARNING))

        val categories = PolicyDiff.of(before, after).entries.map { it.category }

        assertFalse(
            DiffCategory.SEVERITY_CHANGED in categories,
            "identical severity must not report a severity change; got $categories",
        )
    }

    // --- helpers ---

    private val ruleKey = RuleKey.of("set", "policy", "replicas")

    private fun verifyPacked(document: PolicyIrDocument) = BundleVerifier.verifyPacked(
        PolicyBundle(document).pack(),
    )

    private fun reportOf(document: PolicyIrDocument) = IrRuntimeAdapter.evaluate(
        BundleVerifier.verifyPacked(PolicyBundle(document).pack()),
        violatingTree(),
    ).report

    private fun criticalBundle() = bundleWithSeverity(RuleSeverity.CRITICAL)

    private fun bundleWithSeverity(severity: RuleSeverity?) = PolicyIrDocument(
        policySet = PolicySet(
            "set",
            listOf(
                Policy(
                    "policy",
                    listOf(
                        Rule(
                            id = "replicas",
                            message = "spec.replicas must be >= 3",
                            expression = Expression.Comparison(
                                left = Expression.FieldRef(
                                    DocumentPath.ROOT.child("spec").child("replicas"),
                                    ValueNode.Type.NUMBER,
                                ),
                                op = Expression.Operator.GTE,
                                right = Expression.Literal(ValueNode.NumberValue(3)),
                            ),
                            severity = severity,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun violatingTree(): ValueNode = ValueNode.MappingValue(
        mapOf("spec" to ValueNode.MappingValue(mapOf("replicas" to ValueNode.NumberValue(1)))),
    )
}
