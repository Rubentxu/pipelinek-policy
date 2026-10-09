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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M7 REQ-M7-03 — waiver matching post-violation.
 *
 * Scenarios 03a-f from M7_SPECIFICATION. The matcher receives a fixed clock
 * (law 5) and a subject resolver bound to the resource under test.
 */
class WaiversTest {

    private val now: Instant = Instant.parse("2026-06-01T12:00:00Z")

    private val rule = Rule(
        id = "team-own",
        message = "metadata.team must be platform",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("metadata").child("team"), ValueNode.Type.TEXT),
            op = Operator.TEXT_EQUALS,
            right = Literal(ValueNode.TextValue("platform")),
        ),
    )

    private fun reportFor(subject: String): PolicyReport {
        val tree = ValueNode.MappingValue(
            mapOf(
                "metadata" to ValueNode.MappingValue(
                    mapOf("team" to ValueNode.TextValue(subject)),
                ),
            ),
        )
        return Evaluator.evaluate(PolicySet("p1", listOf(Policy("p1", listOf(rule)))), tree)
    }

    private fun waiverFor(
        subject: String,
        notBefore: Instant = now.minusSeconds(3600),
        notAfter: Instant = now.plusSeconds(3600),
        fingerprint: ViolationFingerprint? = null,
        ruleId: String = "team-own",
    ) = Waiver(
        id = "w-$subject",
        policyId = "p1",
        ruleId = ruleId,
        subject = ResourceSubjectSelector("metadata.team", subject),
        reason = "legacy service",
        issuer = "security-team",
        notBefore = notBefore,
        notAfter = notAfter,
        violationFingerprint = fingerprint,
    )

    private val resolver: (String) -> String? = { path ->
        when (path) {
            "metadata.team" -> "payments" // the resource under test belongs to team payments
            else -> null
        }
    }

    // --- 03a: exact match waives ---

    @Test
    fun `03a valid waiver waives the matching violation`() {
        val report = reportFor("payments")
        assertTrue(report.results.values.any { it.violations.isNotEmpty() })

        val app = WaiverMatcher.apply(report, listOf(waiverFor("payments")), now, resolver)

        assertEquals(1, app.waivedCount)
        assertEquals(0, app.activeCount)
        val waived = assertIs<ViolationWaiverOutcome.Waived>(app.outcomes.single())
        assertEquals("w-payments", waived.waiverId)
    }

    // --- 03b: expiry keeps the violation active with diagnostic ---

    @Test
    fun `03b expired waiver keeps violation active with WaiverExpired diagnostic`() {
        val report = reportFor("payments")
        val expired = waiverFor("payments", notBefore = now.minusSeconds(7200), notAfter = now.minusSeconds(3600))

        val app = WaiverMatcher.apply(report, listOf(expired), now, resolver)

        assertEquals(0, app.waivedCount)
        assertEquals(1, app.activeCount)
        val diag = assertIs<ViolationWaiverOutcome.ActiveWithDiagnostic>(app.outcomes.single())
        assertEquals(WaiverDiagnosticCause.WaiverExpired, diag.cause)
        assertEquals("w-payments", diag.waiverId)
    }

    // --- 03c: not-yet-valid keeps the violation active with diagnostic ---

    @Test
    fun `03c not yet valid waiver keeps violation active with WaiverNotYetValid`() {
        val report = reportFor("payments")
        val future = waiverFor("payments", notBefore = now.plusSeconds(3600), notAfter = now.plusSeconds(7200))

        val app = WaiverMatcher.apply(report, listOf(future), now, resolver)

        assertEquals(0, app.waivedCount)
        val diag = assertIs<ViolationWaiverOutcome.ActiveWithDiagnostic>(app.outcomes.single())
        assertEquals(WaiverDiagnosticCause.WaiverNotYetValid, diag.cause)
    }

    // --- 03d: subject isolation — waiver for S1 does not save S2 ---

    @Test
    fun `03d waiver for one subject does not save another`() {
        val report = reportFor("payments") // resource subject = payments

        // A waiver scoped to a DIFFERENT subject (search) must not waive it.
        val app = WaiverMatcher.apply(report, listOf(waiverFor("search")), now, resolver)

        assertEquals(0, app.waivedCount)
        assertEquals(1, app.activeCount)
        assertIs<ViolationWaiverOutcome.Active>(app.outcomes.single())
    }

    // --- 03e: rule mismatch is a no-op, no false diagnostics ---

    @Test
    fun `03e waiver for unknown rule is a no-op without diagnostics`() {
        val report = reportFor("payments")
        val unrelated = waiverFor("payments", ruleId = "some-other-rule")

        val app = WaiverMatcher.apply(report, listOf(unrelated), now, resolver)

        assertEquals(0, app.waivedCount)
        assertEquals(1, app.activeCount)
        // Plain Active, NOT ActiveWithDiagnostic: the waiver is for another rule.
        assertIs<ViolationWaiverOutcome.Active>(app.outcomes.single())
    }

    // --- 03f: fingerprint pinning ---

    @Test
    fun `03f waiver with different fingerprint does not waive and mutation ignoring expiry fails 03b`() {
        val report = reportFor("payments")
        val otherResourceFp = ViolationFingerprint.of("p1", "p1", "team-own", "metadata.team", "different-resource")

        val waiver = waiverFor("payments", fingerprint = otherResourceFp)
        val app = WaiverMatcher.apply(report, listOf(waiver), now, resolver)

        assertEquals(0, app.waivedCount)
        assertIs<ViolationWaiverOutcome.Active>(app.outcomes.single())
        // Mutation contract: if the matcher ignored expiry, 03b would report
        // Waived — 03b pins that. Here we additionally pin the fingerprint
        // equality path: a pinned waiver for another resource never waives.
    }

    // --- B4-T3: waiver scoping ---

    /** A report carrying an evaluation ERROR rather than a violation. */
    private fun errorReportFor(): PolicyReport {
        val tree = ValueNode.MappingValue(
            mapOf("metadata" to ValueNode.MappingValue(mapOf("team" to ValueNode.NumberValue(7)))),
        )
        val report = Evaluator.evaluate(PolicySet("p1", listOf(Policy("p1", listOf(rule)))), tree)
        check(report.results.values.any { it is RuleEvaluation.Error }) { "fixture must produce an Error" }
        return report
    }

    /**
     * B4-T3 constraint 1: an evaluation ERROR is a DIAGNOSTIC, never an
     * eximable violation.
     *
     * `RuleEvaluation.Error.violations` returns `listOf(primary)` — a
     * diagnostic describing WHY evaluation failed. The matcher flat-mapped
     * over `evaluation.violations` for every evaluation, so an Error was
     * handed to the waiver machinery as if it were a violation and could be
     * waived. Waiving an error would hide an operational failure behind an
     * exemption and let a broken policy read as a sanctioned one.
     */
    @Test
    fun `03g an evaluation error is never waived`() {
        val report = errorReportFor()
        val waiver = waiverFor("payments")

        val app = WaiverMatcher.apply(report, listOf(waiver), now, resolver)

        assertEquals(0, app.waivedCount, "an evaluation ERROR must not be eximable")
        // The error still appears, still counted, and carries no waiver id:
        // it is surfaced, not silently dropped and not waived.
        val outcome = app.outcomes.single()
        assertIs<ViolationWaiverOutcome.Active>(outcome)
        assertEquals(1, app.activeCount)
    }

    /**
     * B4-T3 constraint 2, as a regression guard on the existing scenarios:
     * an expired waiver keeps the violation ACTIVE and adds a diagnostic.
     * 03b/03c already assert this; the explicit contract here is that no
     * waiver state may ever convert an expired waiver into a Waived outcome.
     */
    @Test
    fun `03h an expired waiver never yields a Waived outcome`() {
        val report = reportFor("payments")
        val expired = waiverFor(
            "payments",
            notBefore = now.minusSeconds(7200),
            notAfter = now.minusSeconds(3600),
        )

        val app = WaiverMatcher.apply(report, listOf(expired), now, resolver)

        assertTrue(app.outcomes.none { it is ViolationWaiverOutcome.Waived })
        val diag = assertIs<ViolationWaiverOutcome.ActiveWithDiagnostic>(app.outcomes.single())
        assertEquals(WaiverDiagnosticCause.WaiverExpired, diag.cause)
    }

    /**
     * B4-T3 constraint 3: a project-scoped waiver needs project CONTEXT to
     * match. With no context available the honest answer is to refuse the
     * scoped match — a missing context that matched everything would let a
     * narrowly-scoped waiver silence a violation far outside its project.
     */
    @Test
    fun `03i a project-scoped waiver does not match when no project context is supplied`() {
        val report = reportFor("payments")
        val scoped = waiverFor("payments").copy(projectScope = "payments-project")

        val app = WaiverMatcher.apply(report, listOf(scoped), now, resolver, context = null)

        assertEquals(0, app.waivedCount, "a scoped waiver must not waive without its context")
        assertIs<ViolationWaiverOutcome.Active>(app.outcomes.single())
    }

    /** The mirror case: with matching context the same waiver DOES waive. */
    @Test
    fun `03j a project-scoped waiver matches when the project context matches`() {
        val report = reportFor("payments")
        val scoped = waiverFor("payments").copy(projectScope = "payments-project")

        val app = WaiverMatcher.apply(
            report,
            listOf(scoped),
            now,
            resolver,
            context = WaiverContext(project = "payments-project"),
        )

        assertEquals(1, app.waivedCount)
        assertIs<ViolationWaiverOutcome.Waived>(app.outcomes.single())
    }

    /** A project-scoped waiver must not waive inside a DIFFERENT project. */
    @Test
    fun `03k a project-scoped waiver does not leak across projects`() {
        val report = reportFor("payments")
        val scoped = waiverFor("payments").copy(projectScope = "payments-project")

        val app = WaiverMatcher.apply(
            report,
            listOf(scoped),
            now,
            resolver,
            context = WaiverContext(project = "some-other-project"),
        )

        assertEquals(0, app.waivedCount, "a waiver must not leak outside its project")
        assertIs<ViolationWaiverOutcome.Active>(app.outcomes.single())
    }

    /** A dataset-scoped waiver needs dataset context for the same reason. */
    @Test
    fun `03l a dataset-scoped waiver does not match without dataset context`() {
        val report = reportFor("payments")
        val scoped = waiverFor("payments").copy(datasetScope = "payments-rows")

        val app = WaiverMatcher.apply(report, listOf(scoped), now, resolver, context = null)

        assertEquals(0, app.waivedCount)
        assertIs<ViolationWaiverOutcome.Active>(app.outcomes.single())
    }

    /** With matching dataset context the scoped waiver applies. */
    @Test
    fun `03m a dataset-scoped waiver matches when the dataset context matches`() {
        val report = reportFor("payments")
        val scoped = waiverFor("payments").copy(datasetScope = "payments-rows")

        val app = WaiverMatcher.apply(
            report,
            listOf(scoped),
            now,
            resolver,
            context = WaiverContext(dataset = "payments-rows"),
        )

        assertEquals(1, app.waivedCount)
    }

    /** An UNSCOPED waiver must still work with no context at all. */
    @Test
    fun `03n an unscoped waiver is unaffected by absent context`() {
        val report = reportFor("payments")

        val app = WaiverMatcher.apply(report, listOf(waiverFor("payments")), now, resolver, context = null)

        assertEquals(1, app.waivedCount, "an unscoped waiver must not require context it never asked for")
    }
}
