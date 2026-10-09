package com.pipelinek.policy.plugin

import com.pipelinek.policy.kernel.governance.EnforcementInterpreter
import com.pipelinek.policy.kernel.governance.EnforcementMode
import com.pipelinek.policy.kernel.governance.GovernanceVerdict
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import kotlinx.serialization.Serializable

/**
 * M6 REQ-02/03 · typed result implementing [TypedStepOutput].
 *
 * Enforcement (REQ-03a): a VIOLATED verdict makes the step's outcome a Failure
 * carrying the report digest, mirroring `RepeatOutput.failedAt` in the SDK's
 * block plugin example — the boundary classifies it as the step's own
 * operational result, not an engine defect. REFUSED also fails (fail-closed,
 * REQ-01b), with a distinct message so a decode refusal can never masquerade
 * as a policy pass.
 *
 * M7 REQ-M7-04: in SHADOW enforcement the verdict and findings are preserved
 * (would-deny is data), but a genuine VIOLATED verdict does not fail the
 * outcome — the pipeline result is unchanged while the evidence is still
 * reported.
 *
 * B0.5: SHADOW never disguises an OPERATIONAL failure. `REFUSED` and
 * `ERRORED` mean the evaluation did not complete, and they fail in BOTH modes.
 * Only a genuine policy violation is shadowable. The precedence lives in the
 * core [EnforcementInterpreter], never in this adapter.
 */
@Serializable
data class PolicyCheckOutput(
    val verdict: PolicyCheckVerdict,
    val violationsCount: Int = 0,
    val reportDigest: String? = null,
    val policySetId: String? = null,
    val ruleSummaries: List<RuleSummary> = emptyList(),
    val refusalReason: String? = null,
    val enforcement: EnforcementMode = EnforcementMode.ENFORCED,
    /** True iff verdict==VIOLATED && enforcement==SHADOW (would have denied). */
    val wouldDeny: Boolean = false,
) : TypedStepOutput {

    /**
     * The core verdict this output was derived from. B0.5: the outcome is a
     * function of the CORE verdict and the enforcement mode, decided once in
     * [EnforcementInterpreter]. The adapter does not re-derive the precedence,
     * so a mixed report (some rules errored, some violated) can no longer be
     * reported as a clean pass under SHADOW.
     */
    @kotlinx.serialization.Transient
    private val governanceVerdict: GovernanceVerdict = when (verdict) {
        PolicyCheckVerdict.PASSED -> GovernanceVerdict.PASSED
        PolicyCheckVerdict.ERRORED -> GovernanceVerdict.ERRORED
        PolicyCheckVerdict.VIOLATED -> GovernanceVerdict.VIOLATED
        PolicyCheckVerdict.REFUSED -> GovernanceVerdict.REFUSED
    }

    @kotlinx.serialization.Transient
    private val failureMessage: String = when (governanceVerdict) {
        GovernanceVerdict.REFUSED -> "policy check REFUSED: ${refusalReason ?: "unknown refusal"}"
        GovernanceVerdict.ERRORED ->
            "policy check ERRORED: evaluation error(s), report digest $reportDigest"
        GovernanceVerdict.VIOLATED ->
            "policy check VIOLATED: $violationsCount violation(s), report digest $reportDigest"
        GovernanceVerdict.PASSED -> "policy check PASSED"
    }

    /**
     * DERIVED from [verdict] and [enforcement] through the shared kernel;
     * never serialized (StepOutcome is a runtime ADT, not wire data — the
     * verdict enum is the wire form of the same fact).
     */
    @kotlinx.serialization.Transient
    override val outcome: StepOutcome = when {
        EnforcementInterpreter.denies(governanceVerdict, enforcement) -> StepOutcome.Failure(
            PipelineFailure(kind = FailureKind.PLUGIN, message = failureMessage),
        )
        else -> StepOutcome.Success
    }

    companion object {
        /**
         * Single construction path from a core verdict. Both adapters reach
         * the same precedence through this door.
         *
         * No new wire field is introduced: the M6/M7 payload bytes are frozen,
         * and the per-rule `error` state is already carried by
         * [RuleSummary.outcome], so an error count would be redundant data.
         */
        fun of(
            verdict: GovernanceVerdict,
            violationsCount: Int,
            reportDigest: String,
            policySetId: String,
            ruleSummaries: List<RuleSummary>,
            enforcement: EnforcementMode = EnforcementMode.ENFORCED,
            refusalReason: String? = null,
        ): PolicyCheckOutput = PolicyCheckOutput(
            verdict = when (verdict) {
                GovernanceVerdict.PASSED -> PolicyCheckVerdict.PASSED
                GovernanceVerdict.ERRORED -> PolicyCheckVerdict.ERRORED
                GovernanceVerdict.VIOLATED -> PolicyCheckVerdict.VIOLATED
                GovernanceVerdict.REFUSED -> PolicyCheckVerdict.REFUSED
            },
            violationsCount = if (verdict == GovernanceVerdict.REFUSED) 0 else violationsCount,
            reportDigest = reportDigest,
            policySetId = policySetId,
            ruleSummaries = ruleSummaries,
            refusalReason = refusalReason,
            enforcement = enforcement,
            // Frozen semantics: "a genuine violation was suppressed by SHADOW".
            // An operational failure is NOT would-deny evidence, it is a failure.
            wouldDeny = EnforcementInterpreter.isWouldDenyEvidence(verdict, enforcement),
        )

        fun passed(
            reportDigest: String,
            policySetId: String,
            ruleSummaries: List<RuleSummary>,
        ): PolicyCheckOutput = PolicyCheckOutput(
            verdict = PolicyCheckVerdict.PASSED,
            reportDigest = reportDigest,
            policySetId = policySetId,
            ruleSummaries = ruleSummaries,
        )

        fun violated(
            violationsCount: Int,
            reportDigest: String,
            policySetId: String,
            ruleSummaries: List<RuleSummary>,
            enforcement: EnforcementMode = EnforcementMode.ENFORCED,
        ): PolicyCheckOutput = of(
            verdict = GovernanceVerdict.VIOLATED,
            violationsCount = violationsCount,
            reportDigest = reportDigest,
            policySetId = policySetId,
            ruleSummaries = ruleSummaries,
            enforcement = enforcement,
        )

        fun refused(reason: String): PolicyCheckOutput = PolicyCheckOutput(
            verdict = PolicyCheckVerdict.REFUSED,
            refusalReason = reason,
        )

        /** B0.1: evaluation errors are a distinct fail-closed verdict. */
        fun errored(
            errorCount: Int,
            reportDigest: String,
            policySetId: String,
            ruleSummaries: List<RuleSummary>,
        ): PolicyCheckOutput = of(
            verdict = GovernanceVerdict.ERRORED,
            violationsCount = 0,
            reportDigest = reportDigest,
            policySetId = policySetId,
            ruleSummaries = ruleSummaries,
        )
    }
}
