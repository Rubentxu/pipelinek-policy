package com.pipelinek.policy.kernel.governance

import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.policy.AuthorityRegistry
import com.pipelinek.policy.kernel.policy.ComposeResult
import com.pipelinek.policy.kernel.policy.LayerComposer
import com.pipelinek.policy.kernel.policy.LayerCompositionRefusal
import com.pipelinek.policy.kernel.policy.LayeredPolicy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.policy.ViolationFingerprint
import com.pipelinek.policy.kernel.policy.ViolationWaiverOutcome
import com.pipelinek.policy.kernel.policy.Waiver
import com.pipelinek.policy.kernel.policy.WaiverApplication
import com.pipelinek.policy.kernel.policy.WaiverContext
import com.pipelinek.policy.kernel.policy.WaiverMatcher
import com.pipelinek.policy.kernel.value.ValueNode
import java.time.Instant

/**
 * B4-T10 / H1.2 · the ONE governance path both adapters run.
 *
 * ## Why this type exists
 *
 * `LayerComposer`, `WaiverMatcher` and `AuthorityRegistry` were all built, all
 * tested, and all **never called from production**. Measured before this class:
 * zero code call sites for any of the three, against 16, 23 and 10 references in
 * tests. The enforcement precedence WAS unified (B0.5), so both adapters agreed
 * on REFUSED > ERRORED > VIOLATED > PASSED, and everything else about
 * governance stayed disconnected: neither adapter composed layers, applied a
 * waiver, or could receive a host grant, because there was no seam for one.
 *
 * ADR-0013 assigned exactly this to B4: the B3 CLI deliberately omits
 * `enforcement`, `rollout` and `waiverStatus` because it has no governance
 * execution context. That context has to exist somewhere, and it exists here.
 *
 * ## The seam, and why the registry travels as data
 *
 * [GovernanceContext] carries the host's [AuthorityRegistry] as an ordinary
 * value. The alternative was a named host capability reached through the
 * PipelineK `StepHandler` context, and it was rejected for a concrete reason:
 * that capability type does not exist in the SDK, so choosing it makes H1 depend
 * on SDK provenance work (H4.2) that is itself blocked behind this. A capability
 * is also not serializable, so a handler staying pure (law 5) could not carry
 * it. Passing the registry as data keeps the core pure, keeps both adapters
 * symmetric, and leaves the capability route open as explicit debt for H4.
 *
 * ## What the adapters must NOT do
 *
 * They must not re-derive precedence, count findings into a verdict of their
 * own, or evaluate a policy set other than the one this returns. If they did,
 * "parity by construction" would be a claim about two independent
 * implementations that happen to agree today.
 */
data class GovernanceContext(
    /** Host-supplied grants. NEVER read from a bundle: a claim is not a grant. */
    val authorities: AuthorityRegistry = AuthorityRegistry.EMPTY,
    /** Injected clock (law 5): the host owns time, the kernel never reads it. */
    val now: Instant,
    /** What the resource subject actually is, resolved by the caller. */
    val subjectResolver: (String) -> String? = { null },
    /** Project/dataset a scoped waiver must match. Null means "unknown". */
    val waiverContext: WaiverContext? = null,
)

/**
 * Governance could not produce a verdict at all.
 *
 * Composition refused, so NOTHING was evaluated: there is no report, no
 * violation count, and no waiver outcome to report. Adapters must render this as
 * an operational failure and never as a pass. The refusal is a typed ADT rather
 * than a string (law 9), and it is the only thing an adapter needs to explain
 * the failure to a user.
 */
data class GovernanceRefused(
    val verdict: GovernanceVerdict = GovernanceVerdict.REFUSED,
    val reason: LayerCompositionRefusal,
    val enforcement: EnforcementMode,
) {
    /** B0.5: a refusal denies in BOTH modes; it is never shadowed. */
    val denies: Boolean get() = EnforcementInterpreter.denies(verdict, enforcement)

    /**
     * The wire vocabulary is the enum NAME, so an adapter renders
     * [reason]::class.simpleName rather than a free-form sentence it invents.
     */
    val code: String get() = reason::class.simpleName ?: "LayerCompositionRefused"
}

/**
 * Everything a caller needs to render a decision, and nothing it does not.
 *
 * [verdict] is already resolved through [EnforcementInterpreter], as are [denies]
 * and [wouldDenyEvidence]. Neither adapter may repeat the question.
 */
data class GovernanceOutcome(
    val verdict: GovernanceVerdict,
    val report: PolicyReport,
    val waivers: WaiverApplication,
    /** The composed policy set actually evaluated, after layer composition. */
    val composed: PolicySet,
    val enforcement: EnforcementMode,
    val denies: Boolean,
    val wouldDenyEvidence: Boolean,
) {
    /**
     * Rules whose violation a waiver silenced.
     *
     * Read straight off the matcher application, so a reader cannot report a
     * waiver as applied while the policy engine says otherwise.
     */
    val waivedRuleKeys: Set<RuleKey> = waivers.waivedRuleKeys

    /**
     * Violations still ACTIVE after waivers. A waived violation is evidence and
     * is still reported, but it does not deny, so this is the number a gate needs.
     */
    val activeViolations: Int = waivers.activeFingerprints.size
}

/**
 * The single governance entry point.
 *
 * Order is fixed and every step is mandatory:
 *  1. compose layers (refuses rather than weakening an upper layer silently);
 *  2. evaluate the COMPOSED set, not the raw one;
 *  3. apply waivers to the resulting report;
 *  4. count the POST-waiver state into a tally;
 *  5. resolve precedence and enforcement once, in the core.
 *
 * Step 4 is where a silent divergence used to live. Both adapters counted
 * independently: the CLI counted `Finding` states while the plugin counted
 * `RuleEvaluation` values, over DIFFERENT policy sets. Counting happens here,
 * once, over the same composed set.
 *
 * [evaluate] is injected rather than called directly so this object stays a pure
 * composition of governance decisions: the evaluator remains the single owner
 * of rule semantics, and the governance layer owns only composition, waivers and
 * the verdict.
 */
object PolicyGovernance {

    /** Result of one governed evaluation: either an outcome or a refusal. */
    sealed interface Result {
        data class Decided(val outcome: GovernanceOutcome) : Result
        data class Refused(val refusal: GovernanceRefused) : Result
    }

    fun decide(
        layers: List<LayeredPolicy>,
        tree: ValueNode,
        waivers: List<Waiver>,
        context: GovernanceContext,
        enforcement: EnforcementMode = EnforcementMode.ENFORCED,
        evaluate: (PolicySet, ValueNode) -> PolicyReport,
    ): Result {
        val composed = when (val composition = LayerComposer.compose(layers, context.authorities)) {
            is ComposeResult.Refused -> return Result.Refused(
                GovernanceRefused(
                    reason = composition.refusal,
                    enforcement = enforcement,
                ),
            )
            is ComposeResult.Composed -> composition.policySet
        }

        val report = evaluate(composed, tree)
        val application = WaiverMatcher.apply(
            report = report,
            waivers = waivers,
            now = context.now,
            subjectResolver = context.subjectResolver,
            context = context.waiverContext,
        )
        val verdict = verdictOf(report, application)
        return Result.Decided(
            GovernanceOutcome(
                verdict = verdict,
                report = report,
                waivers = application,
                composed = composed,
                enforcement = enforcement,
                denies = EnforcementInterpreter.denies(verdict, enforcement),
                wouldDenyEvidence = EnforcementInterpreter.isWouldDenyEvidence(verdict, enforcement),
            ),
        )
    }

    /**
     * Count the POST-waiver state into the core tally, and only then ask for the
     * verdict.
     *
     * Counting is per RULE, not per violation: the tally counts rule outcomes,
     * so a fully waived rule contributes zero while a rule with two violations of
     * which only one was waived still contributes one. Counting violations would
     * let a waiver on the first location hide the second.
     *
     * The read is [WaiverApplication.activeFingerprints] rather than a local
     * reconstruction: the matcher already classified every violation, so this
     * cannot drift from what the engine decided.
     */
    private fun verdictOf(report: PolicyReport, waivers: WaiverApplication): GovernanceVerdict {
        val active = waivers.activeFingerprints
        val violatingRules = report.results.entries.count { (key, evaluation) ->
            evaluation is RuleEvaluation.Violated &&
                evaluation.violations.any { violation ->
                    ViolationFingerprint.of(
                        policySetId = key.policySetId,
                        policyId = key.policyId,
                        ruleId = key.ruleId,
                        location = violation.location.toString(),
                        resourceFingerprint = report.resourceFingerprint,
                    ) in active
                }
        }
        return EnforcementInterpreter.verdict(
            GovernanceTally(
                errors = report.results.values.count { it is RuleEvaluation.Error },
                violations = violatingRules,
            ),
        )
    }
}
