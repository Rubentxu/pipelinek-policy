package com.pipelinek.policy.plugin

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.ResourceDecoder
import com.pipelinek.policy.decoder.ResourceFormat
import com.pipelinek.policy.decoders.csv.CsvResourceDecoder
import com.pipelinek.policy.decoders.json.JsonResourceDecoder
import com.pipelinek.policy.decoders.map.MapAdapterDecoder
import com.pipelinek.policy.decoders.yaml.YamlResourceDecoder
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
import java.util.Base64

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

    /** Pure evaluation pipeline, reusable in tests without a runtime context. */
    fun evaluate(input: PolicyCheckInput): PolicyCheckOutput {
        val resourceBytes = try {
            Base64.getDecoder().decode(input.resourceBase64)
        } catch (e: IllegalArgumentException) {
            return PolicyCheckOutput.refused("resource is not valid Base64")
        }
        val packedBundle = try {
            Base64.getDecoder().decode(input.packedBundleBase64)
        } catch (e: IllegalArgumentException) {
            return PolicyCheckOutput.refused("packed bundle is not valid Base64")
        }

        val format = runCatching { ResourceFormat.valueOf(input.resourceFormat.uppercase()) }
            .getOrElse {
                return PolicyCheckOutput.refused("unknown resource format '${input.resourceFormat}'")
            }

        val decoder = decoders.firstOrNull { it.descriptor.format == format }
            ?: return PolicyCheckOutput.refused("no decoder for format $format")

        val tree = when (val decoded = decoder.decode(resourceBytes)) {
            is DecodeResult.Ok -> decoded.documents.singleOrNull()?.root
                ?: return PolicyCheckOutput.refused(
                    "expected exactly one resource document, got ${decoded.documents.size}",
                )
            is DecodeResult.Refused ->
                return PolicyCheckOutput.refused("decode refused: ${decoded.refusal.code.name}")
        }

        val verified = try {
            BundleVerifier.verifyPacked(packedBundle)
        } catch (e: IllegalArgumentException) {
            return PolicyCheckOutput.refused("bundle refused: ${e.message}")
        } catch (e: Exception) {
            // B0.2: ANY bundle admission failure refuses; a generic
            // engine/adapter crash must not leak as PASSED.
            return PolicyCheckOutput.refused("bundle refused: ${e::class.simpleName}: ${e.message}")
        }

        val report = IrRuntimeAdapter.evaluate(verified, tree).report
        val summaries = report.results.entries
            .sortedBy { it.key.value }
            .map { (key, ev) ->
                RuleSummary(ruleId = key.ruleId, outcome = outcomeName(ev), policyId = key.policyId)
            }
        val violations = report.results.values.count { it is RuleEvaluation.Violated }
        // B0.1 fail-closed: Error is an operational failure, never a pass.
        // Precedence: REFUSED (admission) > ERRORED > VIOLATED > PASSED —
        // but violations remain visible in summaries either way.
        val errors = report.results.values.count { it is RuleEvaluation.Error }

        return when {
            violations > 0 ->
                PolicyCheckOutput.violated(violations, report.digest, report.policySetId, summaries, input.enforcement)
            errors > 0 ->
                PolicyCheckOutput.errored(errors, report.digest, report.policySetId, summaries)
            else ->
                PolicyCheckOutput.passed(report.digest, report.policySetId, summaries)
        }
    }

    private fun outcomeName(ev: RuleEvaluation): String = when (ev) {
        RuleEvaluation.Passed -> "passed"
        RuleEvaluation.NotApplicable -> "not-applicable"
        is RuleEvaluation.Violated -> "violated"
        is RuleEvaluation.Error -> "error"
    }
}
