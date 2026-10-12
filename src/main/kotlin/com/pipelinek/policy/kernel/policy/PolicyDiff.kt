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
            // RC-S2: only rules whose effective verdict actually changes because
            // of the waiver are reported as WAIVER_EFFECT_CHANGED. A blanket
            // "raw states identical => drop the base entry" filter previously
            // erased declared severity changes too, because severity lives
            // outside the verdict comparison. The filter now considers the
            // POST-waiver verdict (a state change driven by the waiver) rather
            // than the raw verdict, so a severity-only change in the base
            // diff survives the waiver-aware overload with empty applications.
            val base = diffStates(a, b) { ruleId ->
                stateOf(a, ruleId, waivedAByRule[ruleId] ?: false) to
                    stateOf(b, ruleId, waivedBByRule[ruleId] ?: false)
            }
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
            return PolicyDiff(base.entries + waiverEntries)
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
                // B4.5 / ADR-0015 — a severity change is a change even when
                // the verdict is untouched, so it has to be decided BEFORE the
                // `sa == sb` short-circuit below. Otherwise the one and only
                // thing this slice exists to report is swallowed by the state
                // comparison and `SEVERITY_CHANGED` stays permanently dead.
                // RC-S2: SEVERITY_CHANGED is bilateral. A one-sided declaration
                // is not a change: the author of the side that stayed silent
                // never claimed one, so there is no second value to have
                // changed from. The guard was previously
                // `severityOf(a) != null && severityOf(a) != severityOf(b)`,
                // which accepted (CRITICAL, null) and emitted a fabricated
                // change.
                val severityA = severityOf(a, ruleId)
                val severityB = severityOf(b, ruleId)
                val severityChanged = severityA != null && severityB != null && severityA != severityB
                if (sa == sb && !severityChanged) return@flatMap emptyList()
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
                    sa is RuleState.NotApplicable && sb !is RuleState.NotApplicable ->
                        DiffCategory.APPLICABILITY_CHANGED
                    sa !is RuleState.NotApplicable && sb is RuleState.NotApplicable ->
                        DiffCategory.APPLICABILITY_CHANGED
                    // B4.5 / ADR-0015 — SEVERITY_CHANGED is no longer a blind
                    // `else`. It is emitted ONLY when both bundles declare a
                    // severity for this rule and the two differ. A one-sided
                    // declaration is not a severity change: the author of the
                    // side that stayed silent never claimed one, so there is
                    // no second value to have changed from.
                    severityChanged -> DiffCategory.SEVERITY_CHANGED
                    // Every state transition is now named above. Anything
                    // reaching here is genuinely not a change and must produce
                    // NO entry rather than be filed under an invented category.
                    else -> return@flatMap emptyList()
                }
                val expansion = category == DiffCategory.RESOLVED_VIOLATION
                listOf(DiffEntry(category, ruleId, fp.value, expansion))
            }
            return PolicyDiff(entries)
        }

        /**
         * B4.5 / ADR-0015 — declared severity for a rule, or null when the
         * bundle's author declared none. Never defaults to a value.
         */
        private fun severityOf(report: PolicyReport, ruleId: RuleKey): RuleSeverity? =
            report.severities[ruleId]

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
                policySetId = ruleId.policySetId,
                policyId = ruleId.policyId,
                ruleId = ruleId.ruleId,
                location = location,
                resourceFingerprint = report.resourceFingerprint,
            )
        }

        private fun indexWaivedByRule(report: PolicyReport, application: WaiverApplication): Map<RuleKey, Boolean> {
            // H1.2: this used to rebuild each violation fingerprint from the
            // report and compare against the waived ones. The matcher already
            // resolved every (rule, violation) pair, so asking it is both
            // cheaper and immune to a reader rebuilding the digest differently.
            val waived = application.waivedRuleKeys
            return report.results.mapValues { (ruleId, _) -> ruleId in waived }
        }
    }
}
