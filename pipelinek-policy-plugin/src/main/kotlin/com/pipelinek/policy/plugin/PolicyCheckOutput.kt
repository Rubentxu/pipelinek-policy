package com.pipelinek.policy.plugin

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
 */
@Serializable
data class PolicyCheckOutput(
    val verdict: PolicyCheckVerdict,
    val violationsCount: Int = 0,
    val reportDigest: String? = null,
    val policySetId: String? = null,
    val ruleSummaries: List<RuleSummary> = emptyList(),
    val refusalReason: String? = null,
) : TypedStepOutput {

    /**
     * DERIVED from [verdict]; never serialized (StepOutcome is a runtime ADT,
     * not wire data — the verdict enum is the wire form of the same fact).
     */
    @kotlinx.serialization.Transient
    override val outcome: StepOutcome = when (verdict) {
        PolicyCheckVerdict.PASSED -> StepOutcome.Success
        PolicyCheckVerdict.VIOLATED -> StepOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.PLUGIN,
                message = "policy check VIOLATED: $violationsCount violation(s), report digest $reportDigest",
            ),
        )
        PolicyCheckVerdict.REFUSED -> StepOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.PLUGIN,
                message = "policy check REFUSED: ${refusalReason ?: "unknown refusal"}",
            ),
        )
    }

    companion object {
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
        ): PolicyCheckOutput = PolicyCheckOutput(
            verdict = PolicyCheckVerdict.VIOLATED,
            violationsCount = violationsCount,
            reportDigest = reportDigest,
            policySetId = policySetId,
            ruleSummaries = ruleSummaries,
        )

        fun refused(reason: String): PolicyCheckOutput =
            PolicyCheckOutput(verdict = PolicyCheckVerdict.REFUSED, refusalReason = reason)
    }
}
