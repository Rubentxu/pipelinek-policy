package com.pipelinek.policy.plugin

import com.pipelinek.policy.kernel.governance.EnforcementMode
import kotlinx.serialization.Serializable

/**
 * M6 REQ-02 · typed request carried by the `policy.check` registry step.
 *
 * `EnforcementMode` is owned by the core since B4-T8 and imported here, NOT
 * typealiased: a typealias would not preserve the fully-qualified type
 * identity kotlinx.serialization resolves the enum descriptor by.
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
    /**
     * H1.2 / B4-T10 · the host's governance inputs: its own grants, any layers
     * above the project one, and the waivers it admits.
     *
     * This is the field whose absence was H1.2. Without it the step evaluated a
     * bare policy set with no composition, no waiver and no authority check, so
     * a bundle could assert a supersession the step never verified and nothing
     * could ever be exempted through the plugin path at all.
     *
     * It defaults to EMPTY rather than being required, so existing M6/M7
     * payloads decode unchanged (REQ-M7-06) — but EMPTY is the FAIL-CLOSED
     * reading: no grant means no supersession is honoured, and no waiver means
     * nothing is exempted.
     */
    val governance: PolicyGovernancePayload = PolicyGovernancePayload.EMPTY,
    /**
     * The instant waiver windows are evaluated against (law 5: the kernel
     * never reads a clock, the host supplies time).
     *
     * Defaults to the epoch so an M6 payload that declares a waiver but no time
     * evaluates every waiver as NOT_YET_VALID — failing closed rather than
     * failing open.
     */
    val now: String = "1970-01-01T00:00:00Z",
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

/** One rule's outcome summary, ordered by rule id for canonical display. */
@Serializable
data class RuleSummary(
    val ruleId: String,
    val outcome: String,
    val policyId: String? = null,
)
