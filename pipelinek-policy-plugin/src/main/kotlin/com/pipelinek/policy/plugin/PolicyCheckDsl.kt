package com.pipelinek.policy.plugin

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
 *     }
 * }
 * ```
 *
 * The resource and bundle paths are read at script-COMPILE time (the façade
 * runs inside the script host) and travel Base64-encoded inside the step
 * input, so the handler itself stays pure (law 5).
 */
fun StageScope.policyCheck(resource: String, format: String = "JSON", bundle: String) {
    val input = PolicyCheckInput(
        resourceBase64 = Base64.getEncoder().encodeToString(bytesOf(resource)),
        resourceFormat = format,
        packedBundleBase64 = Base64.getEncoder().encodeToString(bytesOf(bundle)),
    )
    registryStep(
        stepKey = PolicyCheckStepDefinition.KEY,
        encodedInput = PolicyCheckInputCodec.encode(input),
    )
}

private fun bytesOf(path: String): ByteArray =
    java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path))
