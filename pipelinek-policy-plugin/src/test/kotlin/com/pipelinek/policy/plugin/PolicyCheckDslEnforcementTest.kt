package com.pipelinek.policy.plugin

import com.pipelinek.policy.kernel.governance.EnforcementMode
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B4.4 / B4-T5 · the DSL façade must be able to DECLARE enforcement.
 *
 * Before this slice `PolicyCheckInput.enforcement` existed with default
 * ENFORCED but no façade could ever set it: `policyCheck(...)` took no such
 * parameter, so every DSL-authored step was silently ENFORCED. Every existing
 * enforcement test builds its input BY HAND, which is exactly why the hole was
 * invisible — the tests exercised the type, never the façade that authors it.
 *
 * These tests pin the input-construction seam the façade delegates to, and
 * assert the declared mode survives the round trip through the wire payload.
 * The seam is tested rather than the `StageScope` receiver because the façade
 * needs a pipeline script host to run; what matters here is that the mode the
 * author declares is the mode the handler receives.
 */
class PolicyCheckDslEnforcementTest {

    /** Exactly what the façade builds, minus the file reads it delegates. */
    private fun facadeInput(
        resourceBase64: String,
        format: String,
        bundleBase64: String,
        enforcement: EnforcementMode,
    ) = PolicyCheckInput(
        resourceBase64 = resourceBase64,
        resourceFormat = format,
        packedBundleBase64 = bundleBase64,
        enforcement = enforcement,
    )

    // --- T5-a: SHADOW declared at the façade reaches the encoded payload ---

    @Test
    fun `05a SHADOW declared through the facade reaches the encoded wire payload`() {
        val encoded = PolicyCheckInputCodec.encode(
            facadeInput("eyJmb28iOiJiYXIifQ==", "JSON", "UEtCMQ==", EnforcementMode.SHADOW),
        )

        assertTrue(
            encoded.value.contains(""""enforcement":"SHADOW""""),
            "the declared mode must be on the wire, not defaulted: ${encoded.value}",
        )
        assertEquals(
            EnforcementMode.SHADOW,
            PolicyCheckInputCodec.decode(encoded).enforcement,
        )
    }

    // --- T5-b: the default is ENFORCED, and it is a real default ---

    @Test
    fun `05b omitting the mode defaults to ENFORCED on the wire`() {
        val input = PolicyCheckInput(
            resourceBase64 = "eyJmb28iOiJiYXIifQ==",
            resourceFormat = "JSON",
            packedBundleBase64 = "UEtCMQ==",
        )

        assertEquals(
            EnforcementMode.ENFORCED,
            PolicyCheckInputCodec.decode(PolicyCheckInputCodec.encode(input)).enforcement,
            "a façade call that omits enforcement must remain ENFORCED, not SHADOW",
        )
    }

    // --- T5-c: back-compat — an M6 payload with no field is still ENFORCED ---

    @Test
    fun `05c an M6 payload without the field decodes to ENFORCED`() {
        val legacy = EncodedStepValue(
            """{"resourceBase64":"eyJmb28iOiJiYXIifQ==","resourceFormat":"JSON",""" +
                """"packedBundleBase64":"UEtCMQ=="}""",
        )

        assertEquals(
            EnforcementMode.ENFORCED,
            PolicyCheckInputCodec.decode(legacy).enforcement,
        )
    }

    // --- T5-d/e: invoke the REAL façade and read what it registered ---

    /**
     * The 05a-05c cases pin the payload, but they build the input themselves.
     * These two invoke the ACTUAL `policyCheck` façade against a real
     * `StageScope` and decode the input it registered, because that is the only
     * thing that proves the author-facing gap is closed.
     *
     * Two earlier attempts asserted only the function SIGNATURE via
     * reflection, and both were DECORATIVE: the mutations "hardcode ENFORCED
     * in the body" and "drop the default" survived them. A signature cannot
     * observe a body. These call the function instead.
     *
     * `StageScope` is real SDK API and constructible; `RuntimeConfig` is a
     * 5-method interface stubbed here because the façade never consults it.
     */
    @Test
    fun `05d a SHADOW script reaches the handler as SHADOW through the real facade`() {
        val scope = stageScope()
        val resource = tempFile("{\"spec\":{\"replicas\":1}}", ".json")
        val bundle = tempFile("PK", ".pkg")

        with(scope) { policyCheck(resource = resource, bundle = bundle, enforcement = EnforcementMode.SHADOW) }

        val input = registeredPolicyCheckInput(scope)
        assertEquals(
            EnforcementMode.SHADOW,
            input.enforcement,
            "the mode the script declared must be the mode the handler receives",
        )
    }

    @Test
    fun `05e a script that omits enforcement reaches the handler as ENFORCED`() {
        val scope = stageScope()
        val resource = tempFile("{\"spec\":{\"replicas\":1}}", ".json")
        val bundle = tempFile("PK", ".pkg")

        with(scope) { policyCheck(resource = resource, bundle = bundle) }

        assertEquals(
            EnforcementMode.ENFORCED,
            registeredPolicyCheckInput(scope).enforcement,
            "existing DSL scripts must keep working unchanged",
        )
    }

    // --- helpers ---

    private fun stageScope(): dev.rubentxu.pipeline.v2.dsl.StageScope {
        val config = object : dev.rubentxu.pipeline.v2.domain.RuntimeConfig {
            override fun env(name: String): String? = null
            override fun property(name: String): String? = null
            override fun property(name: String, default: String): String = default
            override fun osName(): String = "linux"
            override fun userDir(): String = System.getProperty("user.dir")
        }
        return dev.rubentxu.pipeline.v2.dsl.StageScope("policy-gate", config)
    }

    /**
     * Decode the `policy.check` input the façade registered on the scope.
     *
     * B4-T5: this helper was rewritten twice for good reasons, and both
     * rewrites REMOVED reflection rather than adding to it.
     *
     * 1. It originally read the scope's PRIVATE `steps` field. `StageScope`
     *    exposes `steps()` as public SDK API, so the field walk was never
     *    justified.
     * 2. It then walked `StepSpec` fields looking for a string containing
     *    `resourceBase64`. `registryStep` stores its payload on the typed
     *    `StepSpec.RegistryStepSpec`, so a SHAPE match was guessing where a
     *    TYPE was available.
     *
     * What survives is a real call into public SDK API and a real data class.
     * If the SDK changes, this fails with a named message rather than silently
     * matching some other field.
     */
    private fun registeredPolicyCheckInput(
        scope: dev.rubentxu.pipeline.v2.dsl.StageScope,
    ): PolicyCheckInput {
        val registered = scope.steps().filterIsInstance<dev.rubentxu.pipeline.v2.dsl.StepSpec.RegistryStepSpec>()
        assertTrue(
            registered.isNotEmpty(),
            "the façade must register a registry step; the SDK shape changed, " +
                "re-point this test. Registered: ${scope.steps()}",
        )
        val step = registered.single()
        return PolicyCheckInputCodec.decode(step.encodedInput)
    }

    private fun tempFile(content: String, suffix: String): String {
        val file = java.nio.file.Files.createTempFile("t5-", suffix)
        java.nio.file.Files.writeString(file, content)
        file.toFile().deleteOnExit()
        return file.toString()
    }
}
