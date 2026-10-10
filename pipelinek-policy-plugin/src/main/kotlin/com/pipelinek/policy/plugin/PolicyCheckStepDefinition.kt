package com.pipelinek.policy.plugin

import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.ResourceDecoder
import com.pipelinek.policy.decoders.csv.CsvResourceDecoder
import com.pipelinek.policy.decoders.json.JsonResourceDecoder
import com.pipelinek.policy.decoders.map.MapAdapterDecoder
import com.pipelinek.policy.decoders.yaml.YamlResourceDecoder
import com.pipelinek.policy.kernel.governance.EnforcementInterpreter
import com.pipelinek.policy.kernel.governance.GovernanceTally
import com.pipelinek.policy.kernel.governance.PolicyCheckPlan
import com.pipelinek.policy.kernel.governance.PolicyCheckPlanRefusal
import com.pipelinek.policy.kernel.governance.PolicyCheckPlanRequest
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.registry.PLUGIN_EVENT_EMISSION_CAPABILITY
import dev.rubentxu.pipeline.v2.events.registry.PluginEventEmission
import kotlinx.serialization.json.Json

/** REQ-02 · symmetric codecs over canonical JSON, same shape as the SDK examples. */
object PolicyCheckInputCodec : StepCodec<PolicyCheckInput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: PolicyCheckInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(PolicyCheckInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): PolicyCheckInput =
        json.decodeFromString(PolicyCheckInput.serializer(), encoded.value)
}

object PolicyCheckOutputCodec : StepCodec<PolicyCheckOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: PolicyCheckOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(PolicyCheckOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): PolicyCheckOutput =
        json.decodeFromString(PolicyCheckOutput.serializer(), encoded.value)
}

/**
 * M6 REQ-01/03 · the `policy.check` registry step.
 *
 * Atomic (no body), controller-side, READ_ONLY, MEMOIZED. The handler is PURE:
 * bytes arrive Base64-encoded in the input, decoding/verification/evaluation
 * is in-process, and no filesystem, network, clock or random source is
 * touched (laws 4/5). A multi-document decode is refused rather than guessed
 * (the check is over ONE resource).
 */
object PolicyCheckStepDefinition : StepDefinition<PolicyCheckInput, PolicyCheckOutput> {

    const val PLUGIN_ID: String = "com.pipelinek.policy"
    const val PLUGIN_VERSION: String = "0.1.0"

    val KEY = PluginStepId("policy.check")

    /** REQ-05 · the event kind this plugin owns, emitted after every check. */
    const val REPORTED_EVENT_KIND: String = "policy.check.reported"

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "policyCheck",
            configRef = "",
            pluginId = PLUGIN_ID,
            pluginVersion = PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = PolicyCheckInputCodec,
        outputCodec = PolicyCheckOutputCodec,
        requiredCapabilities = setOf(PLUGIN_EVENT_EMISSION_CAPABILITY),
    )

    /** Decoders available to the handler; a val so tests can reason about the set. */
    val decoders: List<ResourceDecoder> = listOf(
        JsonResourceDecoder(),
        YamlResourceDecoder(),
        CsvResourceDecoder(),
        MapAdapterDecoder(),
    )

    override val handler = StepHandler<PolicyCheckInput, PolicyCheckOutput> { input, context ->
        val output = evaluate(input)
        // REQ-05/06b: report the outcome through the typed emission seam. The
        // emission result is NOT inspected: the check itself already succeeded,
        // and an observation is not the work (same rationale as the SDK example).
        val emission = context.capabilities.get<PluginEventEmission>(PLUGIN_EVENT_EMISSION_CAPABILITY)
        emission.emit(
            REPORTED_EVENT_KIND,
            PolicyCheckReported(
                verdict = output.verdict.name,
                violationsCount = output.violationsCount,
                reportDigest = output.reportDigest,
                policySetId = output.policySetId,
                enforcement = output.enforcement,
                wouldDeny = output.wouldDeny,
            ),
        )
        output
    }

    /**
     * Pure evaluation pipeline, reusable in tests without a runtime context.
     *
     * B4.6 / ADR-0016: admission is no longer decided HERE. This adapter
     * computes the plan through the kernel and then performs the ONE act the
     * plan obliges it to perform — decoding the admitted bytes as the admitted
     * format. It selects a decoder, which architectural law 6 forbids the core
     * from doing, and it does nothing else in that role.
     */
    fun evaluate(input: PolicyCheckInput): PolicyCheckOutput {
        val plan = PolicyCheckPlan.compute(
            PolicyCheckPlanRequest(
                resourceBase64 = input.resourceBase64,
                packedBundleBase64 = input.packedBundleBase64,
                declaredFormat = input.resourceFormat,
                availableDecoderFormats = decoders.map { it.descriptor.format }.toSet(),
            ),
        )
        val ready = when (plan) {
            is PolicyCheckPlan.PlanRefused ->
                return PolicyCheckOutput.refused(plan.refusal.render())
            is PolicyCheckPlan.PlanReady -> plan
        }

        val decoder = decoders.firstOrNull { it.descriptor.format == ready.decodeFormat }
            ?: return PolicyCheckOutput.refused(
                PolicyCheckPlanRefusal.NO_DECODER_FOR_FORMAT.render(),
            )

        val tree = when (val decoded = decoder.decode(ready.resourceBytes)) {
            is DecodeResult.Ok -> decoded.documents.singleOrNull()?.root
                ?: return PolicyCheckOutput.refused(
                    // The vocabulary is closed (law 9): the count is a
                    // diagnostic, not a second vocabulary. A literal count in
                    // the wire string would be exactly that.
                    PolicyCheckPlanRefusal.MULTI_DOCUMENT_RESOURCE.render() +
                        ": expected exactly one document, got " + decoded.documents.size,
                )
            // The plan cannot know this: it is the decoder's answer, and it
            // arrives after the plan is computed (ADR-0016).
            is DecodeResult.Refused ->
                return PolicyCheckOutput.refused(
                    PolicyCheckPlanRefusal.RESOURCE_DECODE_REFUSED.render() + ": " +
                        decoded.refusal.code.name,
                )
        }

        val report = IrRuntimeAdapter.evaluate(ready.verifiedBundle, tree).report
        val summaries = report.results.entries
            .sortedBy { it.key.value }
            .map { (key, ev) ->
                RuleSummary(ruleId = key.ruleId, outcome = outcomeName(ev), policyId = key.policyId)
            }
        // B0.5: the precedence REFUSED > ERRORED > VIOLATED > PASSED lives in the
        // core EnforcementInterpreter, not here. This adapter only COUNTS; it
        // implements no rule, so it cannot disagree with the CLI adapter.
        // Violations stay in `summaries` as evidence whatever the verdict is.
        val verdict = EnforcementInterpreter.verdict(
            GovernanceTally(
                errors = report.results.values.count { it is RuleEvaluation.Error },
                violations = report.results.values.count { it is RuleEvaluation.Violated },
            ),
        )
        return PolicyCheckOutput.of(
            verdict = verdict,
            violationsCount = report.results.values.count { it is RuleEvaluation.Violated },
            reportDigest = report.digest,
            policySetId = report.policySetId,
            ruleSummaries = summaries,
            enforcement = input.enforcement,
        )
    }

    private fun outcomeName(ev: RuleEvaluation): String = when (ev) {
        RuleEvaluation.Passed -> "passed"
        RuleEvaluation.NotApplicable -> "not-applicable"
        is RuleEvaluation.Violated -> "violated"
        is RuleEvaluation.Error -> "error"
    }
}
