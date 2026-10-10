package com.pipelinek.policy.plugin

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.bundle.VerifiedBundle
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.ResourceDecoder
import com.pipelinek.policy.decoders.csv.CsvResourceDecoder
import com.pipelinek.policy.decoders.json.JsonResourceDecoder
import com.pipelinek.policy.decoders.map.MapAdapterDecoder
import com.pipelinek.policy.decoders.yaml.YamlResourceDecoder
import com.pipelinek.policy.kernel.governance.GovernanceContext
import com.pipelinek.policy.kernel.governance.PolicyCheckPlan
import com.pipelinek.policy.kernel.governance.PolicyCheckPlanRefusal
import com.pipelinek.policy.kernel.governance.PolicyCheckPlanRequest
import com.pipelinek.policy.kernel.governance.PolicyGovernance
import com.pipelinek.policy.kernel.policy.LayeredPolicy
import com.pipelinek.policy.kernel.policy.PolicyLayer
import com.pipelinek.policy.kernel.policy.ResourceSubjectSelector
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.policy.Waiver
import com.pipelinek.policy.kernel.policy.WaiverContext
import com.pipelinek.policy.kernel.value.ValueNode
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
import java.time.Instant
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

        // H1.2 / B4-T10: the verdict now comes back from the ONE governance
        // service, on the SAME composed policy set the CLI evaluates.
        //
        // What this replaced: `IrRuntimeAdapter.evaluate(bundle, tree).report`
        // followed by a tally counted here from `RuleEvaluation` values. The
        // comment above this block claimed the adapter "cannot disagree with the
        // CLI adapter" because precedence lives in the core. That was only
        // true of precedence, and it was false of everything else: the CLI
        // composed layers, applied waivers and checked grants, and this path did
        // none of the three. Two adapters agreed on precedence while evaluating
        // different policy sets — which is parity of the tally, not of the
        // decision.
        //
        // The plugin now declares no rule and counts nothing. It decodes, hands
        // the host's governance inputs to the service, and renders what comes
        // back.
        val governed = govern(ready.verifiedBundle, tree, input)
        return when (governed) {
            is PolicyGovernance.Result.Refused -> PolicyCheckOutput.refused(
                "layer composition refused: ${governed.refusal.code}",
            )
            is PolicyGovernance.Result.Decided -> {
                val outcome = governed.outcome
                val report = outcome.report
                val summaries = report.results.entries
                    .sortedBy { it.key.value }
                    .map { (key, ev) ->
                        RuleSummary(ruleId = key.ruleId, outcome = outcomeName(ev), policyId = key.policyId)
                    }
                PolicyCheckOutput.of(
                    // The CORE verdict, passed straight through: `of` owns the
                    // rename to the step vocabulary and the wouldDeny
                    // derivation. Mapping it here instead would re-decide
                    // enforcement a second time in the adapter.
                    verdict = outcome.verdict,
                    // The POST-waiver count: a waived violation is reported in
                    // summaries but does not count as an active one.
                    violationsCount = outcome.activeViolations,
                    reportDigest = report.digest,
                    policySetId = report.policySetId,
                    ruleSummaries = summaries,
                    enforcement = outcome.enforcement,
                )
            }
        }
    }

    /**
     * H1.2 / B4-T10 · the plugin's whole governance contribution.
     *
     * Three pure mappings — wire payload to kernel grants, wire layers to
     * [LayeredPolicy], wire waivers to [Waiver] — and one call into
     * [PolicyGovernance]. Everything that decides anything is in the service.
     *
     * The PROJECT layer is always present and always LAST: it is the bundle the
     * step was handed, and [LayerComposer] expects layers ordered from the
     * outermost authority inward.
     */
    private fun govern(
        verified: VerifiedBundle,
        tree: ValueNode,
        input: PolicyCheckInput,
    ): PolicyGovernance.Result {
        val payload = input.governance
        val extraLayers = payload.layers.map { layer ->
            LayeredPolicy(
                layer.toPolicyLayer(),
                // Each layer travels packed and is admitted through the SAME
                // `verifyPacked` call as the main bundle: a layer gets identical
                // terms or none. A layer that cannot be verified throws before
                // composition, which is fail-closed.
                BundleVerifier.verifyPacked(Base64.getDecoder().decode(layer.packedPolicyBase64))
                    .bundle.document.policySet,
            )
        }
        val waivers = payload.waivers.map { it.toWaiver() }

        return PolicyGovernance.decide(
            layers = extraLayers + LayeredPolicy(
                PolicyLayer.PROJECT,
                verified.bundle.document.policySet,
            ),
            tree = tree,
            waivers = waivers,
            context = GovernanceContext(
                authorities = payload.toAuthorityRegistry(),
                now = Instant.parse(input.now),
                subjectResolver = { it },
                waiverContext = WaiverContext(),
            ),
            enforcement = input.enforcement,
            evaluate = { policySet, node ->
                IrRuntimeAdapter.evaluateSet(policySet, verified.bundle.sourceMap, node).report
            },
        )
    }

    /**
     * Wire waiver to kernel waiver.
     *
     * A malformed instant or an empty identity is a REFUSAL that surfaces as a
     * thrown [IllegalArgumentException] from the payload type before this runs:
     * a waiver that silently stopped applying would be an unnoticed exemption,
     * which is the one failure this whole path exists to prevent.
     */
    private fun WaiverPayload.toWaiver(): Waiver {
        val (from, to) = validity()
        return Waiver(
            id = id,
            policyId = policyId,
            ruleId = ruleId,
            subject = ResourceSubjectSelector(subjectPath, subjectEquals),
            datasetScope = datasetScope,
            projectScope = projectScope,
            reason = reason,
            issuer = issuer,
            notBefore = from,
            notAfter = to,
        )
    }

    private fun outcomeName(ev: RuleEvaluation): String = when (ev) {
        RuleEvaluation.Passed -> "passed"
        RuleEvaluation.NotApplicable -> "not-applicable"
        is RuleEvaluation.Violated -> "violated"
        is RuleEvaluation.Error -> "error"
    }
}
