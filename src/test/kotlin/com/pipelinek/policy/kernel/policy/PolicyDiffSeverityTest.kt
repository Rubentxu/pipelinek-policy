package com.pipelinek.policy.kernel.policy

import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B4.5 / ADR-0015 — `SEVERITY_CHANGED` stops being a blind `else`.
 *
 * Normative severity set from EVALUATION_SEMANTICS.md §8 and
 * KOTLIN_POLICY_DSL.md §9: INFO | WARNING | ERROR | CRITICAL. Nothing in this
 * file derives severity from a ViolationCode and nothing parses it from rule
 * text: the value only exists when an author declares it explicitly.
 */
class PolicyDiffSeverityTest {

    private fun rule(expected: Long, severity: RuleSeverity? = null) = Rule(
        id = "floor",
        message = "spec.replicas must be >= $expected",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
            op = Operator.GTE,
            right = Literal(ValueNode.NumberValue(expected)),
        ),
        severity = severity,
    )

    private val tree: ValueNode = ValueNode.MappingValue(
        mapOf("spec" to ValueNode.MappingValue(mapOf("replicas" to ValueNode.NumberValue(1)))),
    )

    /** Same rule shape both sides, so ONLY the declared severity can differ. */
    private fun report(severity: RuleSeverity?): PolicyReport = Evaluator.evaluate(
        PolicySet("p1", listOf(Policy("p1", listOf(rule(3, severity))))),
        tree,
    )

    // --- T4-a: both sides declare and differ => SEVERITY_CHANGED ---

    @Test
    fun `04a severity differing on both sides yields SEVERITY_CHANGED`() {
        val a = report(RuleSeverity.WARNING)
        val b = report(RuleSeverity.ERROR)

        val entry = PolicyDiff.of(a, b).entries.single()

        assertEquals(DiffCategory.SEVERITY_CHANGED, entry.category)
        assertEquals("floor", entry.ruleId)
    }

    // --- T4-b: severity only on ONE side must NOT emit ---

    @Test
    fun `04b severity declared on only one side yields no SEVERITY_CHANGED entry`() {
        val a = report(null)
        val b = report(RuleSeverity.CRITICAL)

        val diff = PolicyDiff.of(a, b)

        // Nothing else changed: no new/resolved violation, no error, no
        // applicability change. A blind `else` here would emit SEVERITY_CHANGED
        // and invent a severity change that only one author declared.
        val categories = diff.entries.map { it.category }
        assertTrue(
            DiffCategory.SEVERITY_CHANGED !in categories,
            "severity declared on one side only must not be reported as changed; got $categories",
        )
    }

    // --- T4-c: both sides declare the SAME severity must NOT emit ---

    @Test
    fun `04c equal declared severity on both sides yields no SEVERITY_CHANGED entry`() {
        val diff = PolicyDiff.of(report(RuleSeverity.ERROR), report(RuleSeverity.ERROR))

        val categories = diff.entries.map { it.category }
        assertTrue(
            DiffCategory.SEVERITY_CHANGED !in categories,
            "identical severity must not be reported as changed; got $categories",
        )
    }

    // --- T4-d: severity must not perturb the verdict categories ---

    @Test
    fun `04d severity does not change how a violation transition is categorised`() {
        val set: (RuleSeverity?) -> PolicyReport = { s -> report(s) }

        // Violated -> Passed with differing severity is still RESOLVED_VIOLATION,
        // never SEVERITY_CHANGED: the verdict transition dominates.
        val a = Evaluator.evaluate(
            PolicySet("p1", listOf(Policy("p1", listOf(rule(3, RuleSeverity.CRITICAL))))),
            tree,
        )
        val b = Evaluator.evaluate(
            PolicySet("p1", listOf(Policy("p1", listOf(rule(0, RuleSeverity.INFO))))),
            tree,
        )

        val entry = PolicyDiff.of(a, b).entries.single()

        assertEquals(DiffCategory.RESOLVED_VIOLATION, entry.category)
        assertTrue(set(RuleSeverity.INFO).results.isNotEmpty(), "sanity: both reports carry results")
    }
}
