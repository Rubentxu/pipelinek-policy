package com.pipelinek.policy.plugin

import kotlinx.serialization.Serializable

/**
 * M6 REQ-02 · typed request carried by the `policy.check` registry step.
 *
 * Everything the pure handler needs travels ENCODED in the input: the resource
 * bytes, its format, and the packed policy bundle. The handler performs zero
 * I/O (architectural law 5); reading the resource is the pipeline's job
 * (`readFile` feeds this input via the DSL façade).
 *
 * M7 REQ-M7-04 adds `enforcement` (default ENFORCED): in SHADOW the policy
 * still evaluates and reports violations, but the step outcome never fails
 * (explicit state per spec §6 — never catch-and-continue). M6 payloads
 * without the field decode to ENFORCED (back-compat REQ-M7-06).
 */
@Serializable
data class PolicyCheckInput(
    val resourceBase64: String,
    val resourceFormat: String,
    val packedBundleBase64: String,
    val enforcement: EnforcementMode = EnforcementMode.ENFORCED,
)

/** Closed verdict vocabulary. REFUSED is operational failure, never a policy result. */
@Serializable
enum class PolicyCheckVerdict {
    /** Evaluation ran but at least one rule errored (typed refusal). Fail-closed. */
    ERRORED,
    PASSED,
    VIOLATED,
    REFUSED,
}

/**
 * M7 REQ-M7-04 · enforcement mode. SHADOW: the policy computes would-block
 * findings without changing the pipeline outcome (spec §6).
 */
@Serializable
enum class EnforcementMode { ENFORCED, SHADOW }

/** One rule's outcome summary, ordered by rule id for canonical display. */
@Serializable
data class RuleSummary(
    val ruleId: String,
    val outcome: String,
    val policyId: String? = null,
)
