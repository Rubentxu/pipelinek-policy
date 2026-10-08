package com.pipelinek.policy.kernel.policy

import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.evaluator.RuleId
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
            val raw = "$policyId|$ruleId|$location|$resourceFingerprint"
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
    ): WaiverApplication {
        val outcomes = report.results.entries
            .flatMap { (ruleId, evaluation) -> evaluation.violations.map { ruleId to it } }
            .map { (ruleId, violation) ->
                val fingerprint = ViolationFingerprint.of(
                    policyId = report.policySetId,
                    ruleId = ruleId.value,
                    location = violation.location.toString(),
                    resourceFingerprint = report.resourceFingerprint,
                )
                val candidate = waivers.firstOrNull { w ->
                    w.ruleId == ruleId.value &&
                        subjectMatches(w, subjectResolver) &&
                        fingerprintMatches(w, fingerprint)
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

    private fun fingerprintMatches(w: Waiver, fingerprint: ViolationFingerprint): Boolean =
        w.violationFingerprint == null || w.violationFingerprint == fingerprint
}
