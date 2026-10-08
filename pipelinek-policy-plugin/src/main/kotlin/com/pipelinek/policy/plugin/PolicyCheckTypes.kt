package com.pipelinek.policy.plugin

import kotlinx.serialization.Serializable

/**
 * M6 REQ-02 · typed request carried by the `policy.check` registry step.
 *
 * Everything the pure handler needs travels ENCODED in the input: the resource
 * bytes, its format, and the packed policy bundle. The handler performs zero
 * I/O (architectural law 5); reading the resource is the pipeline's job
 * (`readFile` feeds this input via the DSL façade).
 */
@Serializable
data class PolicyCheckInput(
    val resourceBase64: String,
    val resourceFormat: String,
    val packedBundleBase64: String,
)

/** Closed verdict vocabulary. REFUSED is operational failure, never a policy result. */
@Serializable
enum class PolicyCheckVerdict { PASSED, VIOLATED, REFUSED }

/** One rule's outcome summary, ordered by rule id for canonical display. */
@Serializable
data class RuleSummary(
    val ruleId: String,
    val outcome: String,
)
