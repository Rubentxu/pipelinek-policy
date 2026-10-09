package com.pipelinek.policy.kernel.policy

import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.evaluator.RuleKey

/**
 * M7 §"Semantic diff" — deterministic A/B diff over the SAME corpus.
 *
 * `PolicyDiff.of(a, b)` compares two [PolicyReport]s produced by evaluating
 * bundle A and bundle B against the same resource (same
 * `resourceFingerprint`; a different fingerprint is a typed refusal — the
 * caller compared apples to oranges). Violation identity is the
 * [ViolationFingerprint] shared with the waiver matcher.
 *
 * `diffDigest` is canonical: categories sorted, entries sorted by
 * fingerprint. Same corpus + same A/B ⇒ same digest (spec §9).
 */
sealed class PolicyDiffRefusal(message: String) : IllegalArgumentException(message) {
    data class CorpusMismatch(
        val fingerprintA: String,
        val fingerprintB: String,
    ) : PolicyDiffRefusal(
            "cannot diff reports over different corpora: " +
                "$fingerprintA vs $fingerprintB (spec §7 requires the same corpus)",
        )
}

enum class DiffCategory {
    NEW_VIOLATION,
    RESOLVED_VIOLATION,
    ENFORCEMENT_INCREASED,
    ENFORCEMENT_DECREASED,
    SEVERITY_CHANGED,
    ERROR_INTRODUCED,
    ERROR_RESOLVED,
    APPLICABILITY_CHANGED,
    WAIVER_EFFECT_CHANGED,
}

data class DiffEntry(
    val category: DiffCategory,
    val ruleKey: RuleKey,
    val fingerprint: String,
    /** Spec §8: possible privilege expansion / reduced restriction. */
    val privilegeExpansion: Boolean = false,
) {
    val ruleId: String get() = ruleKey.ruleId
    val policyId: String get() = ruleKey.policyId
    val policySetId: String get() = ruleKey.policySetId
}

data class PolicyDiff(
    val entries: List<DiffEntry>,
) {
    /** Canonical SHA-256 over sorted categories + sorted fingerprints. */
    val diffDigest: String by lazy {
        val sorted = entries
            .sortedWith(compareBy({ it.category.name }, { it.fingerprint }, { it.ruleKey.value }))
        val canonical = buildString {
            append(sorted.size).append(':')
            sorted.forEach { entry ->
                append(encodeDiffParts(
                    entry.category.name,
                    entry.fingerprint,
                    entry.ruleKey.value,
                    entry.privilegeExpansion.toString(),
                ))
            }
        }
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {

        private fun encodeDiffParts(vararg parts: String): String = parts.joinToString("") { part ->
            "${part.length}:$part"
        }

        /** Plain report diff (no waiver application). */
        fun of(a: PolicyReport, b: PolicyReport): PolicyDiff {
            requireSameCorpus(a, b)
            return diffStates(a, b) { ruleId -> stateOf(a, ruleId) to stateOf(b, ruleId) }
        }

        /**
         * Waiver-aware diff: compares the POST-waiver states. A rule whose
         * violation was active in A and waived in B (or vice versa) yields
         * WAIVER_EFFECT_CHANGED.
         */
        fun of(a: PolicyReport, b: PolicyReport, waivedA: WaiverApplication, waivedB: WaiverApplication): PolicyDiff {
            requireSameCorpus(a, b)
            val waivedAByRule = indexWaivedByRule(a, waivedA)
            val waivedBByRule = indexWaivedByRule(b, waivedB)
            // Rules whose RAW states are identical: any effective-state change
            // comes ONLY from the waiver application, so they are reported
            // exclusively as WAIVER_EFFECT_CHANGED below (no double count).
            val rawIdentical = a.results.keys.union(b.results.keys)
                .filter { ruleId -> stateOf(a, ruleId) == stateOf(b, ruleId) }
                .toSet()
            val base = diffStates(a, b) { ruleId ->
                stateOf(a, ruleId, waivedAByRule[ruleId] ?: false) to
                    stateOf(b, ruleId, waivedBByRule[ruleId] ?: false)
            }
            val baseEntries = base.entries
                .filterNot { it.ruleKey in rawIdentical }
            // Waiver-only changes: same raw states, different waiver effect.
            val waiverEntries = a.results.keys.union(b.results.keys).mapNotNull { ruleId ->
                val wa = waivedAByRule[ruleId] ?: false
                val wb = waivedBByRule[ruleId] ?: false
                val rawSame = stateOf(a, ruleId) == stateOf(b, ruleId)
                if (rawSame && wa != wb) {
                    val fp = fingerprintOf(if (a.results.containsKey(ruleId)) a else b, ruleId)
                    DiffEntry(DiffCategory.WAIVER_EFFECT_CHANGED, ruleId, fp.value)
                } else {
                    null
                }
            }
            return PolicyDiff(baseEntries + waiverEntries)
        }

        private fun requireSameCorpus(a: PolicyReport, b: PolicyReport) {
            if (a.resourceFingerprint != b.resourceFingerprint) {
                throw PolicyDiffRefusal.CorpusMismatch(a.resourceFingerprint, b.resourceFingerprint)
            }
        }

        @Suppress("CyclomaticComplexMethod", "ComplexMethod", "LongMethod") // one branch per diff category, by design
        private fun diffStates(
            a: PolicyReport,
            b: PolicyReport,
            states: (RuleKey) -> Pair<RuleState, RuleState>,
        ): PolicyDiff {
            val ruleIds = a.results.keys.union(b.results.keys)
            val entries = ruleIds.flatMap { ruleId ->
                val (sa, sb) = states(ruleId)
                if (sa == sb) return@flatMap emptyList()
                val fp = fingerprintOf(if (a.results.containsKey(ruleId)) a else b, ruleId)
                val category = when {
                    sa is RuleState.Errored && sb !is RuleState.Errored -> DiffCategory.ERROR_RESOLVED
                    sa !is RuleState.Errored && sb is RuleState.Errored -> DiffCategory.ERROR_INTRODUCED
                    // Violated/Passed transitions take precedence: a rule
                    // absent from one bundle is not "applicability" — the
                    // rule simply did not exist there (NEW/RESOLVED).
                    sa is RuleState.Violated && sb !is RuleState.Violated -> {
                        // Deny -> Allow: mandatory violation disappearing is a
                        // possible privilege expansion (spec §8).
                        DiffCategory.RESOLVED_VIOLATION
                    }
                    sa !is RuleState.Violated && sb is RuleState.Violated -> DiffCategory.NEW_VIOLATION
                    else -> when {
                        sa is RuleState.NotApplicable && sb !is RuleState.NotApplicable ->
                            DiffCategory.APPLICABILITY_CHANGED
                        sa !is RuleState.NotApplicable && sb is RuleState.NotApplicable ->
                            DiffCategory.APPLICABILITY_CHANGED
                        else -> DiffCategory.SEVERITY_CHANGED
                    }
                }
                val expansion = category == DiffCategory.RESOLVED_VIOLATION
                listOf(DiffEntry(category, ruleId, fp.value, expansion))
            }
            return PolicyDiff(entries)
        }

        private sealed interface RuleState {
            data object Violated : RuleState
            data object Passed : RuleState
            data object NotApplicable : RuleState
            data object Errored : RuleState
        }

        private fun stateOf(report: PolicyReport, ruleId: RuleKey, waived: Boolean = false): RuleState {
            val evaluation = report.results[ruleId]
                ?: return RuleState.NotApplicable // rule absent from this bundle
            // A waived violation does not enforce: treat as not-violated.
            val effective = evaluation.let { eval ->
                if (waived && eval is RuleEvaluation.Violated) RuleEvaluation.Passed else eval
            }
            return when (effective) {
                is RuleEvaluation.Violated -> RuleState.Violated
                RuleEvaluation.Passed -> RuleState.Passed
                RuleEvaluation.NotApplicable -> RuleState.NotApplicable
                is RuleEvaluation.Error -> RuleState.Errored
            }
        }

        private fun fingerprintOf(report: PolicyReport, ruleId: RuleKey): ViolationFingerprint {
            val location = report.results[ruleId]?.violations?.firstOrNull()?.location?.toString() ?: "root"
            return ViolationFingerprint.of(
                policyId = ruleId.policyId,
                ruleId = ruleId.value,
                location = location,
                resourceFingerprint = report.resourceFingerprint,
            )
        }

        private fun indexWaivedByRule(report: PolicyReport, application: WaiverApplication): Map<RuleKey, Boolean> {
            val waivedFingerprints = application.outcomes
                .filterIsInstance<ViolationWaiverOutcome.Waived>()
                .map { it.fingerprint.value }
                .toSet()
            return report.results.mapValues { (ruleId, evaluation) ->
                evaluation.violations.any { v ->
                    ViolationFingerprint.of(
                        ruleId.policyId,
                        ruleId.value,
                        v.location.toString(),
                        report.resourceFingerprint,
                    ).value in waivedFingerprints
                }
            }
        }
    }
}
