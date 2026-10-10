package com.pipelinek.policy.kernel.policy

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.ir.PolicyIrDocument
import com.pipelinek.policy.kernel.governance.EnforcementInterpreter
import com.pipelinek.policy.kernel.governance.EnforcementMode
import com.pipelinek.policy.kernel.governance.GovernanceTally
import com.pipelinek.policy.kernel.governance.GovernanceVerdict
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * UAT-H1-03 / UAT-H1-04 / UAT-H1-05 — the business cases the per-mechanism
 * suites never asked.
 *
 * `LayersTest`, `WaiversTest` and `EnforcementInterpreterTest` are strong on
 * mechanism and weak on the question a release actually has to answer: does the
 * whole chain hold together on a realistic input?
 *
 * Three concrete gaps, all verified by reading the sources rather than assumed:
 *
 *  - UAT-H1-03: EVERY supersession scenario in `LayersTest` replaces a floor of
 *    3 with a floor of 5. That is the STRENGTHENING direction. ADR-0014 exists
 *    because a lower layer may WEAKEN a mandatory upper-layer rule, and that
 *    direction is never exercised end to end — not with a grant, not without.
 *  - UAT-H1-04: every `WaiversTest` case passes a SINGLE waiver to `apply`.
 *    `WaiverMatcher` resolves with `waivers.firstOrNull { ... }`, so routing is
 *    decided by list order once more than one waiver is in play, and that is
 *    never exercised.
 *  - UAT-H1-05: `EnforcementInterpreterTest` only ever builds tallies BY HAND.
 *    No test derives a tally from a real report and asks whether an evaluation
 *    error plus a violation still denies under SHADOW, which is exactly where a
 *    dropped error would silently degrade to PASS.
 */
class H1GovernanceBusinessCaseTest {

    private val now: Instant = Instant.parse("2026-06-01T12:00:00Z")

    // --- UAT-H1-03: the weakening direction, which no existing test covers ---

    /**
     * A rule requiring `spec.replicas >= floor`.
     *
     * The direction matters: a HIGHER floor strengthens the policy, a LOWER one
     * weakens it. Every supersession scenario in `LayersTest` swaps 3 for 5, so
     * the composer has only ever been observed permitting a safe replacement.
     */
    private fun floorRule(id: String, floor: Long) = Rule(
        id = id,
        message = "spec.replicas must be >= $floor",
        expression = Comparison(
            left = FieldRef(
                DocumentPath.ROOT.child("spec").child("replicas"),
                ValueNode.Type.NUMBER,
            ),
            op = Operator.GTE,
            right = Literal(ValueNode.NumberValue(floor)),
        ),
    )

    private fun floorOf(set: PolicySet, ruleId: String): Long {
        val rule = set.policies.single().rules.single { it.id == ruleId }
        val right = (rule.expression as Comparison).right as Literal
        return (right.value as ValueNode.NumberValue).number.toLong()
    }

    private fun grant(digest: String = "sha256:floor-grant") = SupersessionAuthority(
        issuer = "acme-platform",
        grantedLayers = setOf(PolicyLayer.PROJECT),
        grantDigest = digest,
    )

    private fun weakening(floor: Long, authority: SupersessionAuthority) = floorRule("min-replicas", floor).copy(
        supersession = Supersession(
            supersedes = RuleRef("shared", "min-replicas"),
            reason = "project needs a smaller footprint",
            authority = authority,
            scope = "project:demo",
            validity = "2026-01-01/2027-01-01",
        ),
    )

    private fun set(vararg rules: Rule) = PolicySet("shared", listOf(Policy("shared", rules.toList())))

    /**
     * UAT-H1-03: a PROJECT layer lowering the ORGANIZATION floor from 3 to 1
     * without a grant is REFUSED. This is architectural law 12 in the one
     * direction that actually costs something, and it has no coverage.
     */
    @Test
    fun `UAT-H1-03 a lower layer weakening an upper floor without a grant is refused`() {
        val org = LayeredPolicy(PolicyLayer.ORGANIZATION, set(floorRule("min-replicas", 3)))
        val project = LayeredPolicy(
            PolicyLayer.PROJECT,
            set(weakening(1, grant())),
        )

        val result = LayerComposer.compose(listOf(org, project))

        val refused = assertIs<ComposeResult.Refused>(result)
        assertIs<LayerCompositionRefusal.AuthorityLacksCapability>(refused.refusal)
    }

    /**
     * The matching case, so the refusal above cannot be satisfied by refusing
     * every weakening supersession. A grant naming PROJECT covers exactly this,
     * and the weakened floor is what actually lands.
     */
    @Test
    fun `UAT-H1-03 the same weakening composes when the grant covers the layer`() {
        val authority = grant()
        val org = LayeredPolicy(PolicyLayer.ORGANIZATION, set(floorRule("min-replicas", 3)))
        val project = LayeredPolicy(PolicyLayer.PROJECT, set(weakening(1, authority)))

        val result = LayerComposer.compose(listOf(org, project), AuthorityRegistry.of(authority))

        val composed = assertIs<ComposeResult.Composed>(result)
        assertEquals(
            1L,
            floorOf(composed.policySet, "min-replicas"),
            "a granted weakening must land; refusing it too would be a different defect",
        )
    }

    /**
     * The same weakening arriving from PIPELINE_LOCAL under a PROJECT-scoped
     * grant is REFUSED. A local rule must not rewrite an organization floor by
     * quoting a real issuer and a real digest it was never granted for.
     */
    @Test
    fun `UAT-H1-03 a project grant cannot let a local layer weaken an organization floor`() {
        val authority = grant()
        val org = LayeredPolicy(PolicyLayer.ORGANIZATION, set(floorRule("min-replicas", 3)))
        val local = LayeredPolicy(PolicyLayer.PIPELINE_LOCAL, set(weakening(1, authority)))

        val result = LayerComposer.compose(listOf(org, local), AuthorityRegistry.of(authority))

        assertIs<ComposeResult.Refused>(result)
    }

    // --- UAT-H1-04: routing with more than one waiver in play ---

    private val teamRule = Rule(
        id = "team-own",
        message = "metadata.team must be platform",
        expression = Comparison(
            left = FieldRef(
                DocumentPath.ROOT.child("metadata").child("team"),
                ValueNode.Type.TEXT,
            ),
            op = Operator.TEXT_EQUALS,
            right = Literal(ValueNode.TextValue("platform")),
        ),
    )

    private val envRule = Rule(
        id = "env-own",
        message = "metadata.env must be production",
        expression = Comparison(
            left = FieldRef(
                DocumentPath.ROOT.child("metadata").child("env"),
                ValueNode.Type.TEXT,
            ),
            op = Operator.TEXT_EQUALS,
            right = Literal(ValueNode.TextValue("production")),
        ),
    )

    /**
     * One resource, two rules, two DIFFERENT subject paths. Both violate.
     */
    private fun twoSubjectReport() = Evaluator.evaluate(
        PolicySet("p1", listOf(Policy("p1", listOf(teamRule, envRule)))),
        ValueNode.MappingValue(
            mapOf(
                "metadata" to ValueNode.MappingValue(
                    mapOf(
                        "team" to ValueNode.TextValue("payments"),
                        "env" to ValueNode.TextValue("staging"),
                    ),
                ),
            ),
        ),
    )

    private val twoSubjectResolver: (String) -> String? = { path ->
        when (path) {
            "metadata.team" -> "payments"
            "metadata.env" -> "prod-eu"
            else -> null
        }
    }

    private fun waiverFor(ruleId: String, subjectPath: String, subject: String, id: String = "w-$ruleId") = Waiver(
        id = id,
        policyId = "p1",
        ruleId = ruleId,
        subject = ResourceSubjectSelector(subjectPath, subject),
        reason = "legacy service",
        issuer = "security-team",
        notBefore = now.minusSeconds(3600),
        notAfter = now.plusSeconds(3600),
    )

    /**
     * UAT-H1-04: with two waivers in one application, each violation must be
     * routed to ITS OWN waiver.
     *
     * `WaiverMatcher` picks the candidate with `waivers.firstOrNull { ... }`, so
     * a matcher that ignored the subject path would hand the FIRST waiver to
     * BOTH violations and the team waiver would silence the env violation. Every
     * existing case passes one waiver, which cannot distinguish "matched the
     * right one" from "matched the only one".
     */
    @Test
    fun `UAT-H1-04 two waivers in one application each route to their own violation`() {
        val report = twoSubjectReport()
        assertEquals(
            2,
            report.results.values.count { it.violations.isNotEmpty() },
            "the fixture must produce exactly two violations",
        )

        val app = WaiverMatcher.apply(
            report,
            listOf(
                waiverFor("team-own", "metadata.team", "payments"),
                waiverFor("env-own", "metadata.env", "prod-eu"),
            ),
            now,
            twoSubjectResolver,
        )

        val waived = app.outcomes.filterIsInstance<ViolationWaiverOutcome.Waived>()
        assertEquals(2, app.waivedCount)
        assertEquals(0, app.activeCount)
        assertEquals(
            setOf("w-team-own", "w-env-own"),
            waived.map { it.waiverId }.toSet(),
            "each violation must be waived by the waiver naming ITS subject path, not by the first one in the list",
        )
    }

    /**
     * Order is not the mechanism. Swapping the list must not change the
     * routing, because the subject path is what selects the candidate. If
     * `subjectMatches` were dropped, the first element would win both ways and
     * this assertion would catch it in the second ordering.
     */
    @Test
    fun `UAT-H1-04 waiver routing does not depend on list order`() {
        val report = twoSubjectReport()
        val team = waiverFor("team-own", "metadata.team", "payments")
        val env = waiverFor("env-own", "metadata.env", "prod-eu")

        val forward = WaiverMatcher.apply(report, listOf(team, env), now, twoSubjectResolver)
        val reversed = WaiverMatcher.apply(report, listOf(env, team), now, twoSubjectResolver)

        assertEquals(
            forward.outcomes.map { it.fingerprint.value to (it as? ViolationWaiverOutcome.Waived)?.waiverId },
            reversed.outcomes.map { it.fingerprint.value to (it as? ViolationWaiverOutcome.Waived)?.waiverId },
            "the waiver list order must not decide which waiver applies",
        )
    }

    /**
     * The test above cannot tell "selected by subject" from "selected by rule
     * id", because there the two waivers differ in BOTH. This one removes that
     * confound: both waivers name the SAME rule and the SAME subject path, and
     * only the subject value differs. The non-matching one is FIRST, so if the
     * matcher dropped `subjectMatches` and simply took the first candidate, it
     * would answer with the wrong waiver id.
     */
    @Test
    fun `UAT-H1-04 two waivers for one rule are separated by the subject value`() {
        val report = twoSubjectReport()
        val wrongSubjectFirst = waiverFor("team-own", "metadata.team", "search", id = "w-search")
        val rightSubject = waiverFor("team-own", "metadata.team", "payments", id = "w-payments")

        val app = WaiverMatcher.apply(
            report,
            listOf(wrongSubjectFirst, rightSubject),
            now,
            twoSubjectResolver,
        )

        assertEquals(1, app.waivedCount)
        assertEquals(1, app.activeCount, "the env violation has no waiver naming env-own")
        val waived = assertIs<ViolationWaiverOutcome.Waived>(
            app.outcomes.single { it is ViolationWaiverOutcome.Waived },
        )
        assertEquals(
            "w-payments",
            waived.waiverId,
            "the subject value selects the waiver; position in the list must not",
        )
    }

    /**
     * And the mirror: a waiver whose subject does NOT hold silences nothing at
     * all, rather than falling through to a neighbouring waiver.
     */
    @Test
    fun `UAT-H1-04 a waiver whose subject does not hold silences nothing`() {
        val report = twoSubjectReport()

        val app = WaiverMatcher.apply(
            report,
            listOf(waiverFor("team-own", "metadata.team", "search", id = "w-search")),
            now,
            twoSubjectResolver,
        )

        assertEquals(0, app.waivedCount, "the resource team is payments, not search")
        assertEquals(2, app.activeCount)
        assertTrue(
            app.outcomes.none { it is ViolationWaiverOutcome.Waived },
            "no outcome may carry a waiver id when no subject matched",
        )
    }

    // --- UAT-H1-05: Error + Violated under SHADOW, tally derived from a report ---

    /**
     * The tally an adapter would actually derive from a report.
     *
     * This is the step `EnforcementInterpreterTest` never takes: it builds every
     * tally by hand, so the mapping from a real evaluation to the three counts
     * is unasserted. Both adapters derive it themselves
     * (`CheckCmd.kt:83`, `PolicyCheckStepDefinition.kt:172`) and the plugin
     * counts `RuleEvaluation` values directly while the CLI counts `Finding`
     * states, so this is the shape they are meant to agree on.
     */
    private fun tallyOf(report: PolicyReport) = GovernanceTally(
        errors = report.results.values.count { it is RuleEvaluation.Error },
        violations = report.results.values.count { it is RuleEvaluation.Violated },
    )

    /**
     * Two rules over one resource: one VIOLATES (replicas below the floor) and
     * one ERRORS (a text comparison against a number, so the types cannot be
     * reconciled and law 8 keeps them distinct).
     */
    private fun errorAndViolationReport(): PolicyReport {
        val typeClashingRule = Rule(
            id = "env-own",
            message = "metadata.env must be production",
            expression = Comparison(
                left = FieldRef(
                    DocumentPath.ROOT.child("metadata").child("env"),
                    ValueNode.Type.TEXT,
                ),
                op = Operator.TEXT_EQUALS,
                right = Literal(ValueNode.TextValue("production")),
            ),
        )
        val report = Evaluator.evaluate(
            PolicySet("p1", listOf(Policy("p1", listOf(floorRule("min-replicas", 3), typeClashingRule)))),
            ValueNode.MappingValue(
                mapOf(
                    "spec" to ValueNode.MappingValue(mapOf("replicas" to ValueNode.NumberValue(1))),
                    "metadata" to ValueNode.MappingValue(mapOf("env" to ValueNode.NumberValue(7))),
                ),
            ),
        )
        check(report.results.values.any { it is RuleEvaluation.Error }) { "fixture must produce an Error" }
        check(report.results.values.any { it is RuleEvaluation.Violated }) { "fixture must produce a Violation" }
        return report
    }

    /**
     * UAT-H1-05: a report carrying BOTH an evaluation error and a violation
     * still DENIES under SHADOW.
     *
     * This is the degradation the criterion exists to forbid. SHADOW suppresses
     * a genuine violation, so a tally that dropped the error would answer
     * VIOLATED, `denies` would be false under SHADOW, and a policy that could
     * not be evaluated would ship as a policy that passed. The count of errors
     * is what prevents that, and this is the first test to derive that count
     * from a real report instead of assuming it.
     */
    @Test
    fun `UAT-H1-05 an error plus a violation denies under SHADOW`() {
        val report = errorAndViolationReport()
        val tally = tallyOf(report)

        val verdict = EnforcementInterpreter.verdict(tally)

        assertEquals(GovernanceVerdict.ERRORED, verdict, "the error must outrank the violation alongside it")
        assertTrue(
            EnforcementInterpreter.denies(verdict, EnforcementMode.SHADOW),
            "a policy that could not be evaluated must deny even in shadow mode",
        )
        assertFalse(
            EnforcementInterpreter.isWouldDenyEvidence(verdict, EnforcementMode.SHADOW),
            "an operational failure is not a shadow-suppressed policy result",
        )
    }

    /**
     * The shadow case that genuinely IS shadowable, so the test above cannot be
     * satisfied by denying everything under SHADOW. A violation with no error is
     * suppressed and recorded as would-deny evidence.
     */
    @Test
    fun `UAT-H1-05 a violation without an error is shadowed into evidence`() {
        val report = Evaluator.evaluate(
            PolicySet("p1", listOf(Policy("p1", listOf(floorRule("min-replicas", 3))))),
            ValueNode.MappingValue(
                mapOf("spec" to ValueNode.MappingValue(mapOf("replicas" to ValueNode.NumberValue(1)))),
            ),
        )

        val verdict = EnforcementInterpreter.verdict(tallyOf(report))

        assertEquals(GovernanceVerdict.VIOLATED, verdict)
        assertFalse(
            EnforcementInterpreter.denies(verdict, EnforcementMode.SHADOW),
            "a genuine violation is exactly what shadow mode suppresses",
        )
        assertTrue(EnforcementInterpreter.isWouldDenyEvidence(verdict, EnforcementMode.SHADOW))
    }

    // --- UAT-H1-06: a difference exclusive to the third document ---

    private fun bundleWith(extraRules: List<Rule>): PolicyIrDocument = PolicyIrDocument(
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
                        ),
                    ) + extraRules,
                ),
            ),
        ),
    )

    private fun reportFor(extraRules: List<Rule>): PolicyReport = IrRuntimeAdapter.evaluate(
        BundleVerifier.verifyPacked(PolicyBundle(bundleWith(extraRules)).pack()),
        ValueNode.MappingValue(
            mapOf(
                "spec" to ValueNode.MappingValue(mapOf("replicas" to ValueNode.NumberValue(1))),
                "metadata" to ValueNode.MappingValue(mapOf("env" to ValueNode.TextValue("staging"))),
            ),
        ),
    ).report

    private val extraEnvRule = Rule(
        id = "env-own",
        message = "metadata.env must be production",
        expression = Expression.Comparison(
            left = Expression.FieldRef(
                DocumentPath.ROOT.child("metadata").child("env"),
                ValueNode.Type.TEXT,
            ),
            op = Expression.Operator.TEXT_EQUALS,
            right = Expression.Literal(ValueNode.TextValue("production")),
        ),
    )

    /**
     * UAT-H1-06: documents A and B are the SAME policy, and C adds one more
     * rule than either. The diff `A → C` must name that rule and only that rule.
     *
     * The gap this closes is not "PolicyDiff works": `PolicyDiffTest` covers A/B
     * thoroughly. It is that every existing case has exactly TWO documents. A
     * diff implementation that leaked state across calls, or that compared the
     * union of all documents ever seen, would answer every A/B case correctly and
     * still misreport a third document. Two-sided diffs cannot detect that class
     * of bug; a third document can.
     *
     * The middle document is what makes this a real check: comparing A straight
     * to C would also produce exactly one NEW_VIOLATION, and would pass an
     * implementation that simply ignored B. Going A → B → C in sequence and
     * asserting B is empty is what pins that B contributed nothing.
     */
    @Test
    fun `UAT-H1-06 three documents detect a difference exclusive to the third`() {
        val a = reportFor(emptyList())
        val b = reportFor(emptyList())
        val c = reportFor(listOf(extraEnvRule))

        val abEntries = PolicyDiff.of(a, b).entries
        val bcEntries = PolicyDiff.of(b, c).entries

        assertTrue(
            abEntries.isEmpty(),
            "A and B carry the same rules, so the A→B diff must be empty; got $abEntries",
        )
        val entry = assertIs<DiffEntry>(bcEntries.singleOrNull(), "B→C must yield exactly one entry, got $bcEntries")
        assertEquals(DiffCategory.NEW_VIOLATION, entry.category)
        assertEquals("env-own", entry.ruleId)
        assertEquals(
            RuleKey.of("set", "policy", "env-own"),
            entry.ruleKey,
            "the entry must be filed under the rule that only C declares",
        )
    }

    /**
     * A and B really are the same document, and C really is different. Without
     * this the test above could be satisfied by A, B and C all being identical.
     * The rules are compared through the packed bundle, which is also the path
     * that makes the diff claim meaningful.
     */
    @Test
    fun `UAT-H1-06 the third document is the only one that differs`() {
        val ruleOf = { document: PolicyIrDocument ->
            BundleVerifier.verifyPacked(PolicyBundle(document).pack())
                .bundle.document.policySet.policies.single().rules
        }

        val aRules = ruleOf(bundleWith(emptyList()))
        val bRules = ruleOf(bundleWith(emptyList()))
        val cRules = ruleOf(bundleWith(listOf(extraEnvRule)))

        assertEquals(aRules, bRules, "A and B must decode to the same rules")
        assertEquals(aRules, cRules.filter { it.id == "replicas" }, "C keeps the shared rule unchanged")
        assertEquals(
            setOf("replicas", "env-own"),
            cRules.map { it.id }.toSet(),
            "C declares exactly one extra rule",
        )
        assertEquals(
            aRules.size + 1,
            cRules.size,
            "exactly one rule differs, not a rewrite of the whole policy",
        )
    }

    /**
     * And the diff is order-independent across the pair, so the middle document
     * cannot be smuggled in as an artifact of call order.
     */
    @Test
    fun `UAT-H1-06 the same difference is reported regardless of comparison order`() {
        val a = reportFor(emptyList())
        val c = reportFor(listOf(extraEnvRule))

        val forward = PolicyDiff.of(a, c).entries
        val reversed = PolicyDiff.of(c, a).entries

        assertEquals(
            DiffCategory.NEW_VIOLATION,
            forward.single().category,
            "adding the rule introduces a violation",
        )
        assertEquals(
            DiffCategory.RESOLVED_VIOLATION,
            reversed.single().category,
            "reversing the direction reports that same rule as resolved",
        )
        assertTrue(
            reversed.single().privilegeExpansion,
            "dropping a mandatory violation is the privilege-expansion direction",
        )
        assertEquals(
            setOf("env-own"),
            forward.map { it.ruleId }.toSet() + reversed.map { it.ruleId }.toSet(),
            "both directions must name the same rule and no other",
        )
    }
}
