package com.pipelinek.policy.kernel.governance

/**
 * B4.3/B0.5 · the operational verdict of one policy check, and the ONLY place
 * where the precedence between evaluation outcomes is decided.
 *
 * Both adapters (CLI and plugin) delegate here. Neither adapter implements the
 * rule; that is what makes them structurally incapable of diverging
 * (ROADMAP §"Paridad CLI/plugin por construcción").
 *
 * Precedence is `REFUSED > ERRORED > VIOLATED > PASSED` and is total:
 * every input maps to exactly one [GovernanceVerdict], with no default branch
 * and no string reason (architectural law 9).
 *
 * The distinction that B0.5 restores is between a POLICY verdict and an
 * OPERATIONAL one. `Error` means the evaluation itself could not be completed,
 * so it is an operational failure and outranks any violation found alongside
 * it. Reporting `VIOLATED` because a violation happened to be present is what
 * let an operational failure pass for compliance.
 */
enum class GovernanceVerdict {
    /** Admission or decoding failed: nothing about the resource was evaluated. */
    REFUSED,

    /** At least one rule could not be evaluated. Operational failure, fail-closed. */
    ERRORED,

    /** Every rule evaluated and at least one was violated. A genuine policy verdict. */
    VIOLATED,

    /** Every rule evaluated to Passed or NotApplicable. */
    PASSED,
}

/**
 * Counts of the three operational states, carried as DATA so the interpreter
 * stays a total function over a closed vocabulary (no free-form reasons).
 */
data class GovernanceTally(
    val refusals: Int = 0,
    val errors: Int = 0,
    val violations: Int = 0,
) {
    init {
        require(refusals >= 0 && errors >= 0 && violations >= 0) { "tally counts must be non-negative" }
    }
}

/**
 * B4.3 · enforcement interpreter: turns a tally plus the enforcement mode into
 * the operational decision. Pure, parameterised, no clock, no I/O (law 5).
 */
object EnforcementInterpreter {

    /**
     * Total, ordered precedence. Refusal outranks everything because nothing
     * was evaluated; an evaluation error outranks any violation because the
     * report is incomplete.
     */
    fun verdict(tally: GovernanceTally): GovernanceVerdict = when {
        tally.refusals > 0 -> GovernanceVerdict.REFUSED
        tally.errors > 0 -> GovernanceVerdict.ERRORED
        tally.violations > 0 -> GovernanceVerdict.VIOLATED
        else -> GovernanceVerdict.PASSED
    }

    /**
     * True iff this verdict *would* deny the operation under ENFORCED.
     *
     * SHADOW never denies on a policy verdict, and NEVER denies on an
     * operational failure either: `REFUSED` and `ERRORED` are failures in both
     * modes. "Could not evaluate" must never read as "compliant".
     */
    fun wouldDeny(verdict: GovernanceVerdict, enforcement: EnforcementMode): Boolean = when (verdict) {
        GovernanceVerdict.REFUSED, GovernanceVerdict.ERRORED -> true
        GovernanceVerdict.VIOLATED -> enforcement == EnforcementMode.ENFORCED
        GovernanceVerdict.PASSED -> false
    }

    /**
     * True iff the operation must be denied NOW.
     *
     * An operational failure denies in BOTH modes: "could not evaluate" is not
     * a policy result and is never shadowed. A genuine violation denies only
     * under ENFORCED; under SHADOW it becomes would-deny evidence instead.
     */
    fun denies(verdict: GovernanceVerdict, enforcement: EnforcementMode): Boolean = when (verdict) {
        GovernanceVerdict.REFUSED, GovernanceVerdict.ERRORED -> true
        GovernanceVerdict.VIOLATED -> enforcement == EnforcementMode.ENFORCED
        GovernanceVerdict.PASSED -> false
    }

    /**
     * True iff this run produced would-deny EVIDENCE: a genuine policy
     * violation that SHADOW suppressed instead of enforcing.
     *
     * An operational failure is deliberately NOT would-deny evidence. It is a
     * failure in both modes; labelling it "would have denied" would imply a
     * policy result that was never produced.
     */
    fun isWouldDenyEvidence(verdict: GovernanceVerdict, enforcement: EnforcementMode): Boolean =
        verdict == GovernanceVerdict.VIOLATED && enforcement == EnforcementMode.SHADOW

    /** True iff the verdict is an operational failure rather than a policy result. */
    fun isOperationalFailure(verdict: GovernanceVerdict): Boolean =
        verdict == GovernanceVerdict.REFUSED || verdict == GovernanceVerdict.ERRORED
}
