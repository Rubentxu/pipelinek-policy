package com.pipelinek.policy.plugin

import com.pipelinek.policy.kernel.governance.EnforcementMode
import dev.rubentxu.pipeline.v2.dsl.StageScope
import java.util.Base64

/**
 * M6 REQ-04 · external DSL facade for the `policy.check` step.
 *
 * Everything this façade does is declarative: it encodes the typed input and
 * hands it to the generic `registryStep` primitive. It does not resolve the
 * runtime registry, does not execute the handler and does not know how the
 * check will run. Core never learns the name `policy.check`.
 *
 * ```kotlin
 * import com.pipelinek.policy.plugin.policyCheck
 *
 * pipeline {
 *     stages {
 *         stage("Policy gate") {
 *             policyCheck(resource = "service.json", format = "JSON", bundle = "policy.pkg")
 *         }
 *         stage("Shadow gate") {
 *             policyCheck(
 *                 resource = "service.json",
 *                 bundle = "policy.pkg",
 *                 enforcement = EnforcementMode.SHADOW,
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * The resource and bundle paths are read at script-COMPILE time (the façade
 * runs inside the script host) and travel Base64-encoded inside the step
 * input, so the handler itself stays pure (law 5).
 *
 * B4.4 / B4-T5 · `enforcement` is declared here and defaults to ENFORCED. The
 * mode is the CORE enum, not a String, so the script and the handler cannot
 * disagree about what `SHADOW` means. Before this slice the parameter did not
 * exist and every DSL-authored step was silently ENFORCED, however correct the
 * payload layer was: the only way to reach SHADOW was to hand-build a
 * `PolicyCheckInput` in a test, which is why the gap stayed invisible.
 */
fun StageScope.policyCheck(
    resource: String,
    format: String = "JSON",
    bundle: String,
    enforcement: EnforcementMode = EnforcementMode.ENFORCED,
) {
    val input = PolicyCheckInput(
        resourceBase64 = Base64.getEncoder().encodeToString(bytesOf(resource)),
        resourceFormat = format,
        packedBundleBase64 = Base64.getEncoder().encodeToString(bytesOf(bundle)),
        enforcement = enforcement,
    )
    registryStep(
        stepKey = PolicyCheckStepDefinition.KEY,
        encodedInput = PolicyCheckInputCodec.encode(input),
    )
}

private fun bytesOf(path: String): ByteArray =
    java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path))
