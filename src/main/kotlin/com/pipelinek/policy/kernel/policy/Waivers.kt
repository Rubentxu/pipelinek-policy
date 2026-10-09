package com.pipelinek.policy.kernel.policy

import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.evaluator.RuleKey
import java.time.Instant

/**
 * M7 §"Waivers" — post-violation, pre-enforcement waiver application.
 *
 * A waiver NEVER modifies the rule or the evaluator (spec §4). It is applied
 * to an already-computed [PolicyReport] and produces, per violation, either
 * a waived finding or an active violation (optionally with a diagnostic when
 * a waiver almost matched but is expired / not yet valid — never a silent
 * waive).
 *
 * Pure (law 5): the current instant is INJECTED as [now] by the caller; the
 * host plugin obtains it from the runtime, tests use a fixed clock.
 */

/**
 * Deterministic identity of a concrete violation instance: derived from
 * (policyId, ruleId, violation location, resource fingerprint). Shared with
 * `PolicyDiff` for cross-bundle violation identity.
 */
data class ViolationFingerprint(val value: String) {
    companion object {
        fun of(policyId: String, ruleId: String, location: String, resourceFingerprint: String): ViolationFingerprint {
            val raw = listOf(policyId, ruleId, location, resourceFingerprint)
                .joinToString("") { part -> "${part.length}:$part" }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return ViolationFingerprint(digest)
        }
    }
}

/**
 * Structural resource selector of a waiver (law 9: no stringly matching).
 * [subjectPath] is the document path whose value identifies the subject
 * (e.g. `metadata.name`); [subjectEquals] matches that value TEXTUALLY
 * (structural equality on TextValue). An absent/null selector matches
 * nothing — a waiver must be scoped (spec §5).
 */
data class ResourceSubjectSelector(
    val subjectPath: String,
    val subjectEquals: String,
)

/** A waiver scoped to a policy/rule and a resource subject. */
data class Waiver(
    val id: String,
    val policyId: String,
    val ruleId: String,
    val subject: ResourceSubjectSelector,
    val datasetScope: String? = null,
    val projectScope: String? = null,
    val violationFingerprint: ViolationFingerprint? = null,
    val reason: String,
    val issuer: String,
    val notBefore: Instant,
    val notAfter: Instant,
) {
    init {
        require(id.isNotBlank()) { "waiver.id must not be blank" }
        require(reason.isNotBlank()) { "waiver.reason must not be blank" }
        require(issuer.isNotBlank()) { "waiver.issuer must not be blank" }
        require(notBefore < notAfter) { "waiver validity window is empty" }
    }
}

/**
 * The ambient scope a waiver is evaluated against (B4-T3).
 *
 * `PolicyReport` carries no project or dataset identity, so the matcher cannot
 * derive one. The caller — which knows the pipeline, the project and the
 * dataset — supplies it explicitly. [project] and [dataset] are nullable
 * because a caller may genuinely know only one of them.
 *
 * A null field means "this context does not know", which is NOT the same as
 * "matches anything". A waiver that names a scope the context cannot supply
 * is REFUSED rather than honored, because treating a missing context as a
 * wildcard would let a narrowly-scoped exemption silence violations far
 * outside it. That is the same defect class as an unverified authority
 * claim: a missing check silently matching everything.
 */
data class WaiverContext(
    val project: String? = null,
    val dataset: String? = null,
)

/** Why an otherwise-matching waiver did not waive. */
enum class WaiverDiagnosticCause {
    WaiverExpired,
    WaiverNotYetValid,
}

/** Outcome of applying the waiver set to ONE violation. */
sealed interface ViolationWaiverOutcome {
    val fingerprint: ViolationFingerprint

    data class Waived(
        override val fingerprint: ViolationFingerprint,
        val waiverId: String,
    ) : ViolationWaiverOutcome

    data class Active(
        override val fingerprint: ViolationFingerprint,
    ) : ViolationWaiverOutcome

    data class ActiveWithDiagnostic(
        override val fingerprint: ViolationFingerprint,
        val waiverId: String,
        val cause: WaiverDiagnosticCause,
    ) : ViolationWaiverOutcome
}

/** Full application of a waiver set over a report. */
data class WaiverApplication(
    val outcomes: List<ViolationWaiverOutcome>,
) {
    val waivedCount: Int get() = outcomes.count { it is ViolationWaiverOutcome.Waived }
    val activeCount: Int get() = outcomes.count { it !is ViolationWaiverOutcome.Waived }
}

/**
 * Pure waiver matcher. [subjectResolver] resolves the subject value of a
 * resource (the caller binds it to the decoded resource tree; the kernel
 * stays I/O-free). Returns [ViolationWaiverOutcome.Active] for violations
 * with no matching waiver — and never invents diagnostics for waivers whose
 * (policyId, ruleId) never matched anything (scenario 03e).
 */
object WaiverMatcher {

    fun apply(
        report: PolicyReport,
        waivers: List<Waiver>,
        now: Instant,
        subjectResolver: (subjectPath: String) -> String?,
        context: WaiverContext? = null,
    ): WaiverApplication {
        val outcomes = report.results.entries
            .flatMap { (ruleId, evaluation) ->
                // B4-T3: an ERROR carries a DIAGNOSTIC, not an eximable
                // violation. `Error.violations` returns `listOf(primary)`, so
                // flat-mapping over it unconditionally handed operational
                // failures to the waiver machinery and they could be waived.
                // A waiver must never silence an evaluation error: doing so
                // would hide a broken policy behind an exemption.
                //
                // The error is NOT dropped either. It is still surfaced as an
                // outcome so it stays visible and counted, it simply cannot
                // carry a waiver id.
                when (evaluation) {
                    is RuleEvaluation.Violated -> evaluation.violations.map { Triple(ruleId, it, true) }
                    is RuleEvaluation.Error -> evaluation.violations.map { Triple(ruleId, it, false) }
                    else -> emptyList()
                }
            }
            .map { (ruleId, violation, waivable) ->
                val fingerprint = ViolationFingerprint.of(
                    policyId = ruleId.policyId,
                    ruleId = ruleId.value,
                    location = violation.location.toString(),
                    resourceFingerprint = report.resourceFingerprint,
                )
                val candidate = if (!waivable) {
                    null
                } else {
                    waivers.firstOrNull { w ->
                        w.policyId == ruleId.policyId &&
                            w.ruleId == ruleId.ruleId &&
                            subjectMatches(w, subjectResolver) &&
                            scopeMatches(w, context) &&
                            fingerprintMatches(w, fingerprint)
                    }
                }
                when {
                    candidate == null -> ViolationWaiverOutcome.Active(fingerprint)
                    now > candidate.notAfter ->
                        ViolationWaiverOutcome.ActiveWithDiagnostic(
                            fingerprint,
                            candidate.id,
                            WaiverDiagnosticCause.WaiverExpired,
                        )
                    now < candidate.notBefore ->
                        ViolationWaiverOutcome.ActiveWithDiagnostic(
                            fingerprint,
                            candidate.id,
                            WaiverDiagnosticCause.WaiverNotYetValid,
                        )
                    else -> ViolationWaiverOutcome.Waived(fingerprint, candidate.id)
                }
            }
        return WaiverApplication(outcomes)
    }

    private fun subjectMatches(w: Waiver, resolver: (String) -> String?): Boolean =
        resolver(w.subject.subjectPath) == w.subject.subjectEquals

    /**
     * B4-T3: `datasetScope` and `projectScope` were declared on [Waiver] and
     * read by nothing, so a waiver narrowed to one project silently silenced
     * violations in every project.
     *
     * A scope the waiver names must match the supplied [context] exactly. A
     * null context field does NOT mean "any project": it means the context
     * does not know, and a scoped waiver cannot be honored without it. The
     * difference matters — treating absence as a wildcard is precisely the
     * behavior this removes.
     */
    private fun scopeMatches(w: Waiver, context: WaiverContext?): Boolean =
        scopeSatisfied(w.projectScope, context?.project) &&
            scopeSatisfied(w.datasetScope, context?.dataset)

    /** An unscoped dimension is satisfied by anything; a scoped one needs an exact hit. */
    private fun scopeSatisfied(waiverScope: String?, actual: String?): Boolean =
        waiverScope == null || waiverScope == actual

    private fun fingerprintMatches(w: Waiver, fingerprint: ViolationFingerprint): Boolean =
        w.violationFingerprint == null || w.violationFingerprint == fingerprint
}
